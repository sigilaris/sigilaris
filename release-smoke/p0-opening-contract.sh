#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# This policy oracle uses public M2 primitives, not a production V2 runtime.
opening_cache="$(pwd)/target/public-m2-baseline"
mkdir -p "$opening_cache"
export COURSIER_CACHE="$opening_cache/coursier"
export COURSIER_REPOSITORIES="https://repo.maven.apache.org/maven2"
npm ci --ignore-scripts
sbt -Dsbt.override.build.repos=true \
  -Dsbt.repository.config="$(pwd)/project/public-repositories" \
  -Dsbt.global.base="$opening_cache/sbt-global" \
  'jvm/verifyM2PublicArtifacts' 'js/verifyM2PublicArtifacts' \
  'jvm/runMain P0OpeningContract' \
  'set js / Compile / mainClass := Some("P0OpeningContract")' \
  'js/run' 'js/fullLinkJS'
node js/target/scala-3.7.3/js-opt/main.js
