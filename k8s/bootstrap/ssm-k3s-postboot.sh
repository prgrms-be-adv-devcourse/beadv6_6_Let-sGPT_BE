#!/usr/bin/env bash
# 상호 참조: 콜드 리빌드 시 이 스크립트(postboot), 라이브 클러스터 재작업 시 ssm-k3s-bootstrap.sh 사용.
# k3s 콜드 리빌드 post-boot 축소판 (terraform apply로 새 인스턴스가 생성된 뒤 로컬에서 1회 실행).
#
# 전제: k3s 설치·조인·라벨·taint·OIDC config·zram·helm은 전부 cloud-init(user_data.sh.tpl)이
#   이미 수행했다. 이 스크립트는 cloud-init으로 옮길 수 없는 잔여 항목만 담는다:
#     1) 두 노드 k3s 기동 확인(server 1 + agent 1, taint 자가 등록 확인)
#     2) OIDC discovery/JWKS 미러 게시 + 익명 조회 검증(JWKS 버킷 write는 노드 Role에 없음 —
#        공개 키 변조 = 클러스터 신원 위조라 사람 자격증명으로만 게시)
#     3) ArgoCD 설치 + openat Application 등록
#     4) 남은 수동 단계 안내(러너 등록; 시크릿은 CD가 자동 생성)
#
# 제거됨(→위치): zram·OIDC config 기록·server 설치·helm(→cloud-init),
#   토큰 획득·agent 조인(→사전공유 random_password 토큰), taint(→agent 자가 등록).
#
# 검토: k3s 버전은 cloud-init이 get.k3s.io 최신 채널로 설치하므로 리빌드 시점의 버전이
#   라이브와 다를 수 있다. 재현성이 필요하면 리빌드 직전 user_data.sh.tpl의 설치 라인에
#   INSTALL_K3S_VERSION=<라이브 버전> 을 고정할 것(예: curl ... | INSTALL_K3S_VERSION=v1.30.x sh -s ...).
set -euo pipefail

cd "$(dirname "$0")/../../terraform"
REGION=$(grep aws_region terraform.tfvars | cut -d'"' -f2)
SEMI_ID=$(terraform output -json instance_ids | python3 -c "import sys,json;print(json.load(sys.stdin)['semi'])")
BUCKET=$(terraform output -raw s3_bucket_name)
# S3 이미지 접근용 OIDC. terraform apply가 선행되어야 issuer URL이 확정된다 —
# 그 URL은 cloud-init이 k3s 설치 전 config.yaml에 이미 박아 두었다.
OIDC_ISSUER=$(terraform output -raw k3s_oidc_issuer_url)
JWKS_BUCKET=$(terraform output -raw oidc_jwks_bucket_name)
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

echo "== 1. 두 노드 k3s 기동 확인 (server 1 + agent 1, taint 자가 등록) =="
# agent 조인 대기 루프(cloud-init, 최대 10분)를 감안해 서버에서 노드 2개가 Ready로 뜰 때까지 대기.
# tier 라벨(cloud-init node-label)과 observability 노드의 taint 자가 등록을 함께 확인한다.
run_ssm "$SEMI_ID" "verify-nodes" '[
  "set -eu",
  "for i in $(seq 1 30); do READY=$(sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -c \" Ready \" || true); [ \"$READY\" -ge 2 ] && break; echo \"대기: Ready 노드 $READY/2\"; sleep 10; done",
  "sudo k3s kubectl get nodes -L tier",
  "NODES=$(sudo k3s kubectl get nodes --no-headers | grep -c \" Ready \" || true)",
  "[ \"$NODES\" -ge 2 ] || { echo \"FAIL: Ready 노드가 2개 미만 — agent 조인 실패 의심(/var/log/cloud-init-output.log 확인)\" >&2; exit 1; }",
  "echo ---taint---",
  "sudo k3s kubectl get nodes -l tier=observability -o jsonpath=\"{.items[*].spec.taints[*].key}\"; echo",
  "sudo k3s kubectl describe nodes -l tier=observability | grep -i taint || true"
]'

echo "== 2. OIDC discovery/JWKS 미러를 S3에 게시 =="
# apiserver는 discovery의 jwks_uri를 내부 advertise 주소(https://<private-ip>:6443/...)로
# 내보내는데 STS는 거기에 닿을 수 없다. 그래서 미러본에서 jwks_uri만 S3 URL로 바꿔 올린다.
# 업로드는 이 스크립트를 돌리는 사람의 자격증명(terraform/CI급)으로 한다 — 워크로드 Role에는
# 이 버킷 쓰기 권한이 없고 있어서도 안 된다(공개 키 변조 = 클러스터 신원 위조).
OIDC_CMD=$(aws ssm send-command --region "$REGION" --instance-ids "$SEMI_ID" \
  --document-name "AWS-RunShellScript" \
  --parameters '{"commands":["sudo k3s kubectl get --raw /.well-known/openid-configuration","echo ---JWKS---","sudo k3s kubectl get --raw /openid/v1/jwks"]}' \
  --query 'Command.CommandId' --output text)
aws ssm wait command-executed --region "$REGION" --command-id "$OIDC_CMD" --instance-id "$SEMI_ID"
OIDC_RAW=$(aws ssm get-command-invocation --region "$REGION" --command-id "$OIDC_CMD" --instance-id "$SEMI_ID" --query StandardOutputContent --output text)

OIDC_TMP=$(mktemp -d /tmp/oidc.XXXXXX)
OIDC_RAW="$OIDC_RAW" OIDC_ISSUER="$OIDC_ISSUER" OIDC_TMP="$OIDC_TMP" python3 <<'PY'
import json, os

raw = os.environ["OIDC_RAW"]
issuer = os.environ["OIDC_ISSUER"]
out = os.environ["OIDC_TMP"]

disc_raw, jwks_raw = raw.split("---JWKS---")
disc = json.loads(disc_raw)
jwks = json.loads(jwks_raw)

# 게이트: k3s가 자기 기본 issuer를 첫 항목으로 끼워넣어 primary를 가져가면 STS provider URL과
# 어긋나 전부 무너진다. 조용히 잘못된 걸 올리느니 여기서 멈춘다.
assert disc["issuer"] == issuer, "issuer 불일치: %s != %s" % (disc["issuer"], issuer)
assert jwks.get("keys"), "JWKS에 키가 없음"

disc["jwks_uri"] = issuer + "/openid/v1/jwks"

with open(os.path.join(out, "openid-configuration"), "w") as f:
    json.dump(disc, f)
with open(os.path.join(out, "jwks"), "w") as f:
    json.dump(jwks, f)
PY

aws s3 cp "$OIDC_TMP/openid-configuration" "s3://$JWKS_BUCKET/.well-known/openid-configuration" \
  --region "$REGION" --content-type application/json
aws s3 cp "$OIDC_TMP/jwks" "s3://$JWKS_BUCKET/openid/v1/jwks" \
  --region "$REGION" --content-type application/json
rm -rf "$OIDC_TMP"

# STS가 실제로 읽는 경로 그대로 익명 조회해 본다. 여기서 막히면 파드가 자격증명을 못 잡는다.
curl -sf "$OIDC_ISSUER/.well-known/openid-configuration" >/dev/null || { echo "FAIL: discovery 익명 조회 실패 — 버킷 정책 확인" >&2; exit 1; }
curl -sf "$OIDC_ISSUER/openid/v1/jwks" >/dev/null || { echo "FAIL: JWKS 익명 조회 실패 — 버킷 정책 확인" >&2; exit 1; }
echo "--- OIDC 미러 게시 완료: $OIDC_ISSUER"

echo "== 3. ArgoCD 설치 + openat Application 등록 =="
# ArgoCD "설치" 자체(k8s/argocd/)는 ArgoCD가 스스로를 watch하지 않는 1회성 부트스트랩이라
# S3(ops/argocd/) 경유로 전달한다. 실제 앱 매니페스트(k8s/)는 이 시점부터 ArgoCD의 openat
# Application이 deploy/state를 직접 watch·apply하므로 별도 S3 동기화가 필요 없다.
aws s3 sync k8s/argocd/ "s3://$BUCKET/ops/argocd/" --region "$REGION" --delete

# 서버 노드에서 내려받아 apply. 전부 root(SSM) 단일 블록 + mktemp로 소유권/잔재 충돌 차단.
# SSM는 /bin/sh(dash)로 실행 → pipefail 미지원. 파이프 없는 블록이라 set -eu로 충분.
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
· 이미지 pull 시크릿(ghcr-pull) + 도메인별 시크릿: 정식 경로는 CD(deploy.yml)가 자동 생성.
· GitHub Actions 러너 등록(단명 등록토큰이라 자동화 제외 — user_data.sh.tpl 주석 참조):
     cd /home/deployer/actions-runner
     ./config.sh --url https://github.com/<org>/<repo> --token <TOKEN> --labels ec2-deploy --unattended
     sudo ./svc.sh install deployer && sudo ./svc.sh start
EOF
