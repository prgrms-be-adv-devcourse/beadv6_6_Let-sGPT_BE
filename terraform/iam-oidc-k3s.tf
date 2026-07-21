# =====================================================================
# k3s ServiceAccount OIDC — 파드가 정적 AWS 키 없이 S3에 접근하기 위한 자격증명
# =====================================================================
# github-oidc.tf(GitHub Actions terraform-plan용 OIDC)와 나란히 놓지만 **리소스는 별개**다.
#
# 흐름: k3s가 발급한 ServiceAccount projected 토큰(iss = 이 issuer)을 파드가
#   sts:AssumeRoleWithWebIdentity로 교환 → 임시 자격증명으로 S3 접근.
#   issuer의 discovery/JWKS는 프론트 웹파드(https://openat.duckdns.org, 인그레스 host와
#   단일 소스)가 ConfigMap 콘텐츠로 서빙하고, STS가 그 공개 URL로 토큰 서명을 검증한다.
#
# 이 파일은 4계층 배선(플랜 §7-4) 중 ① terraform 계층이다. ②k3s issuer 재구성,
# ③k8s Deployment(SA ref + projected 볼륨 + AWS env), ④app configmap이 모두
# 맞물려야 실제로 자격증명이 잡힌다.

locals {
  # issuer = 프론트 웹파드(인그레스 host)가 서빙하는 OIDC discovery/JWKS 엔드포인트.
  # 프론트 인그레스 host와 단일 소스(var.oidc_issuer_host)로 묶여 있으며, 이 host 아래
  # /.well-known/openid-configuration + JWKS를 담은 ConfigMap 콘텐츠가 서빙된다.
  # k3s config.yaml의 service-account-issuer 값과 **정확히 일치**해야 한다.
  k3s_oidc_issuer_url = "https://${var.oidc_issuer_host}"
  k3s_oidc_host       = var.oidc_issuer_host
}

# ---------------------------------------------------------------------
# OIDC provider
# ---------------------------------------------------------------------
# issuer의 TLS 체인 최상위 인증서 지문을 동적으로 조회한다. issuer가 프론트 웹파드
# (Let's Encrypt 발급 인증서)라 이제 Let's Encrypt 체인(ISRG Root X1) 지문이 잡혀야 한다.
#
# 확인필요: tls_certificate가 프론트 인그레스(openat.duckdns.org) 리프 인증서 체인의
# 최상위(ISRG Root X1)를 반환하는지, 그리고 이 data source가 실제 issuer 호스트에
# 도달 가능한지 apply 시 검증할 것.
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
    resources = ["${aws_s3_bucket.this.arn}/images/staging/*"]
  }

  statement {
    sid    = "FinalObjectRW"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
    ]
    resources = ["${aws_s3_bucket.this.arn}/images/final/*"]
  }

  # tfstate 동거 이중 방어 — prefix allow 실수에도 tfstate 도달 차단.
  statement {
    sid       = "DenyTfstateAccess"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = ["${aws_s3_bucket.this.arn}/tfstate/*"]
  }
}

resource "aws_iam_role_policy" "product_s3" {
  name   = "${var.project_name}-product-s3-access"
  role   = aws_iam_role.product_s3.id
  policy = data.aws_iam_policy_document.product_s3.json
}

# ---------------------------------------------------------------------
# search — S3 Role 미부여
# ---------------------------------------------------------------------
# search는 product 이미지 API(HTTP) 경유로 이미지를 받고 S3에 직접 접근하지 않는다.
# 따라서 별도 IAM Role/SA OIDC 배선을 두지 않는다.
