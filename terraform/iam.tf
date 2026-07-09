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
      # app/ = 유저 데이터, ops/ = 운영 산출물(k3s 매니페스트 등). 둘 다 least-privilege 유지.
      values = ["${var.s3_app_prefix}*", "${var.s3_ops_prefix}*"]
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
    resources = [
      "${aws_s3_bucket.this.arn}/${var.s3_app_prefix}*",
      "${aws_s3_bucket.this.arn}/${var.s3_ops_prefix}*",
    ]
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

# GitHub Actions OIDC(provider/role/policy)는 deploy.yml이 self-hosted runner 방식으로
# 전환되면서 더 이상 GitHub Actions가 직접 AWS API를 호출하지 않게 되어 제거함
# (plan.md §O). runner는 이미 EC2 위에서 실행되므로 AWS 인증 자체가 불필요.
