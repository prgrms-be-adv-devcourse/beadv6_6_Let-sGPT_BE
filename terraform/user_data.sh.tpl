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
# 3) SSM Agent 활성화 확인
#    Ubuntu 22.04 AMI에는 snap으로 사전 설치되어 있다.
#    IAM 인스턴스 프로파일(AmazonSSMManagedInstanceCore)이 연결되면
#    에이전트가 자동으로 SSM에 등록된다.
# =====================================================================
systemctl enable --now snap.amazon-ssm-agent.amazon-ssm-agent || true
