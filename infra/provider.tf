terraform {
  required_version = ">= 1.10.0" # use_lockfile (S3 네이티브 락) 사용을 위해 1.10 이상 필요

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # ---------------------------------------------------------------------
  # S3 backend - 2단계 부트스트랩 필요 (자세한 절차는 README.md 참고)
  #
  # 1단계: 이 backend 블록을 주석 처리한 상태로 최초 apply를 실행한다.
  #         -> state는 로컬 terraform.tfstate에 저장되며, 이때
  #            aws_s3_bucket.this (state/앱 공용 버킷)가 생성된다.
  #
  # 2단계: 버킷 생성 후 아래 backend 블록의 주석을 해제하고
  #         bucket 값을 var.s3_bucket_name과 동일한 실제 버킷 이름으로 채운 뒤
  #         `terraform init -migrate-state` 를 실행해 로컬 state를 S3로 옮긴다.
  # ---------------------------------------------------------------------
  # backend "s3" {
  #   bucket       = "<S3_BUCKET_NAME>" # var.s3_bucket_name과 동일한 값으로 교체
  #   key          = "tfstate/dropcommerce-bootcamp/terraform.tfstate"
  #   region       = "ap-northeast-2"
  #   encrypt      = true
  #   use_lockfile = true # DynamoDB 락 대신 S3 네이티브 락 사용
  # }
}

provider "aws" {
  region = var.aws_region
}
