#!/bin/sh
set -eu

export XML_MODEL_VALIDATOR_WORKSPACE="${XML_MODEL_VALIDATOR_WORKSPACE:-${GITHUB_WORKSPACE}}"
export XML_MODEL_VALIDATOR_CACHE_HOME="${XML_MODEL_VALIDATOR_CACHE_HOME:-${HOME}/.cache/xml-model-validator}"

ACTION_ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
JAR_CACHE_DIR="${XML_MODEL_VALIDATOR_CACHE_HOME}/jar"
JAR_PATH="${JAR_CACHE_DIR}/xml-model-validator.jar"
CHANGED_FILE_LIST="${RUNNER_TEMP}/xml-model-validator-changed-files.txt"
SUMMARY_JSON_FILE="${RUNNER_TEMP}/xml-model-validator-summary.json"
JSON_REPORT_DESTINATION=""

mkdir -p \
  "${RUNNER_TEMP}" \
  "${XML_MODEL_VALIDATOR_CACHE_HOME}/jar" \
  "${XML_MODEL_VALIDATOR_CACHE_HOME}/schema-downloads" \
  "${XML_MODEL_VALIDATOR_CACHE_HOME}/schematron"

build_validator_jar() {
  echo "XML Model Validator: building from source..." >&2
  (cd "${ACTION_ROOT}" && gradle -q jar -x test)
  cp "${ACTION_ROOT}/build/libs/xml-model-validator.jar" "${JAR_PATH}"
}

download_validator_jar() {
  if [ -z "${GITHUB_ACTION_REF:-}" ] || [ -z "${GITHUB_ACTION_REPOSITORY:-}" ]; then
    return 1
  fi
  gh release download "${GITHUB_ACTION_REF}" \
    --repo "${GITHUB_ACTION_REPOSITORY}" \
    --pattern "xml-model-validator.jar" \
    --dir "${JAR_CACHE_DIR}" \
    --clobber 2>/dev/null
}

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
    --jq '.[] | select(.status != "removed") | .filename' || return 1
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
  printf '%s\n' "${all_files}" | sed '/^$/d'
}

write_changed_files_git() {
  mkdir -p "$(dirname "${CHANGED_FILE_LIST}")"

  (
    cd "${XML_MODEL_VALIDATOR_WORKSPACE}"

    if [ "${GITHUB_EVENT_NAME}" = "pull_request" ] || [ "${GITHUB_EVENT_NAME}" = "pull_request_target" ]; then
      ensure_git_history
      git fetch --no-tags origin "${GITHUB_BASE_REF}:refs/remotes/origin/${GITHUB_BASE_REF}" >/dev/null 2>&1 || true
      git diff --name-only --diff-filter=ACMR "origin/${GITHUB_BASE_REF}...${GITHUB_SHA}"
      exit
    fi

    if [ "${GITHUB_EVENT_NAME}" = "push" ]; then
      before_sha="$(resolve_push_base)"
      if [ -n "${before_sha}" ] && [ "${before_sha}" != "0000000000000000000000000000000000000000" ]; then
        ensure_git_history
        if ! git cat-file -e "${before_sha}^{commit}" >/dev/null 2>&1; then
          git fetch --no-tags origin "${before_sha}" >/dev/null 2>&1 || true
        fi
        git diff --name-only --diff-filter=ACMR "${before_sha}" "${GITHUB_SHA}"
        exit
      fi
    fi

    git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "${GITHUB_SHA}"
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

  echo "XML Model Validator changed_files_only API mode is not supported for event '${GITHUB_EVENT_NAME}'." >&2
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

normalize_extension_token() {
  extension="$1"
  extension="$(printf '%s' "${extension}" | tr '[:upper:]' '[:lower:]')"
  case "${extension}" in
    .*) printf '%s' "${extension}" ;;
    *) printf '.%s' "${extension}" ;;
  esac
}

effective_file_extensions() {
  if [ -n "${XML_MODEL_VALIDATOR_INPUT_FILE_EXTENSIONS:-}" ]; then
    printf '%s\n' "${XML_MODEL_VALIDATOR_INPUT_FILE_EXTENSIONS}" \
      | tr ',[:space:]' '\n\n' \
      | sed '/^$/d' \
      | while IFS= read -r extension; do
          normalize_extension_token "${extension}"
        done
    return
  fi

  printf '%s\n' ".xml"
  if [ -n "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_EXTENSION:-}" ]; then
    normalize_extension_token "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_EXTENSION}"
  fi
}

effective_file_extensions_inline() {
  effective_file_extensions | awk '!seen[$0]++' | paste -sd' ' -
}

filter_changed_files_by_extension() {
  temp_filtered="${CHANGED_FILE_LIST}.filtered"
  effective_extensions="$(effective_file_extensions_inline)"

  awk -v extensions="${effective_extensions}" '
    BEGIN {
      split(extensions, values, " ")
      for (extension_index in values) {
        allowed[values[extension_index]] = 1
      }
    }
    {
      filename = tolower($0)
      for (extension in allowed) {
        if (extension != "" && length(filename) >= length(extension) && substr(filename, length(filename) - length(extension) + 1) == extension) {
          print $0
          next
        }
      }
    }
  ' "${CHANGED_FILE_LIST}" > "${temp_filtered}"

  mv "${temp_filtered}" "${CHANGED_FILE_LIST}"
}

count_selection_inputs() {
  count=0

  if [ -n "${XML_MODEL_VALIDATOR_INPUT_DIRECTORY:-}" ]; then
    count=$((count + 1))
  fi
  if [ -n "${XML_MODEL_VALIDATOR_INPUT_FILES_FROM:-}" ]; then
    count=$((count + 1))
  fi
  if [ -n "${XML_MODEL_VALIDATOR_INPUT_FILES:-}" ]; then
    count=$((count + 1))
  fi
  if [ "${XML_MODEL_VALIDATOR_INPUT_CHANGED_FILES_ONLY:-false}" = "true" ]; then
    count=$((count + 1))
  fi

  printf '%s' "${count}"
}

describe_selection_inputs() {
  selections=""

  if [ -n "${XML_MODEL_VALIDATOR_INPUT_DIRECTORY:-}" ]; then
    selections="${selections} directory=${XML_MODEL_VALIDATOR_INPUT_DIRECTORY}"
  fi
  if [ -n "${XML_MODEL_VALIDATOR_INPUT_FILES_FROM:-}" ]; then
    selections="${selections} files_from=${XML_MODEL_VALIDATOR_INPUT_FILES_FROM}"
  fi
  if [ -n "${XML_MODEL_VALIDATOR_INPUT_FILES:-}" ]; then
    selections="${selections} files=<multiline>"
  fi
  if [ "${XML_MODEL_VALIDATOR_INPUT_CHANGED_FILES_ONLY:-false}" = "true" ]; then
    selections="${selections} changed_files_only=true"
  fi

  printf '%s' "${selections# }"
}

append_step_summary() {
  if [ -z "${GITHUB_STEP_SUMMARY:-}" ]; then
    return
  fi

  cat >> "${GITHUB_STEP_SUMMARY}" <<'EOF'
## XML Validation

Validation was skipped because no changed files matched the configured extensions.

EOF

  if [ -n "${XML_MODEL_VALIDATOR_SUMMARY_SELECTION:-}" ] || [ -n "${XML_MODEL_VALIDATOR_SUMMARY_CONFIG:-}" ] || [ -n "${XML_MODEL_VALIDATOR_SUMMARY_FILE_EXTENSIONS:-}" ]; then
    {
      printf '%s\n' "### Run Context"
      printf '\n'
      printf '%s\n' "| Setting | Value |"
      printf '%s\n' "| --- | --- |"
      if [ -n "${XML_MODEL_VALIDATOR_SUMMARY_SELECTION:-}" ]; then
        printf '| %s | %s |\n' "Selection" "${XML_MODEL_VALIDATOR_SUMMARY_SELECTION}"
      fi
      if [ -n "${XML_MODEL_VALIDATOR_SUMMARY_CONFIG:-}" ]; then
        printf '| %s | `%s` |\n' "Config" "${XML_MODEL_VALIDATOR_SUMMARY_CONFIG}"
      fi
      if [ -n "${XML_MODEL_VALIDATOR_SUMMARY_FILE_EXTENSIONS:-}" ]; then
        printf '| %s | `%s` |\n' "File extensions" "${XML_MODEL_VALIDATOR_SUMMARY_FILE_EXTENSIONS}"
      fi
      printf '\n'
    } >> "${GITHUB_STEP_SUMMARY}"
  fi
}

append_action_outputs() {
  if [ -z "${GITHUB_OUTPUT:-}" ]; then
    return
  fi

  {
    printf '%s\n' "skipped=$1"
    printf '%s\n' "files_checked=$2"
    printf '%s\n' "failed_files=$3"
    printf '%s\n' "warning_count=$4"
    printf '%s\n' "json_report_path=$5"
  } >> "${GITHUB_OUTPUT}"
}

resolve_json_report_destination() {
  if [ -z "${XML_MODEL_VALIDATOR_INPUT_JSON_REPORT_PATH:-}" ]; then
    return
  fi

  case "${XML_MODEL_VALIDATOR_INPUT_JSON_REPORT_PATH}" in
    /*)
      JSON_REPORT_DESTINATION="${XML_MODEL_VALIDATOR_INPUT_JSON_REPORT_PATH}"
      ;;
    *)
      JSON_REPORT_DESTINATION="${XML_MODEL_VALIDATOR_WORKSPACE}/${XML_MODEL_VALIDATOR_INPUT_JSON_REPORT_PATH}"
      ;;
  esac
}

write_skipped_summary_json() {
  if [ -z "${XML_MODEL_VALIDATOR_SUMMARY_FILE:-}" ]; then
    return
  fi

  printf '%s\n' '{"summary":{"skipped":true,"filesChecked":0,"okFiles":0,"failedFiles":0,"warningCount":0,"elapsedSeconds":0.0},"results":[]}' > "${XML_MODEL_VALIDATOR_SUMMARY_FILE}"
}

persist_json_report() {
  if [ -z "${JSON_REPORT_DESTINATION}" ] || [ ! -f "${SUMMARY_JSON_FILE}" ]; then
    return
  fi

  mkdir -p "$(dirname "${JSON_REPORT_DESTINATION}")"
  cp "${SUMMARY_JSON_FILE}" "${JSON_REPORT_DESTINATION}"
}

set -- java -jar "${JAR_PATH}"

resolve_json_report_destination

if [ -n "${JSON_REPORT_DESTINATION}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_JSON_REPORT_PATH="${JSON_REPORT_DESTINATION}"
else
  unset XML_MODEL_VALIDATOR_SUMMARY_JSON_REPORT_PATH
fi

if [ -n "${GITHUB_OUTPUT:-}" ] || [ -n "${JSON_REPORT_DESTINATION}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_FILE="${SUMMARY_JSON_FILE}"
  rm -f "${SUMMARY_JSON_FILE}"
else
  unset XML_MODEL_VALIDATOR_SUMMARY_FILE
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_CONFIG:-}" ]; then
  set -- "$@" --config "${XML_MODEL_VALIDATOR_INPUT_CONFIG}"
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_MODE:-}" ]; then
  set -- "$@" --rule-mode "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_MODE}"
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_DIRECTORY:-}" ]; then
  set -- "$@" --rule-directory "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_DIRECTORY}"
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_EXTENSION:-}" ]; then
  set -- "$@" --rule-extension "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_EXTENSION}"
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_DECLARATIONS:-}" ]; then
  while IFS= read -r declaration; do
    if [ -n "${declaration}" ]; then
      set -- "$@" --xml-model-declaration "${declaration}"
    fi
  done <<EOF
${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_DECLARATIONS}
EOF
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_FILE_EXTENSIONS:-}" ]; then
  set -- "$@" --file-extensions "${XML_MODEL_VALIDATOR_INPUT_FILE_EXTENSIONS}"
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_CONFIG:-}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_CONFIG="${XML_MODEL_VALIDATOR_INPUT_CONFIG}"
else
  export XML_MODEL_VALIDATOR_SUMMARY_CONFIG=".xml-validator/config.toml"
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_FILE_EXTENSIONS:-}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_FILE_EXTENSIONS="${XML_MODEL_VALIDATOR_INPUT_FILE_EXTENSIONS}"
else
  export XML_MODEL_VALIDATOR_SUMMARY_FILE_EXTENSIONS="$(effective_file_extensions_inline)"
fi

if [ "${XML_MODEL_VALIDATOR_INPUT_FAIL_FAST:-false}" = "true" ]; then
  set -- "$@" --fail-fast
fi

if [ "${XML_MODEL_VALIDATOR_INPUT_CHECK_SCHEMATRON_SCHEMA:-false}" = "true" ]; then
  set -- "$@" --check-schematron-schema
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_SCHEMATRON_SEVERITY_THRESHOLD:-}" ] && \
   [ "${XML_MODEL_VALIDATOR_INPUT_SCHEMATRON_SEVERITY_THRESHOLD}" != "INFO" ]; then
  set -- "$@" --schematron-severity-threshold "${XML_MODEL_VALIDATOR_INPUT_SCHEMATRON_SEVERITY_THRESHOLD}"
fi

set -- "$@" -j "${XML_MODEL_VALIDATOR_INPUT_JOBS:-0}"

selection_count="$(count_selection_inputs)"
if [ "${selection_count}" -gt 1 ]; then
  echo "XML Model Validator: choose only one of directory, files_from, files, or changed_files_only. Received: $(describe_selection_inputs)." >&2
  exit 1
fi

if [ -n "${XML_MODEL_VALIDATOR_INPUT_DIRECTORY:-}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_SELECTION="directory:${XML_MODEL_VALIDATOR_INPUT_DIRECTORY}"
  set -- "$@" --directory "${XML_MODEL_VALIDATOR_INPUT_DIRECTORY}"
elif [ -n "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_DIRECTORY:-}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_SELECTION="directory:${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_DIRECTORY}"
  set -- "$@" --directory "${XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_DIRECTORY}"
elif [ -n "${XML_MODEL_VALIDATOR_INPUT_FILES_FROM:-}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_SELECTION="files_from:${XML_MODEL_VALIDATOR_INPUT_FILES_FROM}"
  set -- "$@" --files-from "${XML_MODEL_VALIDATOR_INPUT_FILES_FROM}"
elif [ -n "${XML_MODEL_VALIDATOR_INPUT_FILES:-}" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_SELECTION="files"
  while IFS= read -r file; do
    if [ -n "${file}" ]; then
      set -- "$@" "${file}"
    fi
  done <<EOF
${XML_MODEL_VALIDATOR_INPUT_FILES}
EOF
elif [ "${XML_MODEL_VALIDATOR_INPUT_CHANGED_FILES_ONLY:-false}" = "true" ]; then
  export XML_MODEL_VALIDATOR_SUMMARY_SELECTION="changed_files_only:${XML_MODEL_VALIDATOR_INPUT_CHANGED_SOURCE:-auto}"
  write_changed_files
  filter_changed_files_by_extension
  if [ ! -s "${CHANGED_FILE_LIST}" ]; then
    write_skipped_summary_json
    persist_json_report
    echo "::notice title=XML Validation::Validation skipped because no changed files matched the configured extensions."
    append_step_summary
    append_action_outputs "true" "0" "0" "0" "${JSON_REPORT_DESTINATION}"
    echo "XML Model Validator: no changed files matched the configured extensions; skipping validation." >&2
    exit 0
  fi
  set -- "$@" --files-from "${CHANGED_FILE_LIST}"
else
  export XML_MODEL_VALIDATOR_SUMMARY_SELECTION="directory:."
  set -- "$@" --directory "."
fi

if [ ! -f "${JAR_PATH}" ]; then
  if download_validator_jar; then
    echo "XML Model Validator: downloaded pre-built JAR for ${GITHUB_ACTION_REF:-unknown}." >&2
  else
    build_validator_jar
  fi
fi

set +e
"$@"
status=$?
set -e

if [ -n "${GITHUB_OUTPUT:-}" ] && [ -f "${SUMMARY_JSON_FILE}" ]; then
  persist_json_report
  append_action_outputs \
    "false" \
    "$(jq -r '.summary.filesChecked' "${SUMMARY_JSON_FILE}")" \
    "$(jq -r '.summary.failedFiles' "${SUMMARY_JSON_FILE}")" \
    "$(jq -r '.summary.warningCount' "${SUMMARY_JSON_FILE}")" \
    "${JSON_REPORT_DESTINATION}"
fi

exit "${status}"
