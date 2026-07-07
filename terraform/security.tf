# 단일 보안그룹으로 네트워크 격리를 처리한다 (NACL은 사용하지 않음, 기본값 유지).
#
# - 외부 공개 인바운드: 80(HTTP), 443(HTTPS)만 허용
# - SSH 인바운드 없음: 서버 접속은 SSM Session Manager를 사용한다 (인바운드 포트 불필요)
# - 그 외 모든 포트(PostgreSQL, Kafka 등 내부 서비스)는 외부 인바운드 차단.
#   서비스 간 통신은 인스턴스 내부 도커 네트워크에서만 이루어진다.

resource "aws_security_group" "app" {
  name        = "${var.project_name}-app-sg"
  description = "Public 80/443 only; SSH removed, access via SSM Session Manager"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound (including SSM Agent to AWS endpoints)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-app-sg"
  }
}
