# letsGPT - AWS 인프라 (Terraform)

세미 프로젝트 기준 단일 EC2(t3.large) + 단일 퍼블릭 서브넷 + 단일 S3 버킷 구성.
모듈화 없이 루트 모듈 평탄 구조로 작성되어 있다.

## 파일 구조

| 파일 | 역할 |
|---|---|
| `provider.tf` | Terraform/AWS provider 설정, S3 backend(2단계 부트스트랩용) |
| `network.tf` | VPC, 퍼블릭 서브넷 1개, IGW, 라우트 테이블, S3 Gateway VPC 엔드포인트 |
| `security.tf` | EC2용 보안그룹 (80/443 공개, SSH 없음 - 접속은 SSM Session Manager 사용) |
| `iam.tf` | EC2 인스턴스 프로파일(S3 접근 + SSM) |
| `s3.tf` | S3 버킷(app 데이터 + tfstate 공용), 버전관리/암호화/Public Access Block/버킷 정책 |
| `compute.tf` | EC2 인스턴스(들) + Elastic IP, IMDSv2 강제, user_data 로딩 |
| `user_data.sh.tpl` | 부트스트랩 스크립트 템플릿 (Docker 설치 + deployer 계정 + SSM Agent 확인) |
| `variables.tf` | 변수 정의 |
| `outputs.tf` | 출력값 (IP, 버킷명, Role ARN, SSM 접속 명령어 등) |
| `terraform.tfvars` | 실제 적용 값 |

## 사전 준비

- Terraform >= 1.10 (S3 네이티브 락 `use_lockfile` 사용)
- AWS CLI 자격 증명 설정 (`aws configure` 또는 환경변수)
- AWS Session Manager Plugin (SSM 접속용, 로컬 PC에 설치)

## State 백엔드 부트스트랩 (닭-달걀 문제 해결: 2단계)

이 코드는 state를 저장할 S3 버킷 자체도 직접 생성한다. 따라서 처음에는
backend 없이(로컬 state로) 버킷을 먼저 만들고, 그 다음에 state를 S3로 옮긴다.

### 1단계: 로컬 state로 최초 apply (버킷 생성)

1. `terraform.tfvars`에서 `s3_bucket_name`을 전역적으로 유일한 이름으로 변경한다.
2. `provider.tf`의 `backend "s3" { ... }` 블록은 **주석 상태 그대로 둔다**.
3. 실행:
   ```bash
   terraform init
   terraform apply
   ```
   이 시점의 state는 로컬 `terraform.tfstate`에 저장되며, `aws_s3_bucket.this`
   (app 데이터 + tfstate 공용 버킷)가 생성된다.

### 2단계: state를 S3로 이전

1. `provider.tf`의 `backend "s3" { ... }` 블록 주석을 해제한다.
2. `bucket` 값을 1단계에서 사용한 `s3_bucket_name`과 동일하게 채운다.
3. 실행:
   ```bash
   terraform init -migrate-state
   ```
   프롬프트에서 로컬 state를 S3로 복사할지 물으면 `yes`를 선택한다.
   이후부터는 `tfstate/letsGPT-openAt/terraform.tfstate` 경로에
   state가 저장되고, S3 네이티브 락(`use_lockfile = true`)으로 동시 실행을 방지한다.
4. 로컬 `terraform.tfstate` / `terraform.tfstate.backup`은 더 이상 필요 없으므로
   삭제해도 된다 (`.gitignore`에 의해 git에는 포함되지 않음).

## 일반 적용(apply) 절차 (2단계 완료 후)

```bash
terraform init      # backend가 이미 설정되어 있으면 자동으로 S3 state 사용
terraform plan
terraform apply
```

## 변수 설명 (`variables.tf`)

| 변수 | 설명 | 기본값 |
|---|---|---|
| `aws_region` | 리전 | `ap-northeast-2` |
| `project_name` | 리소스 Name 태그/식별자 접두어 | `letsGpt-openAt` |
| `vpc_cidr` | VPC CIDR | `10.0.0.0/16` |
| `public_subnet_cidr` | 퍼블릭 서브넷 CIDR | `10.0.1.0/24` |
| `availability_zone` | 서브넷 가용영역 | `ap-northeast-2a` |
| `ec2_instances` | 생성할 EC2 맵 (`instance_type`, `root_volume_size`) | (필수, tfvars에서 정의) |
| `deployer_user` | 배포 전용 계정 이름 (docker 그룹만, sudo 없음) | `deployer` |
| `s3_bucket_name` | S3 버킷 이름 (app 데이터 + tfstate 공용, 전역 유일) | (필수) |
| `s3_app_prefix` | IAM Role이 접근 가능한 S3 prefix | `app/` |

## SSM 접속

SSH 대신 AWS Systems Manager Session Manager를 사용한다. 키페어, 인바운드 포트 불필요.

```bash
# apply 후 인스턴스 ID 확인
terraform output instance_ids

# SSM으로 접속 (AWS CLI + Session Manager Plugin 필요)
aws ssm start-session --target i-0abc1234... --region ap-northeast-2
```

접속 명령어는 `terraform output ssm_connect_commands`에서도 확인 가능.

## GitHub Actions 배포 (self-hosted runner)

GitHub Actions는 AWS API를 직접 호출하지 않는다(OIDC/액세스 키 불필요) — self-hosted runner를
EC2 인스턴스 위에 직접 설치해서 그 박스 안에서 잡을 실행하므로, 인증 자체가 필요 없다.
러너 등록 절차는 `user_data.sh.tpl`의 "GitHub Actions self-hosted runner" 섹션 참고.

## 파이널 전환 시 변경할 값

| 항목 | 세미 | 파이널 |
|---|---|---|
| `ec2_instances` | `semi` (t3.large) 1개만 | `final` (t3.medium) 항목을 맵에 추가 - 코드 수정 불필요, `terraform.tfvars`만 수정 |

`ec2_instances`에 항목을 추가하면 `for_each`에 의해 EC2 인스턴스와 Elastic IP가
각각 하나씩 추가 생성된다. 기존 `semi` 인스턴스는 영향받지 않는다.

## 보안그룹 / 네트워크 정책 요약

- 인바운드: `80`, `443` 전체 공개. SSH 포트 없음 (접속은 SSM 사용).
- 그 외 포트(PostgreSQL, Kafka 등 내부 서비스)는 SG에서 전혀 열지 않는다.
  서비스 간 통신은 docker-compose의 내부 네트워크에서만 이루어져야 한다
  (`user_data.sh.tpl` 주석 참고 - 호스트 포트로 `ports:` publish 금지).
- 아웃바운드는 전체 허용 (패키지 설치, 이미지 pull, S3 접근, SSM Agent 통신 등).
- NACL은 별도로 설정하지 않고 기본값(전체 허용)을 유지한다 - 통제는 SG로 일원화.

## IAM

- EC2 인스턴스 프로파일: `s3_app_prefix` 하위 S3 접근 + `AmazonSSMManagedInstanceCore` (Session Manager + Run Command).
- 액세스 키를 서버나 GitHub에 저장하지 않는다.

## S3

- 단일 버킷을 app 데이터(`s3_app_prefix`)와 Terraform state(`tfstate/` prefix)에 공용으로 사용한다.
- ACL은 비활성화(`BucketOwnerEnforced`), Public Access Block 4개 옵션 모두 활성화.
- 버전 관리 + SSE-S3(AES256) 기본 암호화 활성화.
- 버킷 정책으로 비-HTTPS 요청을 거부한다.
- S3 Gateway VPC 엔드포인트를 통해 EC2 <-> S3 트래픽이 인터넷(IGW)을 거치지 않는다.
