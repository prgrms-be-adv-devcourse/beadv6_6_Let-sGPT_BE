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

output "ssm_connect_commands" {
  description = "인스턴스별 SSM Session Manager 접속 명령어 (AWS CLI + session-manager-plugin 필요)"
  value = {
    for k, v in aws_instance.app :
    k => "aws ssm start-session --target ${v.id} --region ${var.aws_region}"
  }
}
