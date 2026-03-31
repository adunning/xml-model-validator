#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SCRIPT_PATH="${SCRIPT_DIR}/resolve-release-jar-tag.sh"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEMP_ROOT}"' EXIT HUP INT TERM

run_case() {
  case_name="$1"
  action_ref="$2"
  mvnw_version="${3:-}"
  expected_output="${4:-}"

  case_dir="${TEMP_ROOT}/${case_name}"
  mkdir -p "${case_dir}"

  cat > "${case_dir}/mvnw" <<EOF
#!/bin/sh
printf '%s\n' '${mvnw_version}'
EOF
  chmod +x "${case_dir}/mvnw"

  : > "${case_dir}/github-output.txt"

  (
    cd "${case_dir}"
    GITHUB_OUTPUT="${case_dir}/github-output.txt" \
      XML_MODEL_VALIDATOR_ACTION_REF="${action_ref}" \
      sh "${SCRIPT_PATH}"
  )

  actual_output="$(cat "${case_dir}/github-output.txt")"
  if [ "${actual_output}" != "${expected_output}" ]; then
    echo "Unexpected output for ${case_name}" >&2
    echo "expected: ${expected_output}" >&2
    echo "actual:   ${actual_output}" >&2
    exit 1
  fi
}

run_case exact-tag v2.1.0 ignored "release_tag=v2.1.0"
run_case matching-major v2 2.1.0 "release_tag=v2.1.0"
run_case mismatched-major v2 3.0.0 ""
run_case unsupported-ref main ignored ""
