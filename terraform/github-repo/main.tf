# GitHub 리포 설정 루트 모듈 — terraform-plan.yml이 쓰는 리포 Variable을 코드로 관리.
#
# 목적: apply → output 확인 → 사람이 리포 Settings에서 AWS_TF_ROLE_ARN 복붙 하던
# 수동 루프를 닫는다. Role ARN이 바뀌면 apply 한 번으로 리포 변수까지 전파된다.
#
# 인증: 로컬 셸에서 GITHUB_TOKEN 환경변수로 PAT를 공급한다(코드·tfvars에 토큰 기재 금지).
# apply는 로컬 전용 — CI에는 토큰을 두지 않는다. 자세한 절차는 README.md 참고.
terraform {
  required_version = ">= 1.10.0"
  required_providers {
    github = {
      source  = "integrations/github"
      version = "~> 6.0"
    }
  }
  backend "s3" {
    bucket       = "team02-letsgpt-bucket"
    key          = "tfstate/letsGPT-openAt/github.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true # DynamoDB 락 대신 S3 네이티브 락 사용
  }
}

provider "github" {
  owner = "prgrms-be-adv-devcourse"
}

# 메인 state의 output에서 Role ARN을 읽어온다(운영자 로컬 자격은 이미 state 읽기 가능).
data "terraform_remote_state" "main" {
  backend = "s3"
  config = {
    bucket = "team02-letsgpt-bucket"
    key    = "tfstate/letsGPT-openAt/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

# 기존 수동 등록값을 코드로 흡수 — 최초 1회 `terraform import` 필요
# (import 없이 apply하면 이미 존재하는 변수와 충돌). 값은 평문 공개라 state 노출 무해.
resource "github_actions_variable" "tf_role_arn" {
  repository    = "beadv6_6_Let-sGPT_BE"
  variable_name = "AWS_TF_ROLE_ARN"
  value         = data.terraform_remote_state.main.outputs.github_terraform_plan_role_arn
}
