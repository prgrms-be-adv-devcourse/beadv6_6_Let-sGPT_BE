# =====================================================================
# k3s ServiceAccount OIDC — 파드가 정적 AWS 키 없이 S3에 접근하기 위한 자격증명
# =====================================================================
# github-oidc.tf(GitHub Actions terraform-plan용 OIDC)와 나란히 놓지만 **리소스는 별개**다.
#
# 흐름: k3s가 발급한 ServiceAccount projected 토큰(iss = 이 issuer)을 파드가
#   sts:AssumeRoleWithWebIdentity로 교환 → 임시 자격증명으로 S3 접근.
#   issuer의 discovery/JWKS는 oidc-jwks 버킷(images.tf)에 미러링돼 있고, STS가
#   그 공개 URL로 토큰 서명을 검증한다.
#
# 이 파일은 4계층 배선(플랜 §7-4) 중 ① terraform 계층이다. ②k3s issuer 재구성,
# ③k8s Deployment(SA ref + projected 볼륨 + AWS env), ④app configmap이 모두
# 맞물려야 실제로 자격증명이 잡힌다.

locals {
  # issuer = oidc-jwks 버킷의 가상 호스트 스타일 엔드포인트(점 없는 버킷명이라 TLS 와일드카드 매칭됨).
  # k3s의 --kube-apiserver-arg=service-account-issuer 값과 **정확히 일치**해야 한다(§3-1).
  k3s_oidc_issuer_url = "https://${var.oidc_jwks_bucket_name}.s3.${var.aws_region}.amazonaws.com"
  k3s_oidc_host       = "${var.oidc_jwks_bucket_name}.s3.${var.aws_region}.amazonaws.com"
}

# ---------------------------------------------------------------------
# OIDC provider
# ---------------------------------------------------------------------
# issuer의 TLS 체인 최상위 인증서 지문을 동적으로 조회한다. issuer가 S3(=Amazon 신뢰 CA)라
# AWS는 자체 신뢰 저장소로도 검증하지만, provider 리소스엔 thumbprint 값이 필요하다.
#
# 확인필요: tls_certificate가 S3 엔드포인트 리프 인증서 체인의 최상위를 반환하는지,
# 그리고 issuer 재구성(§3-1) 후 이 data source가 실제 issuer 호스트에 도달 가능한지
# apply 시 검증할 것. 값이 부적절하면 S3 TLS 루트 CA(Amazon Root CA 1) 지문으로 고정한다.
data "tls_certificate" "k3s_oidc" {
  url = local.k3s_oidc_issuer_url
}

resource "aws_iam_openid_connect_provider" "k3s" {
  url             = local.k3s_oidc_issuer_url
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.k3s_oidc.certificates[0].sha1_fingerprint]

  tags = { Name = "${var.project_name}-k3s-oidc" }
}

# ---------------------------------------------------------------------
# product-s3 Role — staging RW + final RW (presigned 서명 + 승격 CopyObject)
# ---------------------------------------------------------------------
# 신뢰정책: aud = sts.amazonaws.com, sub = 정확히 openat/product-sa 하나만.
# sub 스코프로 "이 ServiceAccount 토큰만" 이 Role을 assume할 수 있게 좁힌다.
data "aws_iam_policy_document" "product_s3_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.k3s.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.k3s_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.k3s_oidc_host}:sub"
      values   = ["system:serviceaccount:openat:product-sa"]
    }
  }
}

resource "aws_iam_role" "product_s3" {
  name               = "${var.project_name}-product-s3"
  assume_role_policy = data.aws_iam_policy_document.product_s3_assume.json

  tags = { Name = "${var.project_name}-product-s3" }
}

# 최소권한(§3-4): ListBucket 절대 미부여. 리소스 ARN은 버킷 객체(/*)로 스코프.
# - staging: PutObject(업로드 서명), GetObject(Copy source), DeleteObject(명시 청소·선택)
# - final  : PutObject(Copy dest/승격), GetObject(다운로드 서명)
# - CopyObject = source GetObject(staging) + dest PutObject(final) → 이 집합으로 충족.
# - JWKS 버킷 write는 부여하지 않는다(§2 write-lock).
data "aws_iam_policy_document" "product_s3" {
  statement {
    sid    = "StagingObjectRW"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:DeleteObject",
    ]
    resources = ["${aws_s3_bucket.images_staging.arn}/*"]
  }

  statement {
    sid    = "FinalObjectRW"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
    ]
    resources = ["${aws_s3_bucket.images_final.arn}/*"]
  }
}

resource "aws_iam_role_policy" "product_s3" {
  name   = "${var.project_name}-product-s3-access"
  role   = aws_iam_role.product_s3.id
  policy = data.aws_iam_policy_document.product_s3.json
}

# ---------------------------------------------------------------------
# search-s3 Role — final 읽기 전용(AI 이미지 분석용 GetObject만)
# ---------------------------------------------------------------------
data "aws_iam_policy_document" "search_s3_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.k3s.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.k3s_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.k3s_oidc_host}:sub"
      values   = ["system:serviceaccount:openat:search-sa"]
    }
  }
}

resource "aws_iam_role" "search_s3" {
  name               = "${var.project_name}-search-s3"
  assume_role_policy = data.aws_iam_policy_document.search_s3_assume.json

  tags = { Name = "${var.project_name}-search-s3" }
}

# 최소권한(§3-4): final 버킷 GetObject만. ListBucket 미부여.
# 없는 key GetObject는 403(404 아님) → search가 "없음/스킵"으로 처리(§6).
data "aws_iam_policy_document" "search_s3" {
  statement {
    sid       = "FinalObjectReadOnly"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.images_final.arn}/*"]
  }
}

resource "aws_iam_role_policy" "search_s3" {
  name   = "${var.project_name}-search-s3-access"
  role   = aws_iam_role.search_s3.id
  policy = data.aws_iam_policy_document.search_s3.json
}
