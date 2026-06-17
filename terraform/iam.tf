# EC2 인스턴스 프로파일(IAM Role) - 서버 안에 액세스 키를 저장하지 않기 위함.
# - S3: var.s3_app_prefix 하위 객체에 대한 읽기/쓰기/목록 조회
# - SSM: AmazonSSMManagedInstanceCore (Session Manager 접속 + Run Command)

# =====================================================================
# EC2 Role
# =====================================================================

data "aws_iam_policy_document" "ec2_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${var.project_name}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role.json

  tags = {
    Name = "${var.project_name}-ec2-role"
  }
}

# S3 접근 정책
data "aws_iam_policy_document" "ec2_s3_access" {
  statement {
    sid       = "ListBucketAppPrefix"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.this.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.s3_app_prefix}*"]
    }
  }

  statement {
    sid    = "ReadWriteAppPrefixObjects"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]
    resources = ["${aws_s3_bucket.this.arn}/${var.s3_app_prefix}*"]
  }
}

resource "aws_iam_policy" "ec2_s3_access" {
  name   = "${var.project_name}-ec2-s3-access"
  policy = data.aws_iam_policy_document.ec2_s3_access.json
}

resource "aws_iam_role_policy_attachment" "ec2_s3_access" {
  role       = aws_iam_role.ec2.name
  policy_arn = aws_iam_policy.ec2_s3_access.arn
}

# SSM Session Manager + Run Command 필수 권한 (AWS 관리형 정책)
resource "aws_iam_role_policy_attachment" "ec2_ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project_name}-ec2-profile"
  role = aws_iam_role.ec2.name
}

# =====================================================================
# GitHub Actions OIDC - 키 없이 GitHub Actions → AWS 인증
# =====================================================================

resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"

  # GitHub Actions OIDC가 sts:AssumeRoleWithWebIdentity를 호출할 때 사용하는 audience
  client_id_list = ["sts.amazonaws.com"]

  # GitHub OIDC 인증서 thumbprint (AWS는 신뢰된 CA를 자동 검증하지만 Terraform 필드 필수)
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]

  tags = {
    Name = "${var.project_name}-github-oidc"
  }
}

data "aws_iam_policy_document" "github_actions_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    # GitHub OIDC audience 검증
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # 특정 레포지토리에서 발행된 토큰만 허용 (브랜치/태그 무관)
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:*"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = "${var.project_name}-github-actions-role"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume_role.json

  tags = {
    Name = "${var.project_name}-github-actions-role"
  }
}

# GitHub Actions 배포 권한: SSM Run Command로 EC2에 배포 명령 전달
data "aws_iam_policy_document" "github_actions_deploy" {
  # SSM Send Command - 배포 스크립트 실행
  statement {
    sid    = "SSMSendCommand"
    effect = "Allow"
    actions = [
      "ssm:SendCommand",
      "ssm:GetCommandInvocation",
      "ssm:ListCommandInvocations",
    ]
    resources = ["*"]
  }

  # EC2 인스턴스 ID 조회 (배포 타겟 확인)
  statement {
    sid    = "EC2Describe"
    effect = "Allow"
    actions = [
      "ec2:DescribeInstances",
      "ec2:DescribeInstanceStatus",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "github_actions_deploy" {
  name   = "${var.project_name}-github-actions-deploy"
  policy = data.aws_iam_policy_document.github_actions_deploy.json
}

resource "aws_iam_role_policy_attachment" "github_actions_deploy" {
  role       = aws_iam_role.github_actions.name
  policy_arn = aws_iam_policy.github_actions_deploy.arn
}
