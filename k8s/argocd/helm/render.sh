#!/usr/bin/env bash
# ArgoCD 설치 매니페스트 렌더 파이프라인 (k3s_memory_mitigation_plan §9-12, C안).
#
# values.yaml 을 argo-cd 차트에 먹여 helm template 으로 렌더한 뒤, 차트가 values 로 못 끄는
# applicationset-controller(코어 편입, 토글 삭제됨)와 그 CRD 를 후처리로 제거하고,
# 결과를 ../argocd-install.yaml 로 커밋한다. repo-server 는 이 정적 파일만 읽으므로(원격 fetch 없음)
# 렌더 부하가 빌드타임(CI/로컬)으로 이관된다 — 관측 스택(observability/openat)과 동일한 정적-파일 패턴.
#
# 사용: bash k8s/argocd/helm/render.sh   (helm, python3+pyyaml 필요; CI 가 자동 실행 — .github/workflows/argocd-render.yml)
set -euo pipefail
cd "$(dirname "$0")"

CHART_REPO="https://argoproj.github.io/argo-helm"
CHART_VERSION="10.1.2"            # ↔ ArgoCD 앱버전 v3.4.4 (기존 install.yaml URL 과 동일 버전 유지; 10.1.3 은 v3.4.5 로 patch bump 라 회피)
RELEASE="argocd"
NAMESPACE="argocd"
OUT="../argocd-install.yaml"

helm repo add argo "$CHART_REPO" >/dev/null 2>&1 || true
helm repo update argo >/dev/null

# 1) helm 렌더 (hook 포함 — redis-secret-init Job 등)
helm template "$RELEASE" argo/argo-cd \
  --version "$CHART_VERSION" \
  --namespace "$NAMESPACE" \
  --include-crds \
  -f values.yaml > .rendered-base.yaml

# 2) applicationset-controller 매니페스트 + 그 CRD 제거 (values 토글 부재 → 매니페스트째 strip)
python3 - "$CHART_VERSION" <<'PY'
import sys, yaml
src = ".rendered-base.yaml"
docs = [d for d in yaml.safe_load_all(open(src)) if d]

def is_appset(d):
    lbl = (d.get("metadata", {}).get("labels") or {})
    if lbl.get("app.kubernetes.io/component") == "applicationset-controller":
        return True
    # 컨트롤러가 사라지면 쓸모없어지는 applicationset CRD 도 함께 제거(과거 262144B annotation 이슈 근원)
    if d.get("kind") == "CustomResourceDefinition" and d["metadata"]["name"] == "applicationsets.argoproj.io":
        return True
    return False

kept = [d for d in docs if not is_appset(d)]
banner = ("# ============================================================================\n"
          "# 자동 생성 파일 — 직접 수정 금지. 설정은 helm/values.yaml 에서 바꾸고\n"
          "# `bash k8s/argocd/helm/render.sh` 로 재생성한다(CI: argocd-render.yml 자동).\n"
          "# argo-cd chart %s / ArgoCD v3.4.4. applicationset-controller+CRD 는 렌더 후 strip 됨.\n"
          "# ============================================================================\n" % sys.argv[1])
with open("../argocd-install.yaml", "w") as f:
    f.write(banner)
    f.write(yaml.safe_dump_all(kept, default_flow_style=False, sort_keys=False))
print("rendered %d docs (stripped %d appset docs) -> ../argocd-install.yaml" % (len(kept), len(docs) - len(kept)))
PY

rm -f .rendered-base.yaml
echo "done: $OUT"
