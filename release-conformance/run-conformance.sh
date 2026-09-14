#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 VERSION legacy-m1|legacy-m2|v2 [HTTPS_OR_FILE_STAGING_REPOSITORY]" >&2
  exit 2
fi
artifact_version="$1"
fixture_profile="$2"
case "$artifact_version" in *[!a-zA-Z0-9._-]*|'') echo "Invalid artifact version" >&2; exit 2;; esac
case "$fixture_profile" in legacy-m1|legacy-m2|v2) ;; *) echo "Invalid fixture profile" >&2; exit 2;; esac
python3 tools/verify-source-inventory.py
python3 tools/verify-crypto-vectors.py
conformance_dir="$(pwd)/target/conformance"
mkdir -p "$conformance_dir"
export COURSIER_CACHE="$conformance_dir/coursier"
repository_file="$conformance_dir/repositories"
staging_args=("-Dsigilaris.conformance.mode=public")
evidence_mode=public
printf '%s\n' '[repositories]' > "$repository_file"
if [[ $# -eq 3 ]]; then
  staging_repository="$3"
  case "$staging_repository" in https://*|file:/*) ;; *) echo "Staging requires HTTPS or an absolute file repository URL" >&2; exit 2;; esac
  printf 'explicit-staging: %s\n' "$staging_repository" >> "$repository_file"
  evidence_mode=staging
  staging_args=("-Dsigilaris.conformance.mode=staging" "-Dsigilaris.conformance.staging=$staging_repository")
fi
printf '%s\n' 'public-central: https://repo.maven.apache.org/maven2' >> "$repository_file"
npm ci --ignore-scripts
node tools/verify-crypto-runtime.cjs . "target/identities/$fixture_profile-js-crypto.json"
sbt -Dsbt.override.build.repos=true -Dsbt.repository.config="$repository_file" \
  -Dsbt.global.base="$conformance_dir/sbt-global" \
  "-Dsigilaris.conformance.version=$artifact_version" \
  "-Dsigilaris.conformance.profile=$fixture_profile" \
  "${staging_args[@]}" \
  'jvm/clean' 'js/clean' \
  'jvm/verifyArtifactIdentities' 'js/verifyArtifactIdentities' \
  'jvm/verifyCryptoIdentities' \
  'jvm/verifySourceIdentities' 'js/verifySourceIdentities' \
  'jvm/runMain legacyRecoveryAlias' \
  'jvm/run' 'js/run' 'js/fullLinkJS'
node js/target/scala-3.7.3/js-opt/main.js
