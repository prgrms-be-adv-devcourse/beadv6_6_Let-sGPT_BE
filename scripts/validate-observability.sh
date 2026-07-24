#!/usr/bin/env bash
# 관측 스택 정적 검증 — 클러스터 없이 도는 검사만 수행한다.
#   1) k8s/observability 의 각 grafana ConfigMap 에서 embed 된 대시보드 JSON 을 추출해 파싱 검증
#   2) 모든 대시보드 uid 를 모아 중복 없음 확인
#   3) provider path <-> 볼륨 마운트 <-> 볼륨의 configMap 이름 <-> CM metadata.name 4중 정합 확인
#   4) kubectl 이 있으면 k8s/observability / k8s/base 를 kustomize 렌더해 실패 없음 확인 (없으면 skip)
# 실패 시 비영(non-zero) 종료, 성공 시 요약 출력.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OBS_DIR="${REPO_ROOT}/k8s/observability"
BASE_DIR="${REPO_ROOT}/k8s/base"

echo "== 관측 스택 정적 검증 =="
echo "repo: ${REPO_ROOT}"
echo

# --- 1~3: 대시보드 JSON 파싱 + uid 중복 + 4중 정합 (python) ---
python3 - "${OBS_DIR}" <<'PY'
import json, sys, os, glob
import yaml

obs_dir = sys.argv[1]
errors = []

# --- 모든 옵저버빌리티 yaml 문서 로드 ---
docs = []  # (파일경로, 파싱된 문서)
for path in sorted(glob.glob(os.path.join(obs_dir, "*.yaml"))):
    with open(path, "r", encoding="utf-8") as f:
        try:
            for d in yaml.safe_load_all(f):
                if d is not None:
                    docs.append((path, d))
        except yaml.YAMLError as e:
            errors.append(f"YAML 파싱 실패: {path}: {e}")

# --- ConfigMap 인덱스 (metadata.name -> 문서) ---
configmaps = {}
for path, d in docs:
    if isinstance(d, dict) and d.get("kind") == "ConfigMap":
        name = (d.get("metadata") or {}).get("name")
        if name:
            configmaps[name] = (path, d)

# --- 1) 대시보드 JSON 추출 + 파싱, 2) uid 수집 ---
# 대시보드 CM = data 의 키가 .json 으로 끝나는 값을 가진 ConfigMap
dashboard_cm_names = set()
uid_owner = {}   # uid -> "cm/key" (첫 소유자)
dup_uids = []
dashboard_count = 0

for name, (path, d) in configmaps.items():
    data = d.get("data") or {}
    json_keys = [k for k in data if k.endswith(".json")]
    if not json_keys:
        continue
    dashboard_cm_names.add(name)
    for k in json_keys:
        raw = data[k]
        try:
            dash = json.loads(raw)
        except json.JSONDecodeError as e:
            errors.append(f"대시보드 JSON 파싱 실패: {name}/{k} ({os.path.basename(path)}): {e}")
            continue
        dashboard_count += 1
        uid = dash.get("uid")
        if not uid:
            errors.append(f"대시보드 uid 누락: {name}/{k}")
            continue
        loc = f"{name}/{k}"
        if uid in uid_owner:
            dup_uids.append((uid, uid_owner[uid], loc))
        else:
            uid_owner[uid] = loc

for uid, a, b in dup_uids:
    errors.append(f"대시보드 uid 중복: '{uid}' <- {a} , {b}")

# --- 3) 4중 정합: provider path <-> volumeMount <-> volume.configMap <-> CM metadata.name ---
# grafana Deployment + providers ConfigMap 를 30-grafana.yaml 등에서 찾는다.
providers_cm = None
grafana_deploy = None
for path, d in docs:
    if not isinstance(d, dict):
        continue
    if d.get("kind") == "ConfigMap" and (d.get("metadata") or {}).get("name") == "grafana-dashboard-providers":
        providers_cm = d
    if d.get("kind") == "Deployment" and (d.get("metadata") or {}).get("name") == "grafana":
        grafana_deploy = d

provider_paths = []
# providers.yaml 은 configMapGenerator 로 이관되어 inline ConfigMap 이 아니라
# config/providers.yaml 파일로 존재할 수 있다. inline CM 이 있으면 그걸, 없으면 파일을 읽는다.
prov_raw = None
if providers_cm is not None:
    prov_raw = (providers_cm.get("data") or {}).get("providers.yaml")
if not prov_raw:
    cfg_path = os.path.join(obs_dir, "config", "providers.yaml")
    if os.path.exists(cfg_path):
        with open(cfg_path, "r", encoding="utf-8") as f:
            prov_raw = f.read()
if not prov_raw:
    errors.append("grafana-dashboard-providers 의 providers.yaml 을 찾지 못함 (inline CM 또는 config/providers.yaml)")
else:
    prov = yaml.safe_load(prov_raw)
    for p in (prov.get("providers") or []):
        path_opt = (p.get("options") or {}).get("path")
        if path_opt:
            provider_paths.append((p.get("name"), path_opt))

# Deployment 에서 volumeMounts(name->mountPath), volumes(name->configMap.name) 추출
mount_by_path = {}   # mountPath -> volume name
vol_to_cm = {}       # volume name -> configMap name
if grafana_deploy is None:
    errors.append("grafana Deployment 을 찾지 못함")
else:
    tspec = ((grafana_deploy.get("spec") or {}).get("template") or {}).get("spec") or {}
    for c in (tspec.get("containers") or []):
        for vm in (c.get("volumeMounts") or []):
            mp, nm = vm.get("mountPath"), vm.get("name")
            if mp and nm:
                mount_by_path[mp] = nm
    for v in (tspec.get("volumes") or []):
        cm = (v.get("configMap") or {}).get("name")
        if v.get("name") and cm:
            vol_to_cm[v["name"]] = cm

# provider 의 각 path 를 따라 4중 사슬을 검증
referenced_cms = set()
for pname, ppath in provider_paths:
    vol_name = mount_by_path.get(ppath)
    if vol_name is None:
        errors.append(f"provider '{pname}' path '{ppath}' 에 대응하는 volumeMount(mountPath) 없음")
        continue
    cm_name = vol_to_cm.get(vol_name)
    if cm_name is None:
        errors.append(f"volume '{vol_name}' (provider '{pname}') 에 configMap 참조 없음")
        continue
    if cm_name not in configmaps:
        errors.append(f"volume '{vol_name}' 이 참조한 ConfigMap '{cm_name}' 이 관측 매니페스트에 없음")
        continue
    if cm_name not in dashboard_cm_names:
        errors.append(f"provider '{pname}' 가 가리킨 ConfigMap '{cm_name}' 에 대시보드(.json) 데이터가 없음")
        continue
    referenced_cms.add(cm_name)

# 대시보드 CM 중 어떤 provider 도 참조하지 않는 것(고아) 경고성 실패
orphan = dashboard_cm_names - referenced_cms
for cm_name in sorted(orphan):
    errors.append(f"대시보드 ConfigMap '{cm_name}' 을 참조하는 provider path 가 없음(고아)")

# --- 결과 ---
print(f"[1] 파싱한 대시보드 JSON : {dashboard_count} 개")
print(f"[2] 고유 대시보드 uid    : {len(uid_owner)} 개")
for uid in sorted(uid_owner):
    print(f"      - {uid}  ({uid_owner[uid]})")
print(f"[3] provider path        : {len(provider_paths)} 개, 4중 정합 대상 CM {len(referenced_cms)} 개")
for pname, ppath in provider_paths:
    vol = mount_by_path.get(ppath, "?")
    cm = vol_to_cm.get(vol, "?")
    print(f"      - {pname}: {ppath} -> vol '{vol}' -> cm '{cm}'")

if errors:
    print()
    print("검증 실패:")
    for e in errors:
        print(f"  ✗ {e}")
    sys.exit(1)
print()
print("JSON/uid/정합 검증 통과")
PY

echo

# --- 4: kustomize 렌더 (kubectl 있을 때만) ---
if command -v kubectl >/dev/null 2>&1; then
  echo "[4] kustomize 렌더 검증"
  for d in "${OBS_DIR}" "${BASE_DIR}"; do
    rel="${d#${REPO_ROOT}/}"
    if kubectl kustomize "${d}" >/dev/null; then
      echo "      ok: kubectl kustomize ${rel}"
    else
      echo "      ✗ 실패: kubectl kustomize ${rel}" >&2
      exit 1
    fi
  done
else
  echo "[4] kubectl 미설치 — kustomize 렌더 검증 skip (경고)" >&2
fi

echo
echo "== 모든 정적 검증 통과 =="
