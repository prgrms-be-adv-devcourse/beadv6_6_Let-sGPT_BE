#!/usr/bin/env bash
# k3s 2노드 클러스터 부트스트랩 (terraform apply 완료 후, 로컬에서 1회 실행)
# 전제: aws cli + terraform output 접근 가능, SSM send-command 권한.
# 절차 = k3s_2node_cluster_plan.md §2~§6 (서버 설치 → 에이전트 조인 → taint → helm →
#        ArgoCD 설치 + Application 등록). Secret 생성(§4-3)은 마지막에 안내만 출력(값 필요).
# 2026-07-09: 앱 매니페스트(k8s/) 배포 경로를 S3 스냅샷(ops/k8s/) apply에서 ArgoCD auto-sync로
#        전환(argocd_ci_smoke_plan WS-B-3) — 콜드부트 후에는 ArgoCD가 deploy/state에서 직접
#        수렴한다. ops/k8s/ 스냅샷은 이 스크립트가 더 이상 안 씀(과거 산출물, qna Q87-1).
set -euo pipefail

cd "$(dirname "$0")/../../terraform"
REGION=$(grep aws_region terraform.tfvars | cut -d'"' -f2)
SEMI_ID=$(terraform output -json instance_ids | python3 -c "import sys,json;print(json.load(sys.stdin)['semi'])")
FINAL_ID=$(terraform output -json instance_ids | python3 -c "import sys,json;print(json.load(sys.stdin)['final'])")
SEMI_PRIV=$(terraform output -json instance_private_ips | python3 -c "import sys,json;print(json.load(sys.stdin)['semi'])")
BUCKET=$(terraform output -raw s3_bucket_name)
cd ..

run_ssm() { # $1=instance-id $2=comment $3...=commands(json array 문자열)
  local iid="$1" comment="$2" cmds="$3"
  local cmd_id
  cmd_id=$(aws ssm send-command --region "$REGION" --instance-ids "$iid" \
    --document-name "AWS-RunShellScript" --comment "$comment" \
    --parameters "{\"commands\":$cmds}" \
    --query 'Command.CommandId' --output text)
  aws ssm wait command-executed --region "$REGION" --command-id "$cmd_id" --instance-id "$iid" || true
  local status
  status=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cmd_id" --instance-id "$iid" --query Status --output text)
  echo "--- [$comment] status=$status"
  aws ssm get-command-invocation --region "$REGION" --command-id "$cmd_id" --instance-id "$iid" --query StandardOutputContent --output text | tail -20
  if [ "$status" != "Success" ]; then
    aws ssm get-command-invocation --region "$REGION" --command-id "$cmd_id" --instance-id "$iid" --query StandardErrorContent --output text | tail -20 >&2
    echo "FAIL: $comment — 중단" >&2; exit 1
  fi
}

echo "== 1. k3s server 설치 (semi/t3.large, tier=hotpath) =="
run_ssm "$SEMI_ID" "k3s-server-install" '[
  "curl -sfL https://get.k3s.io | sh -s - server --secrets-encryption --write-kubeconfig-mode 644 --node-label tier=hotpath",
  "sudo k3s kubectl get nodes"
]'

echo "== 2. 조인 토큰 획득 =="
TOKEN_CMD=$(aws ssm send-command --region "$REGION" --instance-ids "$SEMI_ID" \
  --document-name "AWS-RunShellScript" \
  --parameters '{"commands":["sudo cat /var/lib/rancher/k3s/server/node-token"]}' \
  --query 'Command.CommandId' --output text)
aws ssm wait command-executed --region "$REGION" --command-id "$TOKEN_CMD" --instance-id "$SEMI_ID"
K3S_TOKEN=$(aws ssm get-command-invocation --region "$REGION" --command-id "$TOKEN_CMD" --instance-id "$SEMI_ID" --query StandardOutputContent --output text | tr -d '\n')
[ -n "$K3S_TOKEN" ] || { echo "FAIL: 조인 토큰 획득 실패" >&2; exit 1; }

echo "== 3. k3s agent 조인 (final/t3.medium, tier=observability) =="
run_ssm "$FINAL_ID" "k3s-agent-join" "[
  \"curl -sfL https://get.k3s.io | K3S_URL=https://$SEMI_PRIV:6443 K3S_TOKEN=$K3S_TOKEN sh -s - agent --node-label tier=observability\"
]"

echo "== 4. taint + helm CLI (서버 노드) =="
run_ssm "$SEMI_ID" "taint-and-helm" '[
  "MEDIUM=$(sudo k3s kubectl get nodes -l tier=observability -o name | cut -d/ -f2)",
  "sudo k3s kubectl taint nodes $MEDIUM dedicated=observability:NoSchedule --overwrite",
  "sudo k3s kubectl get nodes -L tier",
  "command -v helm >/dev/null || (curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash)",
  "grep -q KUBECONFIG /home/ubuntu/.bashrc 2>/dev/null || echo export KUBECONFIG=/etc/rancher/k3s/k3s.yaml >> /home/ubuntu/.bashrc",
  "helm version --short || true"
]'

echo "== 5. ArgoCD 설치 + openat Application 등록 =="
# ArgoCD "설치" 자체(k8s/argocd/)는 ArgoCD가 스스로를 watch하지 않는 1회성 부트스트랩이라
# 기존과 동일하게 S3 경유로 전달한다 — 단 prefix는 ops/k8s/(구 앱 매니페스트 스냅샷, 이제 미사용)
# 와 별개인 ops/argocd/. 실제 앱 매니페스트(k8s/)는 이 시점부터 ArgoCD의 openat Application이
# deploy/state를 직접 watch·apply하므로 별도 S3 동기화가 필요 없다(WS-B-3, qna Q87-1).
aws s3 sync k8s/argocd/ "s3://$BUCKET/ops/argocd/" --region "$REGION" --delete

# 서버 노드에서 내려받아 apply. 전부 root(SSM) 단일 블록 + mktemp로 소유권/잔재 충돌 차단(Q80 #4).
# SSM는 /bin/sh(dash)로 실행 → pipefail 미지원. 파이프 없는 블록이라 set -eu로 충분.
# set -eu + test -s 로 silent-failure(Q80 #3) 제거 — 마지막 get이 exit 0로 위장 못 함.
run_ssm "$SEMI_ID" "argocd-install" "[
  \"set -eu\",
  \"DIR=\$(mktemp -d /tmp/argocd.XXXXXX)\",
  \"aws s3 sync s3://$BUCKET/ops/argocd/ \\\"\$DIR\\\"/ --region $REGION\",
  \"test -s \\\"\$DIR/kustomization.yaml\\\"\",
  \"sudo k3s kubectl apply -k \\\"\$DIR\\\"/\",
  \"rm -rf \\\"\$DIR\\\"\",
  \"sudo k3s kubectl -n argocd get application openat\",
  \"sudo k3s kubectl -n argocd get pods -o wide\"
]"

cat <<'EOF'

== 남은 수동 단계 ==
· 이미지 pull 시크릿(ghcr-pull) + app-secrets: 정식 경로는 CD(deploy.yml)가 자동 생성.
  최초 부팅 검증용으로 수동 생성하려면(서버 노드 SSM 세션에서, .env 필요):
     ./create-secrets.sh <.env 경로>                                   # app-secrets
     GHCR_USER=<github id> GHCR_PAT=<packages:read PAT> ./create-secrets.sh <.env 경로>   # + ghcr-pull
     sudo k3s kubectl patch serviceaccount default -n openat -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}'
· GitHub Actions 러너 등록(단명 등록토큰이라 자동화 제외 — user_data 주석 참조).
EOF
