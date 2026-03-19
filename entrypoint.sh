#!/bin/sh
set -eu

export XML_MODEL_VALIDATOR_WORKSPACE="${XML_MODEL_VALIDATOR_WORKSPACE:-${GITHUB_WORKSPACE}}"
export XML_MODEL_VALIDATOR_CACHE_HOME="${XML_MODEL_VALIDATOR_CACHE_HOME:-${HOME}/.cache/xml-model-validator}"

ACTION_ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
JAR_CACHE_DIR="${XML_MODEL_VALIDATOR_CACHE_HOME}/jar"
JAR_PATH="${JAR_CACHE_DIR}/xml-model-validator.jar"
CHANGED_FILE_LIST="${RUNNER_TEMP}/xml-model-validator-changed-files.txt"

if [ ! -f "${JAR_PATH}" ]; then
  echo "XML Model Validator: building from source..." >&2
  mkdir -p "${JAR_CACHE_DIR}"
  (cd "${ACTION_ROOT}" && ./mvnw -B -q package -DskipTests)
  cp "${ACTION_ROOT}/target/xml-model-validator.jar" "${JAR_PATH}"
fi

set -- java -jar "${JAR_PATH}"

ensure_git_history() {
  if [ "$(git rev-parse --is-shallow-repository 2>/dev/null || printf 'false')" = "true" ]; then
    git fetch --no-tags --prune --unshallow origin >/dev/null 2>&1 || true
  fi
}

resolve_push_base() {
  jq -r '.before // empty' "${GITHUB_EVENT_PATH}"
}

api_pull_request_changed_files() {
  pr_number="$(jq -r '.number // empty' "${GITHUB_EVENT_PATH}")"
  if [ -z "${pr_number}" ]; then
    echo "XML Model Validator could not determine pull request number for API validation." >&2
    return 1
  fi

  gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}/files" \
    --jq '.[] | select(.status != "removed") | .filename | select(endswith(".xml"))' || return 1
}

api_push_changed_files() {
  before_sha="$(resolve_push_base)"
  if [ -z "${before_sha}" ] || [ "${before_sha}" = "0000000000000000000000000000000000000000" ]; then
    return 0
  fi

  all_files="$(gh api "repos/${GITHUB_REPOSITORY}/compare/${before_sha}...${GITHUB_SHA}" \
    --jq '.files[]? | select(.status != "removed") | .filename')" || return 1
  # The compare API caps the files list at 300; detect truncation so the caller can fall back.
  file_count="$(printf '%s\n' "${all_files}" | grep -c . || true)"
  if [ "${file_count}" -ge 300 ]; then
    echo "XML Model Validator: compare API returned ${file_count} file(s) (at API limit); falling back." >&2
    return 1
  fi
  printf '%s\n' "${all_files}" | grep '\.xml$' || true
}

write_changed_files_git() {
  mkdir -p "$(dirname "${CHANGED_FILE_LIST}")"

  (
    cd "${XML_MODEL_VALIDATOR_WORKSPACE}"

    if [ "${GITHUB_EVENT_NAME}" = "pull_request" ] || [ "${GITHUB_EVENT_NAME}" = "pull_request_target" ]; then
      ensure_git_history
      git fetch --no-tags origin "${GITHUB_BASE_REF}:refs/remotes/origin/${GITHUB_BASE_REF}" >/dev/null 2>&1 || true
      git diff --name-only --diff-filter=ACMR "origin/${GITHUB_BASE_REF}...${GITHUB_SHA}" -- '*.xml'
      exit
    fi

    if [ "${GITHUB_EVENT_NAME}" = "push" ]; then
      before_sha="$(resolve_push_base)"
      if [ -n "${before_sha}" ] && [ "${before_sha}" != "0000000000000000000000000000000000000000" ]; then
        ensure_git_history
        if ! git cat-file -e "${before_sha}^{commit}" >/dev/null 2>&1; then
          git fetch --no-tags origin "${before_sha}" >/dev/null 2>&1 || true
        fi
        git diff --name-only --diff-filter=ACMR "${before_sha}" "${GITHUB_SHA}" -- '*.xml'
        exit
      fi
    fi

    git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "${GITHUB_SHA}" -- '*.xml'
  ) | sed '/^$/d' > "${CHANGED_FILE_LIST}"
}

write_changed_files_api() {
  mkdir -p "$(dirname "${CHANGED_FILE_LIST}")"
  : > "${CHANGED_FILE_LIST}"

  if [ "${GITHUB_EVENT_NAME}" = "pull_request" ] || [ "${GITHUB_EVENT_NAME}" = "pull_request_target" ]; then
    api_pull_request_changed_files > "${CHANGED_FILE_LIST}" || return 1
    return
  fi

  if [ "${GITHUB_EVENT_NAME}" = "push" ]; then
    api_push_changed_files > "${CHANGED_FILE_LIST}" || return 1
    return
  fi

  echo "XML Model Validator changed_only API mode is not supported for event '${GITHUB_EVENT_NAME}'." >&2
  return 1
}

write_changed_files() {
  changed_source="${XML_MODEL_VALIDATOR_INPUT_CHANGED_SOURCE:-auto}"
  case "${changed_source}" in
    auto)
      if write_changed_files_api; then
        return
      fi
      echo "XML Model Validator API changed file discovery failed; falling back to git diff." >&2
      write_changed_files_git
      ;;
    api)
      write_changed_files_api
      ;;
    git)
      write_changed_files_git
      ;;
    *)
      echo "XML Model Validator invalid changed_source '${changed_source}'. Expected one of: auto, api, git." >&2
      exit 1
      ;;
  esac
}

if [ -n "${XML_MODEL_VALIDATOR_INPUT_SCHEMA_ALIASES:-}" ]; then
  set -- "$@" --schema-aliases "${XML_MODEL_VALIDATOR_INPUT_SCHEMA_ALIASES}"
fi

if [ "${XML_MODEL_VALIDATOR_INPUT_FAIL_FAST:-false}" = "true" ]; then
  set -- "$@" --fail-fast
fi

set -- "$@" -j "${XML_MODEL_VALIDATOR_INPUT_JOBS:-0}"

if [ -n "${XML_MODEL_VALIDATOR_INPUT_DIRECTORY:-}" ]; then
  set -- "$@" --directory "${XML_MODEL_VALIDATOR_INPUT_DIRECTORY}"
elif [ -n "${XML_MODEL_VALIDATOR_INPUT_FILE_LIST:-}" ]; then
  set -- "$@" --file-list "${XML_MODEL_VALIDATOR_INPUT_FILE_LIST}"
elif [ -n "${XML_MODEL_VALIDATOR_INPUT_FILES:-}" ]; then
  # shellcheck disable=SC2086
  set -- "$@" ${XML_MODEL_VALIDATOR_INPUT_FILES}
elif [ "${XML_MODEL_VALIDATOR_INPUT_CHANGED_ONLY:-false}" = "true" ]; then
  write_changed_files
  set -- "$@" --file-list "${CHANGED_FILE_LIST}"
else
  set -- "$@" --directory "."
fi

exec "$@"
