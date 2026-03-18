#!/bin/sh
set -eu

export XML_MODEL_VALIDATOR_WORKSPACE="${XML_MODEL_VALIDATOR_WORKSPACE:-${GITHUB_WORKSPACE:-/github/workspace}}"
export XML_MODEL_VALIDATOR_CACHE_HOME="${XML_MODEL_VALIDATOR_CACHE_HOME:-${HOME:-${PWD}}/.cache/xml-model-validator}"

ACTION_ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
CACHE_ROOT="${XML_MODEL_VALIDATOR_CACHE_HOME}"
RELEASE_CACHE_ROOT="${CACHE_ROOT}/releases"
JAR_PATH="${RELEASE_CACHE_ROOT}/xml-model-validator.jar"
CHANGED_FILE_LIST="${RUNNER_TEMP:-${ACTION_ROOT}/.cache}/xml-model-validator-changed-files.txt"

api_get() {
  if [ -n "${XML_MODEL_VALIDATOR_GITHUB_TOKEN:-}" ]; then
    curl -fsSL \
      -H "Accept: application/vnd.github+json" \
      -H "Authorization: Bearer ${XML_MODEL_VALIDATOR_GITHUB_TOKEN}" \
      "$1"
  else
    curl -fsSL \
      -H "Accept: application/vnd.github+json" \
      "$1"
  fi
}

resolve_release_json() {
  repository="${XML_MODEL_VALIDATOR_ACTION_REPOSITORY:-}"
  if [ -z "${repository}" ]; then
    echo "XML Model Validator could not determine the action repository." >&2
    exit 1
  fi

  requested_version="${XML_MODEL_VALIDATOR_INPUT_VERSION:-${XML_MODEL_VALIDATOR_ACTION_REF:-}}"
  requested_version="${requested_version#refs/tags/}"

  if [ -n "${requested_version}" ]; then
    release_json="$(api_get "https://api.github.com/repos/${repository}/releases/tags/${requested_version}" || true)"
    if [ -n "${release_json}" ]; then
      printf '%s\n' "${release_json}"
      return
    fi
  fi

  api_get "https://api.github.com/repos/${repository}/releases/latest"
}

download_release_jar() {
  mkdir -p "${RELEASE_CACHE_ROOT}"

  if [ -f "${ACTION_ROOT}/target/xml-model-validator.jar" ]; then
    cp "${ACTION_ROOT}/target/xml-model-validator.jar" "${JAR_PATH}"
    return
  fi

  release_json="$(resolve_release_json)"
  download_url="$(printf '%s\n' "${release_json}" | sed -n 's/.*"browser_download_url":[[:space:]]*"\([^"]*\/xml-model-validator\.jar\)".*/\1/p' | head -n 1)"
  if [ -z "${download_url}" ]; then
    echo "XML Model Validator could not find a release asset named xml-model-validator.jar." >&2
    exit 1
  fi

  curl -fsSL -o "${JAR_PATH}" "${download_url}"
}

download_release_jar

set -- java -jar "${JAR_PATH}"

ensure_git_history() {
  if [ "$(git rev-parse --is-shallow-repository 2>/dev/null || printf 'false')" = "true" ]; then
    git fetch --no-tags --prune --unshallow origin >/dev/null 2>&1 || true
  fi
}

resolve_push_base() {
  if [ -z "${GITHUB_EVENT_PATH:-}" ] || [ ! -f "${GITHUB_EVENT_PATH}" ]; then
    return
  fi
  sed -n 's/.*"before"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{40\}\)".*/\1/p' "${GITHUB_EVENT_PATH}" | head -n 1
}

write_changed_files() {
  workspace="${XML_MODEL_VALIDATOR_WORKSPACE}"
  if [ ! -d "${workspace}/.git" ]; then
    echo "XML Model Validator changed_only mode requires a checked-out git repository." >&2
    exit 1
  fi

  mkdir -p "$(dirname "${CHANGED_FILE_LIST}")"
  : > "${CHANGED_FILE_LIST}"

  (
    cd "${workspace}"

    event_name="${GITHUB_EVENT_NAME:-}"
    if [ "${event_name}" = "pull_request" ] || [ "${event_name}" = "pull_request_target" ]; then
      base_ref="${GITHUB_BASE_REF:-}"
      if [ -z "${base_ref}" ]; then
        echo "XML Model Validator could not determine GITHUB_BASE_REF for pull request validation." >&2
        exit 1
      fi

      ensure_git_history
      git fetch --no-tags origin "${base_ref}:refs/remotes/origin/${base_ref}" >/dev/null 2>&1 || true
      git diff --name-only --diff-filter=ACMR "origin/${base_ref}...${GITHUB_SHA:-HEAD}" -- '*.xml'
      exit
    fi

    if [ "${event_name}" = "push" ]; then
      before_sha="$(resolve_push_base)"
      after_sha="${GITHUB_SHA:-HEAD}"
      if [ -n "${before_sha}" ] && [ "${before_sha}" != "0000000000000000000000000000000000000000" ]; then
        ensure_git_history
        if ! git cat-file -e "${before_sha}^{commit}" >/dev/null 2>&1; then
          git fetch --no-tags origin "${before_sha}" >/dev/null 2>&1 || true
        fi
        git diff --name-only --diff-filter=ACMR "${before_sha}" "${after_sha}" -- '*.xml'
        exit
      fi

      git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "${after_sha}" -- '*.xml'
      exit
    fi

    git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "${GITHUB_SHA:-HEAD}" -- '*.xml'
  ) | sed '/^$/d' > "${CHANGED_FILE_LIST}"
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
