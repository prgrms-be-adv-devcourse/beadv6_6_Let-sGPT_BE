#!/usr/bin/env bash
# k3s 2노드 클러스터 부트스트랩 (terraform apply 완료 후, 로컬에서 1회 실행)
# 전제: aws cli + terraform output 접근 가능, SSM send-command 권한.
# 절차 = k3s_2node_cluster_plan.md §2~§6 (서버 설치 → 에이전트 조인 → taint → helm →
#        매니페스트 S3 경유 배포). Secret 생성(§4-3)은 마지막에 안내만 출력(값 필요).
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
  "curl -sfL https://get.k3s.io | sh -s - server --write-kubeconfig-mode 644 --node-label tier=hotpath",
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

echo "== 5. 매니페스트 S3 경유 업로드 → 서버 노드에서 apply =="
aws s3 sync k8s/ "s3://$BUCKET/k8s-bootstrap/" --region "$REGION" --exclude "bootstrap/*"
run_ssm "$SEMI_ID" "apply-manifests" "[
  \"mkdir -p /tmp/k8s && aws s3 sync s3://$BUCKET/k8s-bootstrap/ /tmp/k8s/ --region $REGION\",
  \"sudo k3s kubectl apply -f /tmp/k8s/00-namespace.yaml\",
  \"sudo k3s kubectl apply -f /tmp/k8s/\",
  \"sudo k3s kubectl get pods -n openat\"
]"

cat <<'EOF'

== 남은 수동 단계 (Secret — 값이 필요해 자동화 제외) ==
1) 서버 노드 SSM 세션 접속 후 .env(러너 작업 디렉토리 또는 로컬에서 전달)로:
     k8s/bootstrap/create-secrets.sh <.env 경로>
   + GHCR pull 시크릿(read:packages PAT 필요):
     GHCR_USER=<github id> GHCR_PAT=<PAT> k8s/bootstrap/create-secrets.sh <.env 경로>
2) 시크릿 생성 후 앱 파드 재기동:
     kubectl -n openat rollout restart deploy
EOF
