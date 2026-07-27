# 노드 PSI node-exporter — 배포 후 검증과 롤백

대상: `12-node-exporter.yaml`(DaemonSet), `config/otel-collector.yaml`의 `node-exporter` 스크레이프 잡,
`31-grafana-dashboard-platform.yaml`의 '노드 압력 (PSI — node-exporter)' 행.

이 문서는 배포 전에 읽고 **배포 직후 그 자리에서** 실행한다. 순서대로 내려가되 실패하면
멈추고 아래 '롤백'으로 간다.

## 무엇이 불확실한가 (검증의 초점)

커널에 PSI가 있다는 것은 이미 실증됐다. 부하 측정용 별도 스크립트가 두 노드에서
`/proc/pressure/cpu`를 계속 읽어 값을 얻고 있으며(Ubuntu 22.04, 커널 6.8), 노드 CPU 사용률
32%일 때 cpu some avg10 96%라는 수치가 그 경로에서 나왔다. 즉 **커널이 PSI를 내는지는 이미
답이 나온 질문**이다.

남은 불확실성은 둘이다.

1. **파드가 그것을 지표로 내보내는가.** exporter는 호스트 `/proc` 전체가 아니라
   `/proc/pressure` 만 `/host/proc/pressure` 로 받고 `--path.procfs=/host/proc`(빈 emptyDir)를
   쓴다. 소스 확인상 `procfs.NewFS`는 `os.Stat` + `IsDir` 만 검증하고 pressure 컬렉터는
   `<path.procfs>/pressure/<resource>` 만 읽으므로 성립해야 하지만, **실제 클러스터에서
   기동하는 것은 아직 확인되지 않았다.** 여기가 깨지면 증상은 CrashLoopBackOff다.
2. **스크레이프와 라벨이 붙는가.** 전용 잡이 파드를 발견하고 `node` 라벨을 채우는지.

## 배포 전

- [ ] 이 브랜치가 `main`에 들어가면 자동으로 반영된다. observability Application은
      `automated: { prune: true, selfHeal: true }` 이고 `targetRevision: main`
      (`k8s/argocd/app-observability.yaml`). 즉 **머지 = 배포**이고, 클러스터에서 손으로 되돌려도
      selfHeal이 되살린다.
- [ ] 부하 라운드 진행 중이면 머지하지 않는다. DaemonSet 롤아웃이 노드에 잠깐 부하를 준다.
- [ ] 로컬 렌더가 통과하는지 확인한다(클러스터 접근 아님).

      kubectl kustomize k8s/observability > /dev/null && echo OK

## 배포 직후 — 1) 파드가 떴는가

가장 먼저 볼 것. 좁힌 `/proc` 마운트가 틀렸다면 여기서 CrashLoopBackOff로 드러난다.

    kubectl -n observability rollout status daemonset/node-exporter --timeout=120s
    kubectl -n observability get daemonset node-exporter

기대값: `DESIRED = CURRENT = READY = 2` (노드 2대, 모든 테인트를 tolerate하므로 전 노드에 뜬다).
`rollout status`가 타임아웃 없이 끝나야 한다.

    kubectl -n observability get pods -l app=node-exporter -o wide

기대값: 파드 2개 `Running`, `RESTARTS 0`, `NODE` 열에 서로 다른 노드 두 개.

**Running이 아니면** 사유부터 본다.

    kubectl -n observability describe pod -l app=node-exporter | sed -n '/Events/,$p'
    kubectl -n observability logs -l app=node-exporter --tail=50 --all-containers

읽는 법:
- `could not read "/host/proc"` / `mount point ... is not a directory` → `procfs.NewFS` 실패.
  좁힌 마운트 구성이 원인이다. 즉시 롤백.
- `hostPath type check failed: /proc/pressure is not a directory` → 노드에 `/proc/pressure` 가
  없다. PSI 미지원 커널이라는 뜻이므로 이 기능 자체가 성립하지 않는다. 롤백.
- `no data` / `collector pressure failed` 로그만 있고 파드는 Running → 파드는 정상이고 지표만
  없는 경우다. 롤백 대상은 아니고 3)에서 원인을 좁힌다.
- `OOMKilled` → limits 64Mi가 부족. 롤백 후 limits 상향으로 재시도.

## 배포 직후 — 2) exporter가 직접 내는 값

스크레이프 경로를 빼고 exporter 자체를 확인한다. 파드는 떴는데 지표가 없는 경우를
여기서 가른다.

    kubectl -n observability port-forward daemonset/node-exporter 9100:9100 &
    curl -s localhost:9100/metrics | grep '^node_pressure'
    kill %1

기대값: 아래 6종이 각각 한 줄씩 나온다(커널 6.1+ 이면 `node_pressure_irq_stalled_seconds_total`
가 하나 더 붙을 수 있다 — 있어도 없어도 정상).

    node_pressure_cpu_waiting_seconds_total
    node_pressure_cpu_stalled_seconds_total
    node_pressure_io_waiting_seconds_total
    node_pressure_io_stalled_seconds_total
    node_pressure_memory_waiting_seconds_total
    node_pressure_memory_stalled_seconds_total

`node_pressure_cpu_stalled_seconds_total` 은 시스템 전역에서 커널이 의미 있게 채우지 않아
값이 0에 머무는 게 정상이다 — 시리즈가 존재하는지만 본다.

한 줄도 안 나오면 마운트는 됐는데 컬렉터가 읽지 못한 것이다. 다음으로 마운트 자체를 본다.

    kubectl -n observability exec daemonset/node-exporter -- ls -l /host/proc/pressure

기대값: `cpu`, `io`, `memory` (그리고 커널에 따라 `irq`). 여기가 비어 있으면 hostPath 마운트가
안 붙은 것이므로 롤백.

## 배포 직후 — 3) Prometheus에 들어왔는가

Grafana의 Explore(또는 Prometheus UI)에서 아래를 순서대로 실행한다.

**스크레이프 성공 여부와 node 라벨** — 이 하나로 대부분이 갈린다.

    up{job="node-exporter"}

기대값: 결과 2건, 각각 값 `1`, 라벨에 `node="<노드명>"` 과 `pod="node-exporter-xxxxx"` 가 있고
두 건의 `node` 값이 서로 다르다.

- 결과 0건 → 서비스 디스커버리가 파드를 못 찾았다. 잡의 keep 정규식
  (`observability;node-exporter`)과 파드 라벨 `app: node-exporter` 를 대조한다.
- 값이 `0` → 대상은 찾았는데 스크레이프가 실패. NetworkPolicy(`observability-intra-only`)와
  파드 IP:9100 도달성을 본다.
- `node` 라벨이 비어 있음 → 잡의 `__meta_kubernetes_pod_node_name` relabel이 안 먹은 것.
- 결과가 1건뿐 → 한 노드에 파드가 안 떴다. 1)로 돌아간다.

숫자로 한 번에 보려면:

    count(up{job="node-exporter"} == 1)          # 기대값 2
    count(count by (node) (up{job="node-exporter"}))   # 기대값 2 (노드 라벨이 둘로 갈리는지)

**PSI 시계열 6종이 생겼는가**

    count by (__name__) ({__name__=~"node_pressure_.*"})

기대값: 이름 6종(또는 irq 포함 7종)이 각각 `2`(노드 2대). 이름이 빠졌거나 값이 1이면 그 조합이
안 들어온 것이다.

**대시보드가 쓰는 형태 그대로**

    max by (node) (rate(node_pressure_cpu_waiting_seconds_total[5m])) * 100

기대값: 결과 2건. 단 카운터 `rate` 는 창 안에 최소 2개 샘플이 필요하고 스크레이프 주기가 30초라
**배포 후 1~2분은 비어 있는 게 정상**이다. 5분 창이므로 5분 뒤 값이 안정된다. 성급하게
'실패'로 판정하지 말 것.

**Grafana 화면 확인**

'전체 개요' 폴더 → '인프라 — DB·파드 리소스' → 맨 아래 '노드 압력 (PSI — node-exporter)' 행.
'수집기 상태 (node-exporter up, 노드별)' 스탯이 노드 2개 모두 '수집 중'(초록)이고, 왼쪽
'현재 노드 압력 요약'에 노드×3자원 = 6개 값이 채워지면 끝이다.

## 롤백

**클러스터에서 손으로 지우면 안 된다.** observability Application은 `selfHeal: true` 라
`kubectl delete daemonset node-exporter` 를 해도 Argo CD가 곧바로 되살린다. 되돌리는 유일한
방법은 **git에 리버트 커밋을 넣어 `main`을 되돌리는 것**이다.

    git revert <배포된 커밋 해시>
    # 브랜치 규칙상 dev를 거쳐 main으로 올린다. main이 dev보다 앞서지 않게 한다.

리버트 후 확인:

    kubectl -n observability get daemonset node-exporter    # NotFound 가 되어야 한다

**정말 급할 때의 임시 정지**(리버트 커밋을 준비하는 동안만): Application의 자동동기화를 끄고
지운다. 끈 상태를 방치하면 이후 다른 관측 스택 변경도 반영되지 않으니 반드시 되돌린다.

    kubectl -n argocd patch application observability --type=json \
      -p '[{"op":"remove","path":"/spec/syncPolicy/automated"}]'
    kubectl -n observability delete daemonset node-exporter
    # 리버트 머지 후 자동동기화 복구
    kubectl -n argocd patch application observability --type=merge \
      -p '{"spec":{"syncPolicy":{"automated":{"prune":true,"selfHeal":true}}}}'

**부분 롤백**: 파드가 CrashLoop인데 원인이 좁힌 `/proc` 마운트로 확인된 경우, 기능 전체를
버리지 말고 `12-node-exporter.yaml` 의 volumes를 예전 형태(호스트 `/proc` 전체를
`/host/proc` 에 readOnly 마운트)로 되돌리는 커밋만으로도 복구된다. 다만 그 형태는 호스트
프로세스·네트워크 정보를 컨테이너에 노출하므로, 되돌린다면 그 사실과 이유를 매니페스트
주석에 남긴다.

## 대시보드만 되돌리고 싶을 때

대시보드 ConfigMap(31/32)은 Grafana 파일 프로바이더가 재시작 없이 라이브 리로드한다
(`kustomization.yaml` 주석 참조). 즉 리버트 커밋이 동기화되면 파드 재기동 없이 화면이 돌아온다.
