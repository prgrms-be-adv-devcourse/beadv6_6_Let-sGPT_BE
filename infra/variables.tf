variable "aws_region" {
  description = "리소스를 생성할 AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "리소스 Name 태그 및 식별자 접두어로 사용할 프로젝트 이름"
  type        = string
  default     = "dropcommerce-bootcamp"
}

# ---------------------------------------------------------------------
# 네트워크
# ---------------------------------------------------------------------

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "단일 퍼블릭 서브넷 CIDR 블록"
  type        = string
  default     = "10.0.1.0/24"
}

variable "availability_zone" {
  description = "퍼블릭 서브넷을 배치할 가용영역"
  type        = string
  default     = "ap-northeast-2a"
}

# ---------------------------------------------------------------------
# EC2
# ---------------------------------------------------------------------

variable "ec2_instances" {
  description = <<-EOT
    생성할 EC2 인스턴스 정의 맵 (key = 인스턴스 식별자, value = 스펙).
    - 세미 프로젝트: t3.large 1대만 정의 (terraform.tfvars 참고).
    - 파이널 확장: 이 맵에 t3.medium 항목을 추가하면 코드 수정 없이
      인스턴스 + Elastic IP가 함께 추가 생성됨.
    root_volume_size는 gp3 기준 최대 50(GB)을 넘지 않도록 한다.
  EOT
  type = map(object({
    instance_type    = string
    root_volume_size = number
  }))
}

# ---------------------------------------------------------------------
# SSH
# ---------------------------------------------------------------------

variable "ssh_port" {
  description = "sshd가 리스닝할 비표준 포트 (보안그룹 SSH 인바운드 규칙도 이 포트를 사용)"
  type        = number
  default     = 49222
}

variable "ssh_allowed_cidrs" {
  description = <<-EOT
    SSH(ssh_port) 인바운드를 허용할 CIDR 목록.
    - 세미: 팀원 접속을 위해 넓게 허용할 수 있음 (예: ["0.0.0.0/0"]).
    - 파이널: 본인/팀 공인 IP로 좁힐 것 (예: ["1.2.3.4/32"]).
    빈 리스트([])이면 SSH 인바운드 규칙 자체가 생성되지 않는다.
  EOT
  type        = list(string)
  default     = []
}

variable "ssh_public_keys" {
  description = <<-EOT
    EC2 인스턴스에 주입할 SSH 공개키 목록.
    user_data에서 ubuntu 계정과 deployer 계정의 authorized_keys에 등록된다.
    공개키는 git에 커밋되어도 안전하므로 변수로 관리한다 (개인키는 절대 포함하지 말 것).
  EOT
  type        = list(string)
  default     = []
}

variable "deployer_user" {
  description = "배포 전용 계정 이름 (docker 그룹에만 속하며 sudo 권한 없음)"
  type        = string
  default     = "deployer"
}

# ---------------------------------------------------------------------
# S3
# ---------------------------------------------------------------------

variable "s3_bucket_name" {
  description = "애플리케이션 데이터 + Terraform state(tfstate/ prefix)를 함께 저장할 S3 버킷 이름 (전역적으로 유일해야 함)"
  type        = string
}

variable "s3_app_prefix" {
  description = <<-EOT
    EC2 인스턴스 프로파일(IAM Role)이 읽기/쓰기 가능한 S3 객체 prefix (예: presigned URL 업로드 대상).
    '/'로 끝나는 순수 prefix만 지정한다 (예: "app/"). 와일드카드('*')는 IAM 정책에서
    자동으로 붙으므로 여기에 포함하지 않는다.
  EOT
  type        = string
  default     = "app/"
}
