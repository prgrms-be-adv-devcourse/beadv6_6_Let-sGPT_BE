# OpenTelemetry Collector 올바른 활용 정리

## 왜 Collector를 두는가 (앱 → Tempo 직결 대비 이점)

| 이점 | Collector 있을 때 | 없을 때(앱 직결) |
|---|---|---|
| **Tail sampling (후행 샘플링)** | 트레이스 전체를 보고 판단: 에러·느린 요청 100%, 정상은 일부만 | head 샘플링뿐 — 무작위 %만, 에러도 같이 버려짐 |
| **K8s 메타데이터 자동 주입** | pod/namespace/node/deployment를 트레이스에 자동 부착 | 앱 코드가 직접 넣어야 함 |
| **노이즈 제거 / 마스킹** | healthcheck 등 불필요 트레이스를 저장 전 폐기, 민감정보 스크럽 | 전량 저장소로 감 |
| **Application Offloading** | 앱은 로컬 네트워크로 던지고 끝. 배치·재시도는 collector가 | 앱이 직접 압축·배치·재시도 부담 |
| **Decoupling** | 앱은 collector만 바라봄. 저장소 교체/주소 변경이 collector 한 곳 수정으로 끝 | 저장소 IP/DNS 바뀌면 모든 앱 파드 수정 |

## 우리 프로젝트 적용 현황

파이프라인 (traces):
`memory_limiter → filter/noise → transform/scrub → k8sattributes → tail_sampling → batch → resource → otlp/tempo`

적용:
- **Tail sampling**: ERROR 100% / HTTP 5xx 100% / latency>2s 100% / 정상 5%(`OTEL_TAIL_SAMPLING_PERCENT`). 기존 head `probabilistic_sampler`(10%)는 제거 — head가 먼저 버리면 tail이 에러·느린 트레이스를 못 보기 때문.
- **K8s 메타데이터**: pod/namespace/node/deployment/uid + `app` 라벨 주입. k8sattributes는 ServiceAccount RBAC(pods·namespaces·replicasets get/list/watch) 필요 → 반영됨. 라벨은 `app`만 화이트리스트 — k8s가 자동으로 붙이는 `pod-template-hash`가 catch-all(`.*`)에 걸리면 롤아웃마다 값이 바뀌어 카디널리티 폭증하므로 제외.
- **노이즈 제거**: actuator/health/readiness/liveness 스팬을 tail 이전에 드랍(메모리 절약).
- **최소 마스킹**: `db.statement` 삭제 + `http.url`/`url.full` 쿼리스트링 제거. payment/settlement 등 금융 경로 PII가 Tempo에 평문 저장되는 것 방지. (범용 redaction은 비용 대비 스킵)
- **Offloading**: 앱 7종(apigateway/member/product/order/payment/settlement/search)이 collector:4318로만 OTLP push. `batch` 사용. 압축·`retry_on_failure`·`sending_queue`는 의도적 스킵 — 클러스터 내부라 대역폭 저렴, 트레이스는 유실 허용 가능한 관측 데이터, OOM은 `memory_limiter`가 방어. `sending_queue`는 exporter 기본값 유지.
- **Decoupling**: 앱 7종 → collector 단일 엔드포인트. Tempo 주소 변경 시 collector 한 곳만 수정.
- **메모리**: tail_sampling이 decision_wait 동안 트레이스를 버퍼링 → 256Mi에서 512Mi로 상향(memory_limiter 400/100).

## ⚠️ 단일 Gateway = tail_sampling SPOF (반드시 인지)

tail_sampling은 **한 트레이스의 모든 스팬이 같은 collector 인스턴스에 도달해야** 정확히 판단한다. 현재 collector는 단일 gateway(replicas: 1)라 이 조건이 자연히 성립하지만, 동시에 **단일 장애점(SPOF)**이다. collector 파드가 죽으면 트레이스 수집 전체가 멈춘다.

**HA로 스케일(replicas>1) 하는 순간 tail_sampling이 깨진다.** 여러 인스턴스에 같은 트레이스의 스팬이 나뉘어 도착하면 각자 부분만 보고 잘못된 샘플링 결정을 내린다.

→ **HA scale 시 반드시 앞단에 `loadbalancing` exporter 계층을 두어** trace ID 기준으로 같은 트레이스를 같은 tail_sampling collector로 라우팅해야 한다 (2계층 구조: 수신 collector → loadbalancing → tail_sampling collector들). **현재는 단일 인스턴스 전제이며, 이 부분은 미래 숙제로 남겨둔다.**

## 배포 전 주의
- `otelcol validate` 미실행(환경에 바이너리 없음). 구조 YAML만 검증됨 → 클러스터 dry-run 권장.
- status code 정책 키는 신 semconv `http.response.status_code` 기준(Spring Boot 4.1 / Micrometer Tracing 1.7). 앱 semconv 버전 바뀌면 키 재확인.
