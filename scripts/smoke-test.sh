#!/bin/sh
# Run an end-to-end smoke test of entrypoint.sh against a minimal fixture repository.
# Usage: smoke-test.sh <project-root>
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
PROJECT_ROOT="${1:-$(dirname "${SCRIPT_DIR}")}"

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

# Set up a minimal git repository containing one valid XML file.
repo="${tmpdir}/repo"
mkdir -p "${repo}"
cd "${repo}"
git init -q
git config user.name "CI"
git config user.email "ci@example.com"
git config commit.gpgsign false

cat > "${repo}/schema.rng" <<'EOF'
<grammar xmlns="http://relaxng.org/ns/structure/1.0">
  <start>
    <element name="root">
      <empty/>
    </element>
  </start>
</grammar>
EOF

cat > "${repo}/valid.xml" <<'EOF'
<?xml version="1.0"?>
<?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
<root/>
EOF

git add schema.rng valid.xml
git commit -qm "Initial XML"

# Pre-populate the JAR cache with the already-built artifact so entrypoint.sh
# does not attempt a nested Gradle invocation.
mkdir -p "${tmpdir}/cache/jar"
cp "${PROJECT_ROOT}/build/libs/xml-model-validator.jar" \
    "${tmpdir}/cache/jar/xml-model-validator.jar"

# Provide RUNNER_TEMP for entrypoint.sh variables that reference it, even
# though they are not written in the directory-scan mode used here.
export RUNNER_TEMP="${tmpdir}/runner-temp"

XML_MODEL_VALIDATOR_WORKSPACE="${repo}" \
XML_MODEL_VALIDATOR_CACHE_HOME="${tmpdir}/cache" \
XML_MODEL_VALIDATOR_INPUT_JOBS="0" \
bash "${PROJECT_ROOT}/entrypoint.sh"
