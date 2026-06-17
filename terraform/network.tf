# 단일 VPC + 단일 퍼블릭 서브넷.
# 멀티 AZ/프라이빗 서브넷/NAT Gateway 없음 - 네트워크 격리는 보안그룹(security.tf)으로 처리.

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.project_name}-vpc"
  }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = var.public_subnet_cidr
  availability_zone = var.availability_zone

  # 퍼블릭 IP는 인스턴스별 Elastic IP(compute.tf)로 부여하므로 자동 할당은 끔.
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.project_name}-public-subnet"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.project_name}-igw"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = {
    Name = "${var.project_name}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# S3 Gateway VPC 엔드포인트 (무료) - EC2 <-> S3 트래픽이 IGW를 거치지 않고
# AWS 내부망을 통해 흐르도록 한다. route_table_ids를 지정하면 Terraform이
# 해당 라우트 테이블에 S3 prefix list 경로를 자동으로 추가/관리한다.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id]

  tags = {
    Name = "${var.project_name}-s3-endpoint"
  }
}
