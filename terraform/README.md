# openAt AWS 인프라 (Terraform)

> 2026-07-27 `dev` 구현 기준. Terraform은 AWS 기반 2노드 k3s 클러스터와 S3·IAM·네트워크를
> 관리한다. 애플리케이션 배포 자체는 `main` CI 이후 `deploy.yml`과 ArgoCD가 담당한다.

## 현재 토폴로지

| 영역 | 현재 구성 |
|---|---|
| 리전·네트워크 | `ap-northeast-2`, VPC 1개, 퍼블릭 서브넷 1개, IGW, S3 Gateway VPC Endpoint |
| k3s server | `semi`: `t3.large`, gp3 50GB, label `tier=hotpath`, 계획 고정 사설 IP `10.0.1.10` |
| k3s agent | `final`: `t3.medium`, gp3 50GB, label `tier=observability`, taint `dedicated=observability:NoSchedule` |
| 외부 주소 | EC2별 Elastic IP 1개 |
| 접근 | SSH 없이 SSM Session Manager |
| 공개 인바운드 | HTTP 80, HTTPS 443 |
| 노드 간 인바운드 | 같은 보안그룹에서만 k3s API 6443/TCP, Flannel 8472/UDP, kubelet 10250/TCP |
| State | `team02-letsgpt-bucket/tfstate/letsGPT-openAt/terraform.tfstate`, S3 네이티브 lock |

`terraform.tfvars`에는 `semi`와 `final` 두 인스턴스가 모두 활성화돼 있다. 과거의
“세미 1대에서 파이널 노드를 나중에 추가”하는 상태가 아니다.

## 파일별 책임

| 파일 | 책임 |
|---|---|
| `provider.tf` | Terraform/AWS·TLS·random provider와 S3 backend |
| `network.tf` | VPC, 퍼블릭 서브넷, IGW, 라우트, S3 Gateway Endpoint |
| `security.tf` | 80/443 공개와 k3s 노드 간 self 규칙 |
| `compute.tf` | EC2 2대, EIP, k3s 조인 토큰, user data 주입 |
| `user_data.sh.tpl` | Docker·AWS CLI·SSM Agent·runner·zram·k3s 설치와 역할별 설정 |
| `iam.tf` | EC2 인스턴스 프로파일, SSM, 공용 S3의 `app/`·`ops/` 접근 |
| `iam-oidc-k3s.tf` | k3s ServiceAccount OIDC와 `openat/product-sa` 전용 S3 Role |
| `github-oidc.tf` | GitHub-hosted Terraform plan용 OIDC Role — AWS 리소스 조회와 `tfstate/` 읽기·lock 권한 |
| `s3.tf` | 앱·이미지 prefix·tfstate가 함께 있는 주 버킷 |
| `images.tf` | 별도 staging/final 이미지 버킷과 OIDC JWKS 미러 버킷 |
| `variables.tf` / `terraform.tfvars` | 변수 계약과 현재 적용값 |
| `outputs.tf` | 인스턴스·S3·IAM·OIDC 출력값 |

## S3와 이미지 저장 경로

현재 k3s 애플리케이션 경로는 주 버킷 하나를 prefix로 나눠 쓴다.

| prefix | 용도 |
|---|---|
| `images/staging/` | 브라우저 presigned PUT 임시 업로드, 1일 후 미승격 객체 정리 |
| `images/final/` | product가 검증·승격한 서비스 이미지 |
| `app/` | 애플리케이션 데이터 |
| `ops/` | k3s·ArgoCD 운영 산출물 |
| `tfstate/` | Terraform state와 lock |

`k8s/base/01-configmap.yaml`은 staging/final 버킷 이름을 모두
`team02-letsgpt-bucket`으로 설정하고, `product-s3` Role도 이 버킷의 두 이미지 prefix만
허용한다. `images.tf`의 별도 staging/final 버킷 리소스와 관련 output은 아직 코드에 남아
있지만 현재 애플리케이션 런타임에는 연결되지 않는다.

OIDC JWKS 미러 버킷은 discovery와 JWKS 두 객체만 익명 읽기를 허용하고 쓰기는 잠근다.
실제 k3s issuer URL은 `https://openat.duckdns.org`이며, frontend가 같은 두 경로를
ConfigMap으로 서빙한다.

## IAM과 키리스 인증

- EC2는 인스턴스 프로파일로 SSM과 주 버킷의 `app/`·`ops/`만 접근한다.
- product 파드는 `system:serviceaccount:openat:product-sa` 토큰을
  `sts:AssumeRoleWithWebIdentity`로 교환한다. 정적 AWS 키를 파드에 저장하지 않는다.
- product Role은 주 버킷의 `images/staging/*`·`images/final/*` 객체 권한만 갖고
  `tfstate/*` 접근은 명시적으로 거부한다.
- search는 product 이미지 API를 사용하므로 S3 Role이 없다.
- `.github/workflows/terraform-plan.yml`은 GitHub OIDC Role로 PR의 `terraform/**`
  변경을 plan한다. AWS 리소스는 `ReadOnlyAccess`로 조회하고, S3 `tfstate/`에는 state 읽기와
  native lock 생성·해제를 위한 Put/Delete만 추가로 허용한다. CI는 `terraform apply`를 실행하지 않는다.

## EC2 부트스트랩

`user_data.sh.tpl`은 두 노드에 Docker, AWS CLI v2, deb 기반 SSM Agent, GitHub Actions
self-hosted runner 바이너리, zram을 설치한다. 이어서 역할에 따라 k3s server 또는 agent를
설치한다.

- server는 외부 OIDC issuer, secrets encryption, CPU·메모리 예약, eviction 임계값을 설정하고
  Helm을 설치한다.
- agent는 server `10.0.1.10:6443`을 기다린 뒤 같은 사전공유 토큰으로 조인하고
  observability taint를 적용한다.
- runner 바이너리만 설치하며 GitHub 등록 토큰은 user data에 저장하지 않는다. 등록은
  인스턴스 생성 후 한 번 수동으로 한다.

`compute.tf`는 라이브 노드 교체를 막기 위해 `ami`, `user_data`, `private_ip` 변경을
`ignore_changes`로 봉인한다. 이 세 값의 코드 변경은 기존 인스턴스에 자동 적용되지 않고 다음
콜드 리빌드부터 반영된다. k3s 조인 토큰은 민감값이지만 Terraform state에는 저장되므로 state
버킷 접근을 엄격히 제한해야 한다.

## 실행

사전 조건:

- Terraform 1.10 이상
- AWS CLI 자격증명
- SSM 접속 시 Session Manager Plugin
- `terraform.tfvars`의 전역 유일 S3 버킷 이름 확인

현재 backend가 이미 구성된 일반 작업:

```bash
cd terraform
terraform init
terraform fmt -check
terraform validate
terraform plan
```

`apply`는 plan을 사람이 확인한 뒤 로컬에서 명시적으로 실행한다.

```bash
terraform apply
```

완전히 새 AWS 계정에서 state 버킷부터 만드는 경우에만 2단계 부트스트랩이 필요하다.

1. `provider.tf`의 `backend "s3"` 블록을 잠시 주석 처리하고 로컬 state로 주 버킷을 만든다.
2. backend를 복구하고 실제 버킷 이름을 설정한다.
3. `terraform init -migrate-state`로 state를 S3에 옮긴다.

## SSM 접속

```bash
terraform output instance_ids
terraform output ssm_connect_commands
aws ssm start-session --target <instance-id> --region ap-northeast-2
```

## 주요 출력값

- `instance_ids`, `instance_public_ips`, `instance_private_ips`
- `ssm_connect_commands`
- `s3_bucket_name`
- `k3s_server_private_ip_planned`
- `k3s_oidc_issuer_url`, `product_s3_role_arn`
- `images_staging_bucket_name`, `images_final_bucket_name`, `oidc_jwks_bucket_name`
- `github_terraform_plan_role_arn`

`images_staging_bucket_name`과 `images_final_bucket_name`은 Terraform에 남아 있는 별도 버킷의
출력값이다. 현재 k3s runtime의 실제 이미지 버킷은 `s3_bucket_name`이다.
