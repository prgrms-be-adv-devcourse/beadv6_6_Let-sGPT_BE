# legacy/ — k3s 전환으로 폐기된 도커컴포즈 배포 자산

> 2026-07-10 이동. k3s + ArgoCD(GitOps) 컷오버(2026-07-09, CD e2e 시연 완료)로 **더 이상 실행되지 않는** 파일들.
> 히스토리·참고용으로만 보존. 현재 배포/통합의 단일 소스는 `k8s/`(매니페스트) + `.github/workflows/deploy.yml`(CD).

| 파일 | 원래 역할 | 폐기 이유 |
|---|---|---|
| `docker-compose.dev.yml` | EC2 dev 배포(GHCR 이미지 pull) | k3s 매니페스트(`k8s/`)로 대체 |
| `docker-compose.full.yml` | 로컬 풀스택 통합 실행 | 로컬 통합도 k3s/스모크로 대체 |
| `deploy-cold.yml` | 구 CD 워크플로(`deploy/cold-start` 브랜치 push → compose 배포) | `.github/workflows/deploy.yml`(ArgoCD 수렴) 로 대체. **여기 있으면 GitHub이 워크플로로 인식하지 않아 자동 비활성** |

- 로컬 **인프라**(postgres/kafka/redis)만 띄우는 `docker-compose.yml`은 **레거시가 아니며 루트에 유지**된다.
- 되살릴 일이 있으면 파일을 원위치로 옮기고 참조(README·docs/SETUP)를 복원할 것.
