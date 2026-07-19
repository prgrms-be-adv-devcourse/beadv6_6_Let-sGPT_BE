output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.this.id
}

output "public_subnet_id" {
  description = "퍼블릭 서브넷 ID"
  value       = aws_subnet.public.id
}

output "security_group_id" {
  description = "EC2 보안그룹 ID"
  value       = aws_security_group.app.id
}

output "s3_vpc_endpoint_id" {
  description = "S3 Gateway VPC 엔드포인트 ID"
  value       = aws_vpc_endpoint.s3.id
}

output "iam_role_arn" {
  description = "EC2 인스턴스 프로파일에 연결된 IAM Role ARN"
  value       = aws_iam_role.ec2.arn
}

output "iam_instance_profile_name" {
  description = "EC2 인스턴스 프로파일 이름"
  value       = aws_iam_instance_profile.ec2.name
}

output "s3_bucket_name" {
  description = "애플리케이션 데이터 + Terraform state 공용 S3 버킷 이름"
  value       = aws_s3_bucket.this.id
}

output "instance_ids" {
  description = "EC2 인스턴스 ID (key = ec2_instances 맵의 키)"
  value       = { for k, v in aws_instance.app : k => v.id }
}

output "instance_public_ips" {
  description = "EC2 인스턴스별 Elastic IP (key = ec2_instances 맵의 키)"
  value       = { for k, v in aws_eip.app : k => v.public_ip }
}

output "instance_private_ips" {
  description = "EC2 인스턴스별 프라이빗 IP (k3s agent join용)"
  value       = { for k, v in aws_instance.app : k => v.private_ip }
}

output "k3s_server_private_ip_planned" {
  description = "다음 콜드 리빌드에서 server 노드에 고정될 private IP (라이브 노드와 다를 수 있음 — ignore_changes 봉인)"
  value       = var.k3s_server_private_ip
}

output "ssm_connect_commands" {
  description = "인스턴스별 SSM Session Manager 접속 명령어 (AWS CLI + session-manager-plugin 필요)"
  value = {
    for k, v in aws_instance.app :
    k => "aws ssm start-session --target ${v.id} --region ${var.aws_region}"
  }
}

# ---------------------------------------------------------------------
# 이미지 저장소 + k3s OIDC (k8s 매니페스트/부트스트랩 배선 값)
# ---------------------------------------------------------------------

output "images_staging_bucket_name" {
  description = "presigned PUT 업로드용 staging 이미지 버킷 이름 (configmap S3_IMAGES_STAGING_BUCKET)"
  value       = aws_s3_bucket.images_staging.id
}

output "images_final_bucket_name" {
  description = "실제 서비스 이미지(final) 버킷 이름 (configmap S3_IMAGES_FINAL_BUCKET)"
  value       = aws_s3_bucket.images_final.id
}

output "oidc_jwks_bucket_name" {
  description = "k3s OIDC issuer discovery/JWKS 미러 버킷 이름 (부트스트랩 시 두 JSON을 여기 업로드)"
  value       = aws_s3_bucket.oidc_jwks.id
}

output "k3s_oidc_issuer_url" {
  description = "k3s --service-account-issuer 값 및 OIDC provider url (§3-1 부트스트랩에서 사용)"
  value       = local.k3s_oidc_issuer_url
}

output "product_s3_role_arn" {
  description = "product Deployment env AWS_ROLE_ARN 에 설정할 Role ARN (staging/final RW)"
  value       = aws_iam_role.product_s3.arn
}

output "search_s3_role_arn" {
  description = "search Deployment env AWS_ROLE_ARN 에 설정할 Role ARN (final read-only)"
  value       = aws_iam_role.search_s3.arn
}
