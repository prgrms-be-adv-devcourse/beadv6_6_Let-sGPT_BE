aws_region   = "ap-northeast-2"
project_name = "letsGpt-openAt"

# ---------------------------------------------------------------------
# 네트워크
# ---------------------------------------------------------------------
vpc_cidr           = "10.0.0.0/16"
public_subnet_cidr = "10.0.1.0/24"
availability_zone  = "ap-northeast-2a"

# ---------------------------------------------------------------------
# EC2
# 세미 프로젝트: t3.large 1대만 활성화.
# 파이널 확장 시 아래 주석을 해제하여 t3.medium 1대를 추가한다 (코드 수정 불필요).
# ---------------------------------------------------------------------
ec2_instances = {
  semi = {
    instance_type    = "t3.large"
    root_volume_size = 50
  }
  # final = {
  #   instance_type    = "t3.medium"
  #   root_volume_size = 50
  # }
}

deployer_user = "deployer"

# ---------------------------------------------------------------------
# S3
# ---------------------------------------------------------------------
s3_bucket_name = "team02-letsgpt-bucket"
s3_app_prefix  = "app/"
