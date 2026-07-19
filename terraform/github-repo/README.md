# terraform/github-repo

terraform-plan.yml이 사용하는 리포 Variable `AWS_TF_ROLE_ARN`을 코드로 관리하는 별도 루트 모듈.
메인 state의 `github_terraform_plan_role_arn` output을 읽어 리포 변수에 반영한다. Role ARN이
바뀌면 apply 한 번으로 리포 변수까지 전파돼, 사람이 리포 Settings에서 복붙하던 수동 루프를 닫는다.

## 인증

- provider 인증은 `GITHUB_TOKEN` 환경변수로 PAT를 공급한다. 코드·tfvars에 토큰을 기재하지 않는다.
- **apply는 로컬 전용** — 운영자가 셸 환경변수로 토큰을 잠깐 공급하고, CI에는 토큰을 두지 않는다.
- 권장: fine-grained PAT — 대상 리포(`beadv6_6_Let-sGPT_BE`) 한정, 권한은 **Variables read-write +
  Metadata read**.
- 조직 정책으로 fine-grained PAT가 막히면 classic PAT(`repo` 스코프)로 폴백한다. 스코프가 넓어지므로
  로컬 환경변수로만 쓰고 어디에도 저장하지 않는다.

```bash
export GITHUB_TOKEN=<PAT>
terraform -chdir=terraform/github-repo init
```

## 최초 1회 import

기존에 수동 등록된 변수가 이미 존재하므로, import 없이 apply하면 충돌한다. 최초 1회 import 후
plan 변경이 0이면 현행과 코드가 일치한다는 증명이며, 이것이 합격 기준이다(apply 불필요 상태가 정상).

```bash
terraform -chdir=terraform/github-repo import \
  github_actions_variable.tf_role_arn 'beadv6_6_Let-sGPT_BE:AWS_TF_ROLE_ARN'
terraform -chdir=terraform/github-repo plan   # 변경 0 확인 = 합격
```

## github_actions_secret 비채택

실제 시크릿을 terraform으로 넣으면 값이 tfstate에 평문으로 저장돼 state가 새 노출면이 된다.
변수(`AWS_TF_ROLE_ARN`)는 원래 평문 공개값이라 무해하지만, 시크릿은 그렇지 않으므로
`github_actions_secret`은 채택하지 않는다. 시크릿 등록은 현행 수동 유지.

## CI 경로 제외

이 루트는 apply가 로컬 전용이고 CI에 토큰이 없다. terraform-plan.yml의 `paths`에서
`!terraform/github-repo/**`로 제외해, 하위 디렉토리 PR에서 토큰 없이 plan이 도는 것을 막는다.
