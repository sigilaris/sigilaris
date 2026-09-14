#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Override every build resolver; omit Ivy Local and Maven Local entirely.
# A dedicated cache also makes previously staged copies unavailable.
baseline_dir="$(pwd)/target/public-m2-baseline"
mkdir -p "$baseline_dir"
export COURSIER_CACHE="$baseline_dir/coursier"
export COURSIER_REPOSITORIES="https://repo.maven.apache.org/maven2"
for artifact in sigilaris-core_3 sigilaris-core_sjs1_3 sigilaris-node-common_3 sigilaris-node-common_sjs1_3 sigilaris-node-jvm_3; do
  mkdir -p "$baseline_dir/artifacts/$artifact/0.3.0-M2"
  for extension in pom jar; do
    relative="$artifact/0.3.0-M2/$artifact-0.3.0-M2.$extension"
    curl --fail --silent --show-error --location \
      "https://repo.maven.apache.org/maven2/org/sigilaris/$relative" \
      --output "$baseline_dir/artifacts/$relative"
  done
done
(cd "$baseline_dir/artifacts" && shasum -a 256 -c "../../../fixtures/m2-checksums.sha256")
npm ci --ignore-scripts
sbt -Dsbt.override.build.repos=true \
  -Dsbt.repository.config="$(pwd)/project/public-repositories" \
  -Dsbt.global.base="$baseline_dir/sbt-global" \
  'jvm/clean' 'js/clean' \
  'jvm/verifyM2PublicArtifacts' 'js/verifyM2PublicArtifacts' \
  'jvm/run' 'js/run' 'js/fullLinkJS'
node js/target/scala-3.7.3/js-opt/main.js
