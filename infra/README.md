# 드롭 커머스 부트캠프 - AWS 인프라 (Terraform)

세미 프로젝트 기준 단일 EC2(t3.large) + 단일 퍼블릭 서브넷 + 단일 S3 버킷 구성.
모듈화 없이 루트 모듈 평탄 구조로 작성되어 있다.

## 파일 구조

| 파일 | 역할 |
|---|---|
| `provider.tf` | Terraform/AWS provider 설정, S3 backend(주석 처리, 2단계 부트스트랩용) |
| `network.tf` | VPC, 퍼블릭 서브넷 1개, IGW, 라우트 테이블, S3 Gateway VPC 엔드포인트 |
| `security.tf` | EC2용 보안그룹 (80/443 공개, SSH는 화이트리스트) |
| `iam.tf` | EC2 인스턴스 프로파일(IAM Role) - S3 특정 prefix 접근 권한만 |
| `s3.tf` | S3 버킷(app 데이터 + tfstate 공용), 버전관리/암호화/Public Access Block/버킷 정책 |
| `compute.tf` | EC2 인스턴스(들) + 인스턴스당 Elastic IP, user_data 로딩 |
| `user_data.sh.tpl` | 부트스트랩 스크립트 템플릿 (Docker 설치 + SSH 하드닝 + fail2ban + deployer 계정) |
| `variables.tf` | 변수 정의 |
| `outputs.tf` | 출력값 (IP, 버킷명, Role ARN 등) |
| `terraform.tfvars` | 실제 적용 값 (placeholder 포함) |

## 사전 준비

- Terraform >= 1.10 (S3 네이티브 락 `use_lockfile` 사용)
- AWS CLI 자격 증명 설정 (`aws configure` 또는 환경변수) - 로컬에서 수동으로 `terraform apply` 실행

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
   이후부터는 `tfstate/dropcommerce-bootcamp/terraform.tfstate` 경로에
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
| `project_name` | 리소스 Name 태그/식별자 접두어 | `dropcommerce-bootcamp` |
| `vpc_cidr` | VPC CIDR | `10.0.0.0/16` |
| `public_subnet_cidr` | 퍼블릭 서브넷 CIDR | `10.0.1.0/24` |
| `availability_zone` | 서브넷 가용영역 | `ap-northeast-2a` |
| `ec2_instances` | 생성할 EC2 맵 (`instance_type`, `root_volume_size`) | (필수, tfvars에서 정의) |
| `ssh_port` | sshd 리스닝 포트 (비표준) | `49222` |
| `ssh_allowed_cidrs` | SSH 인바운드 허용 CIDR 목록 | `[]` |
| `ssh_public_keys` | authorized_keys에 등록할 SSH 공개키 목록 | `[]` |
| `deployer_user` | 배포 전용 계정 이름 (docker 그룹만, sudo 없음) | `deployer` |
| `s3_bucket_name` | S3 버킷 이름 (app 데이터 + tfstate 공용, 전역 유일) | (필수) |
| `s3_app_prefix` | IAM Role이 접근 가능한 S3 prefix | `app/` |

## SSH 접속

- `ssh_public_keys`에 등록한 공개키로만 접속 가능 (비밀번호 인증 비활성화, root 로그인 차단).
- 포트는 `ssh_port` (기본 `49222`).
- 기본 `ubuntu` 계정과 `deployer` 계정(docker 그룹만, sudo 없음) 양쪽에 동일한 키가 등록된다.
- 접속 예시 (apply 후 `terraform output ssh_connect_commands` 참고):
  ```bash
  ssh -p 49222 deployer@<EIP>
  ```
- **주의**: `ssh_public_keys`를 비워둔 채 apply하면 비밀번호 인증도 비활성화되어
  있어 인스턴스에 접속할 방법이 없어진다. apply 전에 최소 1개 이상의 공개키를 등록할 것.

## 파이널 전환 시 변경할 값

| 항목 | 세미 | 파이널 |
|---|---|---|
| `ec2_instances` | `semi` (t3.large) 1개만 | `final` (t3.medium) 항목을 맵에 추가 - 코드 수정 불필요, `terraform.tfvars`만 수정 |
| `ssh_allowed_cidrs` | 넓게 허용 (예: `["0.0.0.0/0"]`) 가능 | 본인/팀 공인 IP로 좁히기 (예: `["1.2.3.4/32"]`) |
| `ssh_port` | 그대로 사용 가능 | 필요시 재변경 (변경 시 EC2 재부팅/user_data 재실행 필요) |

`ec2_instances`에 항목을 추가하면 `for_each`에 의해 EC2 인스턴스와 Elastic IP가
각각 하나씩 추가 생성된다. 기존 `semi` 인스턴스는 영향받지 않는다.

## 보안그룹 / 네트워크 정책 요약

- 인바운드: `80`, `443` 전체 공개. SSH(`ssh_port`)는 `ssh_allowed_cidrs`에만 허용.
- 그 외 포트(PostgreSQL, Kafka 등 내부 서비스)는 SG에서 전혀 열지 않는다.
  서비스 간 통신은 docker-compose의 내부 네트워크에서만 이루어져야 한다
  (`user_data.sh.tpl` 주석 참고 - 호스트 포트로 `ports:` publish 금지).
- 아웃바운드는 전체 허용 (패키지 설치, 이미지 pull, S3 접근 등).
- NACL은 별도로 설정하지 않고 기본값(전체 허용)을 유지한다 - 통제는 SG로 일원화.

## IAM

- EC2에는 인스턴스 프로파일이 부착되어 있으며, 액세스 키를 서버에 저장하지 않는다.
- 권한은 `s3_app_prefix`(기본 `app/`) 하위 객체에 대한
  `GetObject`/`PutObject`/`DeleteObject`/`ListBucket`(prefix 조건)으로 제한된다.
- presigned URL 업로드는 이 Role 권한으로 동작하므로 별도 설정 없이 가능하다.
- GitHub Actions OIDC Role은 생성하지 않는다 - `terraform apply`는 항상 로컬에서 수동 실행한다.

## S3

- 단일 버킷을 app 데이터(`s3_app_prefix`)와 Terraform state(`tfstate/` prefix)에 공용으로 사용한다.
- ACL은 비활성화(`BucketOwnerEnforced`), Public Access Block 4개 옵션 모두 활성화.
- 버전 관리 + SSE-S3(AES256) 기본 암호화 활성화.
- 버킷 정책으로 비-HTTPS 요청을 거부한다.
- S3 Gateway VPC 엔드포인트를 통해 EC2 <-> S3 트래픽이 인터넷(IGW)을 거치지 않는다.
