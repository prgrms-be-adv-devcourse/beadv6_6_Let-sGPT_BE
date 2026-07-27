#!/usr/bin/env bash

# 호출자 셸의 변수를 읽어 Kubernetes Secret manifest를 stdout으로 렌더링한다.
# 값은 argv, 환경 변수, 임시 파일로 전달하지 않고 data-key=variable-name 형태의 이름만 받는다.
render_secret_manifest() {
  if [ "$#" -lt 4 ]; then
    echo "ERROR: 사용법: render_secret_manifest <name> <namespace> <type> <data-key=variable-name>..." >&2
    return 1
  fi

  local secret_name="$1" namespace="$2" secret_type="$3"
  local binding data_key variable_name encoded_value index
  local -a data_keys=() encoded_values=()
  shift 3

  case "$secret_name" in
    ''|*[!a-z0-9.-]*|[.-]*|*[-.])
      echo "ERROR: Secret name 형식이 올바르지 않습니다: $secret_name" >&2
      return 1
      ;;
  esac
  case "$namespace" in
    ''|*[!a-z0-9-]*|-*|*-)
      echo "ERROR: namespace 형식이 올바르지 않습니다: $namespace" >&2
      return 1
      ;;
  esac
  case "$secret_type" in
    Opaque|kubernetes.io/dockerconfigjson) ;;
    *)
      echo "ERROR: 지원하지 않는 Secret type: $secret_type" >&2
      return 1
      ;;
  esac

  for binding in "$@"; do
    case "$binding" in
      *=*)
        data_key="${binding%%=*}"
        variable_name="${binding#*=}"
        ;;
      *)
        data_key="$binding"
        variable_name="$binding"
        ;;
    esac
    case "$data_key" in
      ''|*[!A-Za-z0-9._-]*)
        echo "ERROR: Secret data key 형식이 올바르지 않습니다: $data_key" >&2
        return 1
        ;;
    esac
    case "$variable_name" in
      ''|[!A-Za-z_]*|*[!A-Za-z0-9_]*)
        echo "ERROR: shell variable name 형식이 올바르지 않습니다: $variable_name" >&2
        return 1
        ;;
    esac
    if ! [[ -v $variable_name ]]; then
      echo "ERROR: Secret data 변수에 값이 없습니다: $variable_name" >&2
      return 1
    fi

    # source한 .env가 export 문을 포함해도 인코더나 kubectl 자식 프로세스로 값을 넘기지 않는다.
    export -n "$variable_name" 2>/dev/null || true
    encoded_value=$(printf '%s' "${!variable_name}" | base64 | tr -d '\r\n') || return 1
    data_keys+=("$data_key")
    encoded_values+=("$encoded_value")
  done

  # 검증·인코딩이 모두 끝난 뒤에만 출력해 잘린 manifest가 apply되는 것을 막는다.
  printf 'apiVersion: v1\n'
  printf 'kind: Secret\n'
  printf 'metadata:\n'
  printf '  name: %s\n' "$secret_name"
  printf '  namespace: %s\n' "$namespace"
  printf 'type: %s\n' "$secret_type"
  printf 'data:\n'
  for index in "${!data_keys[@]}"; do
    printf '  %s: "%s"\n' "${data_keys[$index]}" "${encoded_values[$index]}"
  done
}
