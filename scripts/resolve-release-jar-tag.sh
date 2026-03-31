#!/bin/sh
set -eu

action_ref="${XML_MODEL_VALIDATOR_ACTION_REF:-}"

case "${action_ref}" in
  v[0-9]*.[0-9]*.[0-9]*)
    printf 'release_tag=%s\n' "${action_ref}" >> "${GITHUB_OUTPUT}"
    ;;
  v[0-9]*)
    project_version="$(./mvnw -q -Dexpression=project.version -DforceStdout help:evaluate)"
    if [ "${action_ref#v}" != "${project_version%%.*}" ]; then
      echo "XML Model Validator: action ref ${action_ref} does not match Maven project major version ${project_version}; falling back to a source build." >&2
      exit 0
    fi
    printf 'release_tag=v%s\n' "${project_version}" >> "${GITHUB_OUTPUT}"
    ;;
  *)
    echo "XML Model Validator: action ref ${action_ref} is not a supported release ref; falling back to a source build." >&2
    ;;
esac
