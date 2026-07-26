#!/usr/bin/env python3
"""overlay kustomization.yaml의 이미지 pin 소유권을 집행한다.

구조(주석·apiVersion·kind·resources·override 대상 이미지의 집합)는 골격 파일이 소유하고,
각 이미지의 태그는 deploy/state 파일이 소유한다. 골격에 있는 이미지 항목은 deploy/state에
같은 name이 있으면 그 항목을 통째로 가져오고(태그·newName 유지), 없으면 골격 항목을 그대로
쓴다(새 서비스 도입). deploy/state에만 남은 항목은 main이 더는 override하지 않는 이미지이므로
버린다.

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


def tag_of(entry):
    return next((match.group(1) for match in map(TAG_FIELD.match, entry) if match), None)


def indent_of(entry):
    return len(entry[0]) - len(entry[0].lstrip())


def reindent(entry, target):
    """리스트 항목의 들여쓰기를 블록의 대세에 맞춘다.

    골격(main, 2스페이스 들여쓰기)과 deploy/state(kustomize 재직렬화 결과라 들여쓰기 없음)의
    항목을 한 블록에 섞으면 시퀀스 들여쓰기가 어긋나 YAML 자체가 깨진다. 항목 내부의 상대
    들여쓰기는 유지하면서 전체만 평행이동한다.
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
        (name, state_by_name[name] if name in state_by_name else entry, name in state_by_name)
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
    _, result_images, _ = parse_images(rendered, "집행 결과")
    result_by_name = dict(result_images)
    for name, entry in state_images:
        if name in result_by_name and tag_of(result_by_name[name]) != tag_of(entry):
            fail(f"집행 결과의 {name} 태그가 deploy/state 값과 다릅니다 — 이미지 pin이 보존되지 않았습니다.")
    if [name for name, _ in result_images] != [name for name, _ in skeleton_images]:
        fail("집행 결과의 이미지 항목 집합이 골격과 다릅니다 — 구조가 보존되지 않았습니다.")

    out_path.write_text(rendered, encoding="utf-8")
    print(f"이미지 pin 유지({len(kept)}): {', '.join(kept) or '없음'}")
    print(f"main 신규 항목 채택({len(adopted)}): {', '.join(adopted) or '없음'}")
    print(f"main에 없어 제거({len(dropped)}): {', '.join(dropped) or '없음'}")


if __name__ == "__main__":
    main()
