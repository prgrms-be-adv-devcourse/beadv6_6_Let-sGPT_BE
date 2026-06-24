# 단일 S3 버킷을 애플리케이션 데이터 + Terraform state(tfstate/ prefix)에 공용으로 사용한다.
# ACL은 사용하지 않는다 (object_ownership = BucketOwnerEnforced로 ACL 비활성화).
# 접근 제어는 버킷 정책 + EC2 인스턴스 프로파일(IAM Role, iam.tf)로만 수행한다.

resource "aws_s3_bucket" "this" {
  bucket = var.s3_bucket_name

  tags = {
    Name = var.s3_bucket_name
  }
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id

  versioning_configuration {
    status = "Enabled"
  }
}

# 서버사이드 암호화(SSE-S3) 기본 적용
resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ACL 비활성화 - 버킷 소유자가 모든 객체의 소유권을 가짐
resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Public Access Block 4개 옵션 전부 차단
resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 버킷 정책: HTTPS가 아닌 요청을 거부 (SSE-S3 암호화 요구사항과 함께 전송 구간도 보호)
data "aws_iam_policy_document" "bucket_policy" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.this.arn,
      "${aws_s3_bucket.this.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.bucket_policy.json
}
