# EC2 인스턴스 + Elastic IP.
#
# 인스턴스 정의는 var.ec2_instances (map)로 관리한다.
# 세미 프로젝트는 terraform.tfvars에 t3.large 1개 항목만 정의되어 있어
# 이 키만 apply된다. 파이널에 t3.medium을 추가할 때는 terraform.tfvars의
# ec2_instances 맵에 항목을 하나 더 추가하면 되며, 이 .tf 파일은 수정할 필요 없다.

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd*/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# k3s 조인 사전공유 토큰. tfstate(S3, 암호화·네이티브 락)에 평문 저장됨 — 인지된 트레이드오프.
# server/agent 양쪽 user_data에 동일 값으로 주입해 서버 node-token 대기 없이 조인한다.
resource "random_password" "k3s_token" {
  length  = 48
  special = false
}

resource "aws_instance" "app" {
  for_each = var.ec2_instances

  ami           = data.aws_ami.ubuntu.id
  instance_type = each.value.instance_type

  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  # k3s server만 정적 IP — agent 템플릿이 조인 주소로 참조(자기참조 순환 회피).
  # 라이브 노드에는 ignore_changes로 미적용, 콜드 리빌드의 생성 시점에만 효력.
  private_ip = each.value.k3s_role == "server" ? var.k3s_server_private_ip : null

  # 부팅 직후 user_data(apt-get, docker pull 등)가 인터넷에 접근하려면 퍼블릭 IP가
  # 필요하다. aws_eip 연결은 인스턴스 생성 이후의 별도 API 호출이라 그 사이에
  # 퍼블릭 IP가 없는 구간이 생기면 부트스트랩이 실패할 수 있다. true로 두면
  # 부팅 즉시 임시 퍼블릭 IP가 할당되고, 이후 Elastic IP 연결 시 자동으로 교체된다.
  associate_public_ip_address = true

  # IMDSv2 강제: 토큰 없는 IMDS 요청 차단 (SSRF 방어)
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = each.value.root_volume_size
  }

  user_data = templatefile("${path.module}/user_data.sh.tpl", {
    deployer_user   = var.deployer_user
    k3s_role        = each.value.k3s_role
    k3s_node_label  = each.value.k3s_node_label
    k3s_node_taint  = each.value.k3s_node_taint
    k3s_token       = random_password.k3s_token.result
    k3s_server_ip   = var.k3s_server_private_ip
    k3s_oidc_issuer = local.k3s_oidc_issuer_url # iam-oidc-k3s.tf의 기존 local — 변수만으로 계산되어 순환 없음
  })

  # most_recent AMI 조회 결과가 갱신될 때마다 기존 인스턴스가 교체(destroy+create)되는
  # 것을 차단 — 신규 인스턴스는 생성 시점의 최신 AMI를 쓰고, 기존 인스턴스는 유지된다.
  # (2026-07-08 final 노드 추가 시 semi가 신형 AMI로 강제 교체되려던 것을 발견)
  # AMI 함정과 동형의 방어를 user_data/private_ip에도 적용(이번 축의 최우선 안전장치).
  # user_data: 변경 시 기본이 인스턴스 교체(destroy+create) — 라이브 노드 파괴 금지.
  # private_ip: 정적 IP 부여도 교체 유발 — 콜드 리빌드 생성 시점에만 반영되게 봉인.
  lifecycle {
    ignore_changes = [ami, user_data, private_ip]
  }

  tags = {
    Name    = "${var.project_name}-${each.key}"
    project = var.project_name
  }
}

# 인스턴스당 1개의 Elastic IP
resource "aws_eip" "app" {
  for_each = var.ec2_instances

  instance = aws_instance.app[each.key].id
  domain   = "vpc"

  depends_on = [aws_internet_gateway.this]

  tags = {
    Name = "${var.project_name}-${each.key}-eip"
  }
}
