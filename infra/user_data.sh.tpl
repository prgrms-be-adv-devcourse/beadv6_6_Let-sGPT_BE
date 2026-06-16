#!/bin/bash
# EC2 부트스트랩: 도커 설치 + SSH 하드닝 + fail2ban + deployer 계정 생성까지만 수행한다.
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

# -----------------------------------------------------------------------
# 안내: 내부 서비스(PostgreSQL, Kafka 등)는 보안그룹에서 외부 인바운드를
# 절대 열지 않는다. docker-compose에서 이런 서비스는 호스트 포트로
# publish(ports:)하지 말고, 도커 내부 네트워크(예: `docker network create
# internal`)에만 바인딩하여 컨테이너 간 통신에만 사용할 것.
# 외부에 노출해야 하는 서비스만 80/443 리버스 프록시를 통해 연결한다.
# -----------------------------------------------------------------------

# =====================================================================
# 2) deployer 계정 생성 (docker 그룹에만 추가, sudo 권한 없음)
# =====================================================================
useradd -m -s /bin/bash ${deployer_user}
usermod -aG docker ${deployer_user}

# =====================================================================
# 3) SSH 공개키 등록 (ubuntu 계정 + deployer 계정)
# =====================================================================
SSH_KEYS=$(cat <<'KEYS_EOF'
${ssh_public_keys}
KEYS_EOF
)

for u in ubuntu ${deployer_user}; do
  home_dir="/home/$u"
  install -d -m 700 -o "$u" -g "$u" "$home_dir/.ssh"
  echo "$SSH_KEYS" >> "$home_dir/.ssh/authorized_keys"
  chmod 600 "$home_dir/.ssh/authorized_keys"
  chown "$u:$u" "$home_dir/.ssh/authorized_keys"
done

# =====================================================================
# 4) SSH 하드닝: 비표준 포트, 비밀번호 인증 비활성화, root 로그인 금지
# =====================================================================
SSHD_CONFIG=/etc/ssh/sshd_config

# 일부 Ubuntu 22.04 이미지는 ssh.socket(소켓 활성화)이 기본 활성 상태이며,
# 이 경우 sshd가 ssh.socket이 바인딩한 22번 포트의 fd를 그대로 넘겨받기 때문에
# sshd_config의 Port 지시문이 무시된다. 보안그룹은 ${ssh_port}만 허용하므로
# 그대로 두면 SSH가 완전히 막힌다. ssh.socket을 끄고 ssh.service가 직접
# 포트를 바인딩하도록 강제한다.
if systemctl is-active --quiet ssh.socket; then
  systemctl disable --now ssh.socket
fi
systemctl enable ssh.service

sed -i -E 's/^#?Port .*/Port ${ssh_port}/' "$SSHD_CONFIG"
sed -i -E 's/^#?PasswordAuthentication .*/PasswordAuthentication no/' "$SSHD_CONFIG"
sed -i -E 's/^#?PermitRootLogin .*/PermitRootLogin no/' "$SSHD_CONFIG"
sed -i -E 's/^#?PubkeyAuthentication .*/PubkeyAuthentication yes/' "$SSHD_CONFIG"

systemctl restart ssh || systemctl restart sshd

# =====================================================================
# 5) fail2ban 설치 (SSH 무차별 대입 공격 방어)
# =====================================================================
apt-get install -y fail2ban

cat > /etc/fail2ban/jail.local <<EOF
[sshd]
enabled = true
port    = ${ssh_port}
EOF

systemctl enable --now fail2ban
