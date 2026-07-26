#!/usr/bin/env bash

# main -> deploy/state 병합을 "누가 무엇을 소유하는가"대로 집행한다.
#
#  - 구조는 main이 소유한다: k8s/ 아래 모든 매니페스트, base의 리소스 목록·패치 대상,
#    overlay의 apiVersion/kind/resources, 그리고 overlay가 override할 이미지 항목의 집합.
#  - 값은 deploy/state가 소유한다: overlay images의 태그(이미지 SHA pin), FE 배포 sequence,
#    그리고 CD가 클러스터에서 읽어와 sed로 쓰는 AI 값 3종(조회 Secret revision,
#    read-model Job 이름·identity).
#
# 왜 병합 전략(-X ours)에 맡기지 않는가: `kustomize edit set image`가
# k8s/overlay/kustomization.yaml을 파싱→재직렬화하면서 리스트 들여쓰기를 없애고 newName
# 필드를 덧붙인다(main은 2스페이스 들여쓰기, deploy/state는 들여쓰기 없음). 그래서 main이
# 이 파일을 건드리면 거의 항상 충돌로 잡히고, `git merge -X ours`는 충돌 hunk를 파일 단위가
# 아니라 hunk 단위로 무조건 deploy/state 쪽에 채택해 main의 구조 변경을 조용히 버렸다.
# 이 경로로 실제 유실 사고가 3회 났다: 32-https-redirect-middleware.yaml 누락,
# 27-queue.yaml 재발, AI 배포 배선 유실. 그래서 충돌 해소를 범용 전략에 위임하지 않고
# 파일별 소유자를 이 스크립트가 명시적으로 정한다.
#
# 규칙에 없는 충돌은 조용히 한쪽을 고르지 않는다 — 병합을 되돌리고 실패한다(fail-closed).
# 그런 충돌은 "deploy/state를 CD 계약 밖에서 직접 고쳤다"는 신호이므로 사람이 판단해야 한다.
set -euo pipefail

SHA_INPUT="${1:?사용법: $0 <main commit-ish>}"
GIT="${GIT:-git}"
PYTHON="${PYTHON:-python3}"
WORK_DIR="${WORK_DIR:-${RUNNER_TEMP:-/tmp}}"

OVERLAY=k8s/overlay/kustomization.yaml
SEQUENCE_FILE=k8s/overlay/frontend-image-sequence.txt
REVISION_PATCH=k8s/base/29-ai-query-secret-revision-patch.yaml
JOB_NAME_STATE=k8s/base/29-ai-read-model-job-name.yaml
IDENTITY_PATCH=k8s/base/29-ai-read-model-deployment-identity-patch.yaml

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
IMAGE_OWNERSHIP_SCRIPT="$SCRIPT_DIR/apply-image-pin-ownership.py"
[ -f "$IMAGE_OWNERSHIP_SCRIPT" ] || {
  echo "ERROR: 이미지 pin 소유권 스크립트를 찾지 못했습니다: $IMAGE_OWNERSHIP_SCRIPT" >&2
  exit 1
}

SHA=$("$GIT" rev-parse --verify --quiet "${SHA_INPUT}^{commit}") || {
  echo "ERROR: 병합 대상 commit을 찾지 못했습니다: $SHA_INPUT" >&2
  exit 1
}
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || {
  echo "ERROR: 병합 대상 commit SHA 형식이 올바르지 않습니다: $SHA" >&2
  exit 1
}

# 병합 전 deploy/state의 이미지 pin을 먼저 떠 둔다. 병합이 어떻게 끝나든 이 태그가 정답이다.
# main 버전을 통째로 채택하면 모든 pin이 latest로 리셋돼, 뒤 단계의
# `git merge-base --is-ancestor` 롤백 방지 가드가 비교할 기준 자체를 잃는다.
STATE_OVERLAY="$WORK_DIR/deploy-state-overlay-kustomization.yaml"
MAIN_OVERLAY="$WORK_DIR/main-overlay-kustomization.yaml"
"$GIT" show "HEAD:$OVERLAY" > "$STATE_OVERLAY" || {
  echo "ERROR: 현재 deploy/state에서 $OVERLAY 를 읽지 못했습니다." >&2
  exit 1
}

set +e
"$GIT" merge --no-ff --no-commit "$SHA"
merge_status=$?
set -e

if ! "$GIT" rev-parse --quiet --verify MERGE_HEAD >/dev/null; then
  if [ "$merge_status" -ne 0 ]; then
    echo "ERROR: main($SHA) 병합을 시작하지 못했습니다." >&2
    exit 1
  fi
  echo "main($SHA)은 이미 deploy/state에 반영돼 있어 병합할 것이 없습니다."
  exit 0
fi

conflicts=()
while IFS= read -r path; do
  [ -n "$path" ] && conflicts+=("$path")
done < <("$GIT" diff --name-only --diff-filter=U)

overlay_conflicted=false
sequence_conflicted=false
revision_patch_conflicted=false
read_model_conflicted=false
unexpected=()
for path in ${conflicts[@]+"${conflicts[@]}"}; do
  case "$path" in
    "$OVERLAY") overlay_conflicted=true ;;
    "$SEQUENCE_FILE") sequence_conflicted=true ;;
    "$REVISION_PATCH") revision_patch_conflicted=true ;;
    "$JOB_NAME_STATE" | "$IDENTITY_PATCH") read_model_conflicted=true ;;
    *) unexpected+=("$path") ;;
  esac
done

if [ "${#unexpected[@]}" -gt 0 ]; then
  echo "ERROR: main이 소유한 매니페스트에서 소유권 규칙에 없는 충돌이 발생했습니다:" >&2
  printf '  - %s\n' "${unexpected[@]}" >&2
  echo "  deploy/state는 CD가 값을 쓰는 파일 외에는 직접 수정하지 않는다는 전제가 깨졌습니다." >&2
  echo "  해당 변경을 main에 먼저 반영(PR)한 뒤 다시 배포하세요. 자동으로 한쪽을 고르지 않습니다." >&2
  "$GIT" merge --abort
  exit 1
fi

# FE 배포 sequence는 단조 증가 카운터라 deploy/state가 값을 소유한다. main엔 이 파일이 없어
# 실제로는 충돌이 나지 않지만, 나더라도 뒤로 감기지 않게 현재 값을 지킨다.
if [ "$sequence_conflicted" = true ]; then
  echo "NOTICE: $SEQUENCE_FILE 충돌 — 단조 증가 카운터이므로 deploy/state 값을 유지합니다."
  "$GIT" checkout --ours -- "$SEQUENCE_FILE"
  "$GIT" add -- "$SEQUENCE_FILE"
fi

# 조회 Secret revision patch는 이 job의 뒤 단계가 조건 없이 다시 쓴다. 따라서 충돌 시
# 구조(main)를 택해도 값이 낡을 일이 없다.
if [ "$revision_patch_conflicted" = true ]; then
  echo "NOTICE: $REVISION_PATCH 충돌 — 구조는 main을 따르고 값은 이 실행이 다시 씁니다."
  "$GIT" checkout "$SHA" -- "$REVISION_PATCH"
fi

# read-model identity 값 2종은 identity가 바뀔 때만 갱신되는 조건부 재작성이라, 한쪽만
# main 버전이 되면 두 파일의 identity가 어긋난 채 뒤 단계 검증에서 배포가 멈춘다.
# 그래서 둘 중 하나라도 충돌하면 둘 다 main 버전으로 맞춘다 — seed 값으로 돌아가면
# identity 비교가 반드시 불일치가 되어 이 실행이 두 파일을 한 벌로 다시 쓴다.
# 대가는 read-model Job이 새 이름으로 한 번 더 생성되는 것뿐이고(적용 스크립트는 멱등),
# 그 대신 main의 구조 변경이 유실되지 않는다.
if [ "$read_model_conflicted" = true ]; then
  echo "NOTICE: read-model identity 파일 충돌 — 구조는 main을 따르고 identity는 이 실행이 다시 계산합니다."
  for path in "$JOB_NAME_STATE" "$IDENTITY_PATCH"; do
    if "$GIT" cat-file -e "$SHA:$path" 2>/dev/null; then
      "$GIT" checkout "$SHA" -- "$path"
    fi
  done
fi

# overlay는 CD가 파일 전체를 재직렬화하는 유일한 파일이라 3-way 병합 결과를 신뢰하지 않는다.
# 충돌 여부와 무관하게 main 버전을 골격으로 확정하고(구조·override 대상 이미지 집합 = main)
# 그 위에 병합 전 deploy/state의 이미지 태그를 다시 얹는다(값 = deploy/state). 이렇게 하면
# 결과가 병합이 어디서 충돌했는지에 좌우되지 않고 항상 같다. $SHA가 이미 반영된 경우는
# 위에서 이미 빠져나갔으므로, 낡은 골격으로 되돌아갈 여지는 없다.
if [ "$overlay_conflicted" = true ]; then
  echo "NOTICE: $OVERLAY 충돌 — main 구조 위에 deploy/state 이미지 pin을 다시 얹습니다."
fi
"$GIT" show "$SHA:$OVERLAY" > "$MAIN_OVERLAY" || {
  echo "ERROR: main($SHA)에서 $OVERLAY 를 읽지 못했습니다 — 이미지 pin 소유권을 집행할 수 없습니다." >&2
  "$GIT" merge --abort
  exit 1
}
"$PYTHON" "$IMAGE_OWNERSHIP_SCRIPT" "$MAIN_OVERLAY" "$STATE_OVERLAY" "$OVERLAY" || {
  echo "ERROR: $OVERLAY 의 이미지 pin 소유권 집행에 실패했습니다." >&2
  "$GIT" merge --abort
  exit 1
}
"$GIT" add -- "$OVERLAY"

remaining=$("$GIT" diff --name-only --diff-filter=U)
if [ -n "$remaining" ]; then
  echo "ERROR: 소유권 집행 후에도 미해결 충돌이 남았습니다:" >&2
  printf '  - %s\n' "$remaining" >&2
  "$GIT" merge --abort
  exit 1
fi

"$GIT" commit --no-edit
echo "main($SHA) 병합 완료 — 구조는 main, 이미지 pin과 CD 값은 deploy/state로 확정."
