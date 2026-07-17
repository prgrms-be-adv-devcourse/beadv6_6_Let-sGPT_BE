variable "aws_region" {
  description = "리소스를 생성할 AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "리소스 Name 태그 및 식별자 접두어로 사용할 프로젝트 이름"
  type        = string
  default     = "letsGpt-openAt"
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

variable "deployer_user" {
  description = "배포 전용 계정 이름 (docker 그룹에만 속하며 sudo 권한 없음; SSM Run Command 실행 시 이 계정으로 도커 명령 실행)"
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

variable "s3_ops_prefix" {
  description = <<-EOT
    EC2 인스턴스 프로파일이 읽기/쓰기 가능한 "운영 산출물" 전용 S3 prefix
    (k3s 매니페스트 등 배포 산출물). app_prefix(유저 데이터)와 물리적으로 분리한다.
    '/'로 끝나는 순수 prefix만 지정한다 (예: "ops/"). 와일드카드는 IAM에서 자동 부여.
  EOT
  type        = string
  default     = "ops/"
}

# ---------------------------------------------------------------------
# 이미지 저장소 (staging / final) + k3s OIDC issuer JWKS 미러 버킷
# ---------------------------------------------------------------------

variable "images_staging_bucket_name" {
  description = "브라우저 presigned PUT 업로드용 임시(staging) 이미지 버킷 이름 (전역적으로 유일해야 함)"
  type        = string
  default     = "team02-letsgpt-images-staging"
}

variable "images_final_bucket_name" {
  description = "승격된 실제 서비스 이미지(final) 버킷 이름 (전역적으로 유일해야 함)"
  type        = string
  default     = "team02-letsgpt-images-final"
}

variable "oidc_jwks_bucket_name" {
  description = <<-EOT
    k3s ServiceAccount OIDC issuer의 discovery/JWKS 공개 미러 버킷 이름 (전역적으로 유일해야 함).
    issuer URL(https://<버킷>.s3.<region>.amazonaws.com)로 쓰이므로 **점(.)이 없어야 한다** —
    점이 있으면 가상 호스트 스타일 URL이 S3 와일드카드 인증서와 매칭되지 않아 TLS 검증에 실패한다.
  EOT
  type        = string
  default     = "team02-letsgpt-oidc-jwks"

  validation {
    condition     = !can(regex("\\.", var.oidc_jwks_bucket_name))
    error_message = "oidc_jwks_bucket_name 에는 점(.)을 포함할 수 없다 (S3 가상 호스트 스타일 TLS 인증서 매칭 제약)."
  }
}

variable "images_cors_allowed_origins" {
  description = <<-EOT
    staging 버킷 CORS가 presigned PUT을 허용할 FE origin 목록.
    확인필요: 실제 FE 배포 origin으로 교체할 것(예: https://openat.duckdns.org).
  EOT
  type        = list(string)
  default     = ["https://openat.duckdns.org"]
}
