#!/usr/bin/env python3
"""overlay kustomization.yaml의 이미지 pin 소유권을 집행한다.

구조(주석·apiVersion·kind·resources·override 대상 이미지의 집합, 그리고 각 이미지 항목의
name·newName 같은 필드)는 골격 파일이 소유하고, 각 이미지의 태그(newTag)만 deploy/state 파일이
소유한다. 그래서 항목은 항상 골격 것을 쓰고 그 안의 newTag 값만 deploy/state 값으로 치환한다 —
항목을 통째로 가져오면 골격이 newName(저장소 경로 이전)을 바꿔도 옛 값으로 되돌아간다.
deploy/state에 같은 name이 없으면 골격 항목을 그대로 쓴다(새 서비스 도입). deploy/state에만 남은
항목은 main이 더는 override하지 않는 이미지이므로 버린다.

태그 없는 배포는 base 이미지 참조를 그대로(사실상 latest) 내보내므로, 두 입력 모두 항목당
newTag가 정확히 하나인지 검증하고 아니면 실패한다.

YAML 파서(yq/PyYAML)를 쓰지 않고 텍스트 블록으로 다루는 이유: 러너에 yq가 없고, 재직렬화는
바로 이 파일에서 상시 충돌을 만든 원인이라 여기서 또 하면 안 된다. 대신 마커를 못 찾거나
블록 경계가 의심스러우면 조용히 넘기지 않고 실패한다.
"""

import pathlib
import re
import sys

IMAGES_MARKER = "images:"
LIST_ITEM = re.compile(r"^\s*-\s+\S")
NAME_FIELD = re.compile(r"^\s*(?:-\s+)?name:\s*(\S+)\s*$")
TAG_FIELD = re.compile(r"^\s*(?:-\s+)?newTag:\s*(\S+)\s*$")


def fail(message):
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_images(text, label):
    """(앞부분, 이름별 항목, 뒷부분)으로 쪼갠다. 항목 순서는 등장 순서를 유지한다."""
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line == IMAGES_MARKER:
            if start is not None:
                fail(f"{label}: 최상위 '{IMAGES_MARKER}' 키가 두 번 이상 있습니다.")
            start = index
    if start is None:
        fail(f"{label}: 최상위 '{IMAGES_MARKER}' 블록을 찾지 못했습니다.")

    end = len(lines)
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if not line.strip():
            continue
        if line[0].isspace() or LIST_ITEM.match(line):
            continue
        end = index
        break

    entries = []
    for line in lines[start + 1 : end]:
        if LIST_ITEM.match(line):
            entries.append([line])
        elif entries:
            entries[-1].append(line)
        elif line.strip():
            fail(f"{label}: '{IMAGES_MARKER}' 블록이 리스트 항목으로 시작하지 않습니다: {line!r}")

    named = []
    for entry in entries:
        name = next((match.group(1) for match in map(NAME_FIELD.match, entry) if match), None)
        if name is None:
            fail(f"{label}: 이미지 항목에서 name: 을 읽지 못했습니다: {entry!r}")
        if any(name == existing for existing, _ in named):
            fail(f"{label}: 이미지 항목 name이 중복입니다: {name}")
        tags = tags_of(entry)
        if not tags:
            fail(
                f"{label}: 이미지 항목 {name}에 newTag: 가 없습니다 "
                "— 태그 없이 렌더되면 base 이미지 참조가 그대로(사실상 latest) 배포됩니다."
            )
        if len(tags) > 1:
            fail(
                f"{label}: 이미지 항목 {name}에 newTag: 가 {len(tags)}개 있습니다 "
                f"— 어느 값이 pin인지 확정할 수 없습니다: {tags}"
            )
        named.append((name, entry))
    if not named:
        fail(f"{label}: '{IMAGES_MARKER}' 블록에 이미지 항목이 없습니다.")

    inside = sum(1 for entry in entries for line in entry if TAG_FIELD.match(line))
    total = sum(1 for line in lines if TAG_FIELD.match(line))
    if inside != total:
        fail(
            f"{label}: newTag: {total}개 중 {inside}개만 '{IMAGES_MARKER}' 블록 안에서 인식됐습니다 "
            "— 블록 경계를 신뢰할 수 없어 중단합니다."
        )

    return lines[: start + 1], named, lines[end:]


def tags_of(entry):
    return [match.group(1) for match in map(TAG_FIELD.match, entry) if match]


def tag_of(entry):
    """항목의 태그를 읽는다. parse_images가 항목당 하나임을 이미 보장한 입력만 들어온다."""
    tags = tags_of(entry)
    if len(tags) != 1:
        fail(f"이미지 항목의 newTag가 하나가 아닙니다({len(tags)}개): {entry!r}")
    return tags[0]


def with_tag(entry, tag):
    """골격 항목을 그대로 두고 그 안의 newTag 값만 deploy/state 값으로 치환한다.

    항목을 통째로 deploy/state에서 가져오면 name 말고 다른 구조 필드(newName 등)까지 옛 값으로
    돌아간다. 소유권대로 구조는 골격, 태그만 deploy/state가 되게 값 토큰만 갈아끼운다.
    """
    replaced = []
    for line in entry:
        match = TAG_FIELD.match(line)
        replaced.append(line[: match.start(1)] + tag + line[match.end(1) :] if match else line)
    return replaced


def indent_of(entry):
    return len(entry[0]) - len(entry[0].lstrip())


def reindent(entry, target):
    """리스트 항목의 들여쓰기를 블록의 대세에 맞춘다.

    골격(main)은 2스페이스 들여쓰기고 deploy/state는 kustomize 재직렬화 결과라 들여쓰기가 없다.
    항목은 골격 것만 쓰므로 보통 이미 평행하지만, 블록 안에서 들여쓰기가 어긋나면 시퀀스가
    깨져 YAML 자체를 못 읽으므로 한 종류로 맞춘다. 항목 내부의 상대 들여쓰기는 유지하면서
    전체만 평행이동한다.
    """
    shift = target - indent_of(entry)
    if shift == 0:
        return list(entry)
    if shift > 0:
        return [" " * shift + line if line.strip() else line for line in entry]
    for line in entry:
        if line.strip() and len(line) - len(line.lstrip()) < -shift:
            fail(f"이미지 항목 들여쓰기를 맞출 수 없습니다: {line!r}")
    return [line[-shift:] if line.strip() else line for line in entry]


def main():
    if len(sys.argv) != 4:
        fail("사용법: apply-image-pin-ownership.py <골격 파일> <deploy/state 파일> <출력 파일>")
    skeleton_path, state_path, out_path = (pathlib.Path(argument) for argument in sys.argv[1:4])

    head, skeleton_images, tail = parse_images(
        skeleton_path.read_text(encoding="utf-8"), f"골격({skeleton_path})"
    )
    _, state_images, _ = parse_images(
        state_path.read_text(encoding="utf-8"), f"deploy/state({state_path})"
    )
    state_by_name = dict(state_images)

    chosen = [
        (
            name,
            with_tag(entry, tag_of(state_by_name[name])) if name in state_by_name else entry,
            name in state_by_name,
        )
        for name, entry in skeleton_images
    ]
    target_indent = indent_of(chosen[0][1])
    body = []
    kept = []
    adopted = []
    for name, entry, from_state in chosen:
        body.extend(reindent(entry, target_indent))
        (kept if from_state else adopted).append(f"{name}={tag_of(entry)}")
    dropped = [name for name, _ in state_images if name not in dict(skeleton_images)]

    rendered = "\n".join(head + body + tail) + "\n"
    indents = {len(line) - len(line.lstrip()) for line in body if LIST_ITEM.match(line)}
    if len(indents) != 1:
        fail(f"집행 결과의 이미지 항목 들여쓰기가 섞였습니다: {sorted(indents)}")
    # parse_images가 집행 결과에도 항목당 newTag 유일성을 다시 확인한다(태그 없는 렌더 차단).
    _, result_images, _ = parse_images(rendered, "집행 결과")
    result_by_name = dict(result_images)
    for name, entry in state_images:
        if name in result_by_name and tags_of(result_by_name[name]) != [tag_of(entry)]:
            fail(f"집행 결과의 {name} 태그가 deploy/state 값과 다릅니다 — 이미지 pin이 보존되지 않았습니다.")
    if [name for name, _ in result_images] != [name for name, _ in skeleton_images]:
        fail("집행 결과의 이미지 항목 집합이 골격과 다릅니다 — 구조가 보존되지 않았습니다.")

    out_path.write_text(rendered, encoding="utf-8")
    print(f"deploy/state 이미지 pin 유지({len(kept)}): {', '.join(kept) or '없음'}")
    print(f"main 신규 항목 채택({len(adopted)}): {', '.join(adopted) or '없음'}")
    print(f"main에 없어 제거({len(dropped)}): {', '.join(dropped) or '없음'}")


if __name__ == "__main__":
    main()
