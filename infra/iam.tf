# EC2 인스턴스 프로파일(IAM Role) - 서버 안에 액세스 키를 저장하지 않기 위함.
# 권한은 S3 버킷의 특정 prefix(var.s3_app_prefix)에 대한 읽기/쓰기/목록 조회로 최소화한다.

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

data "aws_iam_policy_document" "ec2_s3_access" {
  # 버킷 내 var.s3_app_prefix 하위 객체만 목록 조회 가능
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

  # var.s3_app_prefix 하위 객체에 대한 읽기/쓰기/삭제 (presigned URL 업로드 포함)
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

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project_name}-ec2-profile"
  role = aws_iam_role.ec2.name
}
