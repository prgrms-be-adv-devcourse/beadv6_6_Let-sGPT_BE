# =====================================================================
# 이미지 저장소 버킷 토폴로지 (staging / final) + k3s OIDC issuer JWKS 미러 버킷
# =====================================================================
# 기존 s3.tf의 공용 버킷(앱 데이터 + tfstate)과는 **별개**다 — 손대지 않는다.
# JWKS 버킷만 공개-read를 허용하므로 tfstate/이미지와 물리적으로 격리한다.
#
# - staging : 브라우저가 presigned PUT으로 바이트를 올리는 임시 버킷.
#             product가 커밋 시 HeadObject로 검증 후 final로 CopyObject(승격)한다.
#             승격되지 않은 찌꺼기는 lifecycle 1일로 청소된다.
# - final   : 실제 서비스 이미지. presigned GET으로만 읽고, search는 GetObject로 읽는다.
# - jwks    : k3s ServiceAccount 토큰 issuer의 discovery/JWKS 공개 미러.
#             read 공개는 **설계의도**(공개키)이고, 진짜 위협은 write 변조다 →
#             public-write Deny + versioning on(변조 복구) + 쓰기는 terraform/CI만.
#
# staging/final은 private + BPA 4옵션 전부 차단을 유지한다.
# presigned URL은 "인증된 요청"이므로 BPA(공개 접근 차단)와 무관하게 동작한다.

# ---------------------------------------------------------------------
# images-staging
# ---------------------------------------------------------------------

resource "aws_s3_bucket" "images_staging" {
  bucket = var.images_staging_bucket_name

  tags = {
    Name = var.images_staging_bucket_name
  }
}

resource "aws_s3_bucket_versioning" "images_staging" {
  bucket = aws_s3_bucket.images_staging.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "images_staging" {
  bucket = aws_s3_bucket.images_staging.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_ownership_controls" "images_staging" {
  bucket = aws_s3_bucket.images_staging.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "images_staging" {
  bucket = aws_s3_bucket.images_staging.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "images_staging_bucket_policy" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.images_staging.arn,
      "${aws_s3_bucket.images_staging.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "images_staging" {
  bucket = aws_s3_bucket.images_staging.id
  policy = data.aws_iam_policy_document.images_staging_bucket_policy.json
}

# 브라우저가 presigned **PUT**으로 직접 올리므로 CORS가 필요하다.
# 업로드 방식은 presigned PUT으로 확정됐다(POST Policy 철회) → allowed_methods는 PUT 뿐이다.
# ETag를 노출해야 브라우저가 업로드 결과를 읽을 수 있다.
resource "aws_s3_bucket_cors_configuration" "images_staging" {
  bucket = aws_s3_bucket.images_staging.id

  cors_rule {
    allowed_methods = ["PUT"]
    allowed_origins = var.images_cors_allowed_origins
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# 찌꺼기 청소 전용 lifecycle — 승격(staging→final CopyObject)은 product가 커밋 시 즉시 수행한다.
# 여기서 지우는 건 "승격되지 못한" 객체(중도 이탈, 크기 게이트 위반분)뿐이다.
resource "aws_s3_bucket_lifecycle_configuration" "images_staging" {
  bucket = aws_s3_bucket.images_staging.id

  rule {
    id     = "expire-staging"
    status = "Enabled"

    filter {}

    expiration {
      days = 1
    }
  }

  rule {
    id     = "abort-mpu"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # versioning이 켜져 있으므로 현재 버전 만료만으론 객체가 남는다 → 비현행 버전도 만료시킨다.
  rule {
    id     = "expire-noncurrent"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.images_staging]
}

# ---------------------------------------------------------------------
# images-final
# ---------------------------------------------------------------------

resource "aws_s3_bucket" "images_final" {
  bucket = var.images_final_bucket_name

  tags = {
    Name = var.images_final_bucket_name
  }
}

resource "aws_s3_bucket_versioning" "images_final" {
  bucket = aws_s3_bucket.images_final.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "images_final" {
  bucket = aws_s3_bucket.images_final.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_ownership_controls" "images_final" {
  bucket = aws_s3_bucket.images_final.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "images_final" {
  bucket = aws_s3_bucket.images_final.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "images_final_bucket_policy" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.images_final.arn,
      "${aws_s3_bucket.images_final.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "images_final" {
  bucket = aws_s3_bucket.images_final.id
  policy = data.aws_iam_policy_document.images_final_bucket_policy.json
}

# final 버킷 CORS는 원칙적으로 불요 — 브라우저가 presigned GET을 `<img src>`로 쓰면
# CORS가 개입하지 않는다(단순 이미지 로드). FE가 fetch/canvas로 읽는다면 GET용
# cors_configuration을 추가해야 한다. (확인필요 — FE 렌더 방식)

resource "aws_s3_bucket_lifecycle_configuration" "images_final" {
  bucket = aws_s3_bucket.images_final.id

  rule {
    id     = "abort-mpu"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.images_final]
}

# ---------------------------------------------------------------------
# oidc-jwks (k3s issuer 공개 미러)
# ---------------------------------------------------------------------
# 이 버킷의 핵심 보안통제는 "이름 감추기"가 아니라 **write 잠금**이다:
#   - read 공개는 설계의도(JWKS = 공개키). 공개키로는 토큰 서명 위조가 불가능하다.
#   - 진짜 위협은 공격자가 JWKS를 덮어써 자기 공개키로 교체하는 것 → 위조 토큰이 STS 통과.
#   - 대응: anonymous write 명시 Deny + versioning on(변조 복구) + product/search Role엔
#     이 버킷 write 미부여(쓰기는 terraform/CI 자격증명만).
#
# 버킷명에 점(.)이 있으면 가상 호스트 스타일 URL이 S3 와일드카드 인증서
# (*.s3.<region>.amazonaws.com)와 매칭되지 않아 TLS 검증에 실패한다 →
# issuer URL로 쓰는 이 버킷은 **점 없는 이름**이어야 한다(var 검증으로 강제).

resource "aws_s3_bucket" "oidc_jwks" {
  bucket = var.oidc_jwks_bucket_name

  tags = {
    Name = var.oidc_jwks_bucket_name
  }
}

resource "aws_s3_bucket_versioning" "oidc_jwks" {
  bucket = aws_s3_bucket.oidc_jwks.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "oidc_jwks" {
  bucket = aws_s3_bucket.oidc_jwks.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_ownership_controls" "oidc_jwks" {
  bucket = aws_s3_bucket.oidc_jwks.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# 공개 정책을 붙여야 하므로 block_public_policy / restrict_public_buckets만 해제한다.
# ACL 경로는 어차피 BucketOwnerEnforced로 죽어 있으므로 acls 2개는 차단 유지.
resource "aws_s3_bucket_public_access_block" "oidc_jwks" {
  bucket = aws_s3_bucket.oidc_jwks.id

  block_public_acls       = true
  block_public_policy     = false
  ignore_public_acls      = true
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "oidc_jwks_bucket_policy" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.oidc_jwks.arn,
      "${aws_s3_bucket.oidc_jwks.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # STS가 issuer discovery를 무인증으로 읽어야 하므로 딱 2개 객체만 공개한다.
  # 버킷 전체(/*) 공개가 아니다 — 실수로 올라간 다른 객체는 공개되지 않는다.
  statement {
    sid    = "AllowAnonymousReadDiscoveryDocuments"
    effect = "Allow"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:GetObject"]

    resources = [
      "${aws_s3_bucket.oidc_jwks.arn}/.well-known/openid-configuration",
      "${aws_s3_bucket.oidc_jwks.arn}/openid/v1/jwks",
    ]
  }

  # write-lock: 익명 주체의 쓰기/삭제를 명시적으로 거부한다.
  # (익명 write를 허용하는 Allow가 지금은 없지만, 향후 정책 실수에 대한 방어선으로 명시한다.)
  # aws:PrincipalType == "Anonymous" 조건으로 익명만 겨냥 → terraform/CI 자격증명은 영향 없음.
  statement {
    sid    = "DenyAnonymousWrite"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = [
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
    ]

    resources = [
      aws_s3_bucket.oidc_jwks.arn,
      "${aws_s3_bucket.oidc_jwks.arn}/*",
    ]

    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalType"
      values   = ["Anonymous"]
    }
  }
}

# block_public_policy가 false로 내려간 뒤에 공개 정책이 붙어야 한다(순서 강제).
resource "aws_s3_bucket_policy" "oidc_jwks" {
  bucket = aws_s3_bucket.oidc_jwks.id
  policy = data.aws_iam_policy_document.oidc_jwks_bucket_policy.json

  depends_on = [aws_s3_bucket_public_access_block.oidc_jwks]
}
