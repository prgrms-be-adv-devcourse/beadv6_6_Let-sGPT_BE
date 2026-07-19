#!/bin/bash
# EC2 부트스트랩: Docker 설치 + deployer 계정 생성 + SSM Agent 확인.
# 서버 접속은 SSH 대신 SSM Session Manager를 사용하므로 SSH 관련 설정 없음.
# k3s/docker-compose/애플리케이션 배포는 별도 CI/배포 스크립트의 책임이며 여기서 다루지 않는다.
set -euxo pipefail

# =====================================================================
# 1) Docker 설치
# =====================================================================
apt-get update -y
apt-get install -y ca-certificates curl gnupg

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

ARCH=$(dpkg --print-architecture)
CODENAME=$(. /etc/os-release && echo "$VERSION_CODENAME")
echo "deb [arch=$ARCH signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $CODENAME stable" \
  > /etc/apt/sources.list.d/docker.list

apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

# =====================================================================
# 1-b) AWS CLI (부트스트랩 §5의 서버측 `aws s3 sync` 등에 필요 — Ubuntu AMI 미탑재)
#    2026-07-15 GATE-1 debloat 반영: snap 대신 공식 zip 인스톨러 사용
#    (snapd 자체를 신규 인스턴스에 들이지 않기 위함, infra_sequencing_plan.md §7).
# =====================================================================
apt-get install -y unzip
curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
/tmp/aws/install
rm -rf /tmp/awscliv2.zip /tmp/aws

# -----------------------------------------------------------------------
# 안내: 내부 서비스(PostgreSQL, Kafka 등)는 보안그룹에서 외부 인바운드를
# 절대 열지 않는다. docker-compose에서 이런 서비스는 호스트 포트로
# publish(ports:)하지 말고, 도커 내부 네트워크(예: `docker network create internal`)에만 바인딩하여 컨테이너 간 통신에만 사용할 것.
# 외부에 노출해야 하는 서비스만 80/443 리버스 프록시를 통해 연결한다.
# -----------------------------------------------------------------------

# =====================================================================
# 2) deployer 계정 생성 (docker 그룹에만 추가, sudo 권한 없음)
#    SSM Run Command 실행 시 이 계정으로 docker 명령을 실행한다.
# =====================================================================
useradd -m -s /bin/bash ${deployer_user}
usermod -aG docker ${deployer_user}

# =====================================================================
# 3) SSM Agent: snap → deb 전환
#    Ubuntu 22.04 AMI엔 snap판이 사전 설치돼 있음 — 그대로 두면 GATE-1에서
#    걷어낸 snapd 의존이 신규 인스턴스마다 재생성된다(infra_sequencing_plan.md
#    §7 TODO). deb판으로 교체 후 snapd를 통째로 purge한다.
#    절차는 GATE-1 라이브 마이그레이션(같은 문서 §7-4~7)과 동일 원칙:
#    snap이 떠 있으면 deb install이 거부되므로 snap 제거가 먼저다.
# =====================================================================
snap stop amazon-ssm-agent 2>/dev/null || true
snap remove amazon-ssm-agent 2>/dev/null || true

curl -fsSL "https://s3.amazonaws.com/ec2-downloads-windows/SSMAgent/latest/debian_amd64/amazon-ssm-agent.deb" \
  -o /tmp/amazon-ssm-agent.deb

# dpkg 락 대기 (최대 60초) — GATE-1 final 노드 실제 장애 재발 방지
# (직전 unzip/apt-get install이 dpkg frontend lock을 순간적으로 쥐고 있어
# dpkg -i가 실패한 사례, infra_sequencing_plan.md §8-5).
DPKG_LOCK_WAITED=0
while fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1; do
  if [ "$DPKG_LOCK_WAITED" -ge 60 ]; then
    echo "ERROR: dpkg lock 60s 대기 후에도 안 풀림 — SSM agent 설치 중단" >&2
    exit 1
  fi
  sleep 1
  DPKG_LOCK_WAITED=$((DPKG_LOCK_WAITED + 1))
done

dpkg -i /tmp/amazon-ssm-agent.deb
rm -f /tmp/amazon-ssm-agent.deb
systemctl enable --now amazon-ssm-agent

# snapd 및 부수 데몬 purge (GATE-1 §7-8과 동일 목록)
apt-get purge -y snapd multipathd unattended-upgrades networkd-dispatcher policykit-1 || true

# =====================================================================
# 4) GitHub Actions self-hosted runner 설치 (등록은 제외)
#    등록 토큰(config.sh --token)은 발급 후 1시간만 유효한 단명 토큰이라
#    부팅 시 1회 실행되는 user_data에 박아둘 수 없음. 바이너리 설치까지만
#    여기서 끝내고, 인스턴스 생성 후 SSM Session Manager로 ${deployer_user}
#    계정으로 1회 수동 등록한다:
#      cd /home/${deployer_user}/actions-runner
#      ./config.sh --url https://github.com/<org>/<repo> --token <TOKEN> --labels ec2-deploy --unattended
#      sudo ./svc.sh install ${deployer_user} && sudo ./svc.sh start
# =====================================================================
apt-get install -y libicu-dev

RUNNER_DIR=/home/${deployer_user}/actions-runner
RUNNER_VERSION=2.319.1
mkdir -p "$RUNNER_DIR"
curl -fsSL -o /tmp/actions-runner.tar.gz \
  "https://github.com/actions/runner/releases/download/v$RUNNER_VERSION/actions-runner-linux-x64-$RUNNER_VERSION.tar.gz"
tar xzf /tmp/actions-runner.tar.gz -C "$RUNNER_DIR"
rm -f /tmp/actions-runner.tar.gz
chown -R ${deployer_user}:${deployer_user} "$RUNNER_DIR"

# =====================================================================
# 5) zram 스왑 (server/agent 공통 — 기존 런북 0단계를 cloud-init으로 흡수)
# =====================================================================
apt-get install -y zram-tools
printf "ALGO=zstd\nPERCENT=25\nPRIORITY=100\n" > /etc/default/zramswap
systemctl enable --now zramswap
systemctl restart zramswap
echo "vm.swappiness=100" > /etc/sysctl.d/99-zram.conf
sysctl -p /etc/sysctl.d/99-zram.conf

# =====================================================================
# 6) k3s 설치 — 역할별 config.yaml 선기록 후 설치(OIDC primary issuer 순서 보존)
# =====================================================================
mkdir -p /etc/rancher/k3s

%{ if k3s_role == "server" ~}
cat > /etc/rancher/k3s/config.yaml <<'K3SCFG'
secrets-encryption: true
write-kubeconfig-mode: "0644"
node-label:
  - "${k3s_node_label}"
kube-apiserver-arg:
  # 첫 항목이 primary — S3 URL이 첫 줄이어야 STS가 토큰을 받는다(순서 민감).
  - "service-account-issuer=${k3s_oidc_issuer}"
  - "service-account-issuer=https://kubernetes.default.svc.cluster.local"
  - "api-audiences=https://kubernetes.default.svc.cluster.local,k3s,sts.amazonaws.com"
kubelet-arg:
  # 콜드부트 CPU 아사 방지 + 스래싱 전 시끄러운 Evicted 전환(기존 런북 1단계 값 그대로)
  - "system-reserved=cpu=300m,memory=256Mi"
  - "kube-reserved=cpu=300m,memory=256Mi"
  - "eviction-hard=memory.available<400Mi"
  - "eviction-soft=memory.available<600Mi"
  - "eviction-soft-grace-period=memory.available=60s"
  - "eviction-max-pod-grace-period=30"
K3SCFG
chmod 600 /etc/rancher/k3s/config.yaml
echo "${k3s_token}" > /etc/rancher/k3s/cluster-token
chmod 600 /etc/rancher/k3s/cluster-token
curl -sfL https://get.k3s.io | sh -s - server --token-file /etc/rancher/k3s/cluster-token

# helm CLI (서버 노드 전용 — 기존 런북 4단계 흡수)
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
grep -q KUBECONFIG /home/ubuntu/.bashrc 2>/dev/null || echo "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml" >> /home/ubuntu/.bashrc
%{ else ~}
cat > /etc/rancher/k3s/config.yaml <<'K3SCFG'
server: "https://${k3s_server_ip}:6443"
node-label:
  - "${k3s_node_label}"
%{ if k3s_node_taint != "" ~}
node-taint:
  # 조인 시점 자가 등록 — 기존 런북의 사후 taint 단계를 대체. post-boot 잔여 아님.
  - "${k3s_node_taint}"
%{ endif ~}
K3SCFG
chmod 600 /etc/rancher/k3s/config.yaml
echo "${k3s_token}" > /etc/rancher/k3s/cluster-token
chmod 600 /etc/rancher/k3s/cluster-token
# agent는 서버 6443이 열릴 때까지 재시도 — cloud-init은 병렬 부팅 순서를 보장하지 않는다.
for i in $(seq 1 60); do
  curl -ksf --max-time 5 "https://${k3s_server_ip}:6443/ping" && break
  sleep 10
done
curl -sfL https://get.k3s.io | sh -s - agent --token-file /etc/rancher/k3s/cluster-token
%{ endif ~}
