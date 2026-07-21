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

# ---------------------------------------------------------------------
# 이미지 prefix 통합 (구 images-staging/final 버킷 → 이 버킷의 prefix)
#   images/staging/ : 브라우저 presigned PUT 임시 업로드 (lifecycle 1일 청소)
#   images/final/   : 커밋 검증 통과 후 CopyObject 승격된 확정 이미지
# ---------------------------------------------------------------------

# 브라우저가 presigned PUT으로 직접 올리므로 CORS가 필요하다(PUT 한정, POST Policy 철회 확정).
# CORS는 버킷 단위 설정이지만 인가 없는 접근을 허용하는 게 아니므로 tfstate 동거에 무해하다.
resource "aws_s3_bucket_cors_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  cors_rule {
    allowed_methods = ["PUT"]
    allowed_origins = var.images_cors_allowed_origins
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# lifecycle의 만료 규칙은 반드시 images/staging/ prefix에만 건다 —
# 필터 오설정은 tfstate 삭제 사고로 직결되므로 apply 후
# get-bucket-lifecycle-configuration으로 필터 적용 범위를 확인한다.
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  # 승격되지 못한 staging 찌꺼기 청소(중도 이탈·크기 게이트 위반분)
  rule {
    id     = "expire-images-staging"
    status = "Enabled"

    filter {
      prefix = "images/staging/"
    }

    expiration {
      days = 1
    }
  }

  # versioning이 켜져 있어 현행 만료만으론 비현행 버전이 남는다 → staging 한정 함께 만료
  rule {
    id     = "expire-images-staging-noncurrent"
    status = "Enabled"

    filter {
      prefix = "images/staging/"
    }

    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  # 미완결 멀티파트 업로드 청소는 버킷 전체(객체 삭제가 아니라 업로드 세션 정리라 tfstate 무해)
  rule {
    id     = "abort-mpu"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}
