# 부하테스트 오버레이 (가짜 PG + 측정 조건)

부하테스트 중에만 적용되는 desired-state 묶음이다. 목 주입을 `kubectl set env`·수동
apply로 하던 방식(자동동기화를 캠페인 내내 꺼야 했고, 지연값이 파드 재시작마다 증발했다)을
대체한다. **목 켜기/끄기 = 커밋 한 줄**이고, 자동동기화는 캠페인 중에도 켠 채 둔다 —
sync가 목을 파괴하는 게 아니라 오히려 강제·복원한다.

## 이 오버레이가 얹는 것

- `wiremock.yaml` — 가짜 토스페이먼츠(WireMock) Deployment·Service.
- `mappings/*.json` — 목 응답 스텁 10개(confirm 정상 1·confirm 결함 5·cancel 3·조회 1).
  confirm 정상 경로의 lognormal median 800ms가 캠페인 표준 지연이다. 이 파일들이 유일한
  원본이고, kustomization의 configMapGenerator가 내용 해시 이름의 ConfigMap으로 운반한다.
- `patch-payment-pg-mock.yaml` — payment의 `PG_BASE_URL`을 WireMock으로, 커넥션풀 4,
  PG 호출 유량제한 50/1000ms(램프 라운드 측정 조건).
- `patch-apigateway-ratelimit.yaml` — confirm 라우트 유량제한 100/200(부하 생성이
  게이트웨이에 먼저 막히지 않게 하는 테스트 편의값).

이미지 태그 핀은 `resources: [../overlay]`로 상속한다. 즉 부하테스트 중에도 팀원의 배포가
평소처럼 목 위로 롤링된다.

## 켜기

이 디렉토리가 `deploy/state`에 이미 존재하는 상태에서만 켠다(그렇지 않으면 Application이
없는 경로를 가리켜 ComparisonError). 준비 커밋이 main에 머지돼 CD가 한 번 녹색이면 충족.

1. dev에서 브랜치를 따고 `k8s/argocd/app-openat.yaml`의 한 줄만 바꾼다.

   ```yaml
   -    path: k8s/overlay
   +    path: k8s/overlay-loadtest
   ```

2. dev → main 머지. main의 `k8s/argocd/`를 추적하는 자기감시 Application이 폴링
   (120초 + 지터 최대 60초) 안에 클러스터의 openat Application 스펙을 갱신한다.
3. 전환 완료 판정은 CD 녹색과 별개로 직접 확인한다 — CD의 수렴 대기는 `deploy/state`
   커밋 기준이라 path 반영보다 먼저 통과할 수 있다.

   ```bash
   kubectl -n argocd get application openat -o jsonpath='{.spec.source.path}'   # k8s/overlay-loadtest
   kubectl -n openat get deploy wiremock-toss                                    # READY 1/1
   kubectl -n openat get deploy payment -o jsonpath='{range .spec.template.spec.containers[0].env[?(@.name=="PG_BASE_URL")]}{.value}{end}'
   ```

4. 램프업 전에 목이 실제로 맞고 있는지 확인(실 토스 오발사 방지): 스모크 1회 후
   WireMock 저널 count가 0보다 큰지 —
   `kubectl -n openat exec deploy/wiremock-toss -- wget -qO- localhost:8080/__admin/requests | head`.

## 끄기

켜기 커밋을 revert해서 main에 머지한다. path가 `k8s/overlay`로 돌아가면 sync가 payment를
실 PG로 되돌리고, wiremock Deployment·Service·매핑 ConfigMap은 prune이 철거한다(openat
Application의 automated에 prune이 켜져 있다). 별도 `kubectl`은 없다.

확인: payment env에 `PG_BASE_URL` 부재, `kubectl -n openat get deploy,svc,cm | grep wiremock`
결과 없음, 실 결제 스모크 1건.

## 캠페인 중 값 바꾸기

지연·스텁 변경은 `mappings/*.json`을 고치는 PR이다. ConfigMap 이름에 내용 해시가 붙어
파드가 자동 재생성되므로 `rollout restart`가 필요 없고, 관리 API 편집과 달리 재시작에도
값이 살아남는다. 다만 wiremock은 Recreate 전략이라 반영 시 수 초~수십 초 목 다운타임이
생긴다 — **매핑 PR은 측정 라운드 사이에 머지한다.**

## "오버레이가 켜져 있다"를 알아채는 법

- ArgoCD UI의 openat Application source path가 `k8s/overlay-loadtest`면 켜진 상태다.
- CLI: `kubectl -n argocd get application openat -o jsonpath='{.spec.source.path}'`.
- 켠 채로 방치하면 결제 승인·취소가 전부 허구가 되므로, 캠페인 종료 시 끄기 PR을 반드시
  올린다(진행 상황은 작업 문서의 진행 항목으로도 추적한다).

## 검증(로컬)

```bash
kustomize build k8s/overlay-loadtest > /dev/null       # CI의 dry-run과 동일
diff <(kustomize build k8s/overlay) <(kustomize build k8s/overlay-loadtest)
```

diff에는 wiremock 리소스 3종 추가, payment·apigateway env 변경만 나와야 하고, 이미지
태그는 양쪽이 같아야 한다(핀 상속 확인). 매핑 ConfigMap의 해시 이름이 wiremock의 volume
참조와 일치하는지도 함께 본다 — generator의 `namespace: openat`이 빠지면 이 치환이 깨져
파드가 기동하지 못한다.

## 매핑 원본 위치

`loadtest/wiremock/mappings/`에 구 방식(수동 apply 스크립트)용 사본이 남아 있지만,
**정본은 이 디렉토리의 `mappings/`다.** 배포 전파 필터가 `k8s/**`만 보기 때문에
`loadtest/` 아래만 고친 커밋은 desired-state에 반영되지 않는다. 구 스크립트 경로는
다음 정리 단계에서 퇴역시킨다.
