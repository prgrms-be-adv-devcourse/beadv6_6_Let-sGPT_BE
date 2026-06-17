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

resource "aws_instance" "app" {
  for_each = var.ec2_instances

  ami           = data.aws_ami.ubuntu.id
  instance_type = each.value.instance_type

  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

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
    deployer_user = var.deployer_user
  })

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
