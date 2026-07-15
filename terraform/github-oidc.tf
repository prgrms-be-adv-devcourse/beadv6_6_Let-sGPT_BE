# =====================================================================
# GitHub Actions OIDC — terraform "plan" CI 전용 (키리스, 정적 AWS 키 저장 안 함)
# =====================================================================
# deploy.yml은 여전히 EC2 위 self-hosted runner + 인스턴스 프로파일을 쓰므로 이와 무관.
# 여기 Role은 terraform-plan.yml(GitHub-hosted)이 OIDC로 잠깐 assume해 쓰는 것이고,
# 권한은 **읽기 전용(plan)** 만 부여한다 — apply는 CI에서 하지 않는다(넓은 쓰기 권한을
# CI에 상주시키지 않기 위함). 활성 절차는 terraform-plan.yml 상단 주석 참고.

variable "github_repository" {
  description = "OIDC 신뢰 대상 GitHub 리포 (owner/repo)"
  type        = string
  default     = "prgrms-be-adv-devcourse/beadv6_6_Let-sGPT_BE"
}

# GitHub Actions OIDC provider (계정당 1개). 이전에 deploy용으로 있다가 제거됐으니
# 재생성. 이미 존재하면 `terraform import`로 흡수할 것.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # GitHub OIDC 루트 CA 지문(문서화된 값). AWS는 자체 신뢰 CA 저장소로도 검증하지만
  # provider 리소스엔 값이 필요하다.
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fca",
  ]

  tags = { Name = "${var.project_name}-github-oidc" }
}

# 신뢰 정책: 이 리포의 PR 및 main 컨텍스트에서만 assume 허용(least privilege)
data "aws_iam_policy_document" "tf_ci_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repository}:pull_request",
        "repo:${var.github_repository}:ref:refs/heads/main",
      ]
    }
  }
}

resource "aws_iam_role" "tf_ci_plan" {
  name               = "${var.project_name}-gha-terraform-plan"
  assume_role_policy = data.aws_iam_policy_document.tf_ci_assume.json

  tags = { Name = "${var.project_name}-gha-terraform-plan" }
}

# plan에 필요한 권한 = (1) 전 리소스 읽기(describe) → AWS 관리형 ReadOnlyAccess
resource "aws_iam_role_policy_attachment" "tf_ci_readonly" {
  role       = aws_iam_role.tf_ci_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# (2) S3 state 읽기 + use_lockfile 락(Put/Delete) — tfstate/ prefix 한정. ReadOnlyAccess엔
# PutObject가 없어 별도 부여(락 파일 쓰기용).
data "aws_iam_policy_document" "tf_ci_state" {
  statement {
    sid       = "StateBucketList"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.this.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["tfstate/*"]
    }
  }

  statement {
    sid       = "StateObjectRW"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.this.arn}/tfstate/*"]
  }
}

resource "aws_iam_policy" "tf_ci_state" {
  name   = "${var.project_name}-gha-tf-state"
  policy = data.aws_iam_policy_document.tf_ci_state.json
}

resource "aws_iam_role_policy_attachment" "tf_ci_state" {
  role       = aws_iam_role.tf_ci_plan.name
  policy_arn = aws_iam_policy.tf_ci_state.arn
}

# terraform-plan.yml의 리포 변수 AWS_TF_ROLE_ARN 에 넣을 값
output "github_terraform_plan_role_arn" {
  description = "terraform-plan.yml 활성화 시 리포 Variable AWS_TF_ROLE_ARN 에 설정할 Role ARN"
  value       = aws_iam_role.tf_ci_plan.arn
}
