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
    k3s_role         = "server"
    k3s_node_label   = "tier=hotpath"
  }
  final = {
    instance_type    = "t3.medium"
    root_volume_size = 50
    k3s_role         = "agent"
    k3s_node_label   = "tier=observability"
    k3s_node_taint   = "dedicated=observability:NoSchedule"
  }
}

deployer_user = "deployer"

# ---------------------------------------------------------------------
# S3
# ---------------------------------------------------------------------
s3_bucket_name = "team02-letsgpt-bucket"
s3_app_prefix  = "app/"

# ---------------------------------------------------------------------
# 이미지 저장소 + k3s OIDC issuer JWKS 미러 버킷
# oidc_jwks_bucket_name 은 issuer URL로 쓰이므로 점(.) 사용 불가(variables.tf에서 강제).
# 이 이름은 k3s --service-account-issuer 와 IAM OIDC provider url 양쪽에 박히므로
# 바꾸려면 노드 재구성 + provider 재생성이 함께 필요하다.
# images_cors_allowed_origins 는 31-ingress.yaml 의 host 와 일치해야 한다.
# ---------------------------------------------------------------------
images_staging_bucket_name  = "team02-letsgpt-images-staging"
images_final_bucket_name    = "team02-letsgpt-images-final"
oidc_jwks_bucket_name       = "team02-letsgpt-oidc-jwks"
images_cors_allowed_origins = ["https://openat.duckdns.org"]
