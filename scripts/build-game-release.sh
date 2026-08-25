#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
service_dir="$repo_root/apps/game-service"
release_root="$service_dir/target/release"
release_name=""

require_clean_source() {
  if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=normal)" ]]; then
    echo "Refusing to assemble a release from a dirty source tree. Commit or stash source changes first." >&2
    exit 2
  fi
}

copy_jar_notices() {
  local library_dir="$1"
  local destination="$2"
  local library entry safe_name
  mkdir -p "$destination"

  for library in "$library_dir"/*.jar; do
    [[ -f "$library" ]] || continue
    while IFS= read -r entry; do
      safe_name="$(basename "$library" .jar)--${entry//\//_}"
      unzip -p "$library" "$entry" > "$destination/$safe_name"
    done < <(unzip -Z1 "$library" | awk 'BEGIN { IGNORECASE = 1 }
      /(^|\/)(license|notice|copying)(\.[^\/]*)?$/ || /^META-INF\/.*(license|notice|copying)/ { print }')
  done
}

require_clean_source

version="$(mvn -q -f "$service_dir/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)"
release_name="EmergentTerrain-$version"
stage="$release_root/$release_name"
archive="$release_root/$release_name-distribution.zip"

rm -rf "$stage" "$archive"

# Install the reactor artifacts first so research-contract is copied into the release lib/
# directory alongside every other runtime dependency. Tests run by default; callers who have
# already completed validation may explicitly opt out of test compilation and execution with
# ET_RELEASE_SKIP_TESTS=1.
maven_args=( -f "$repo_root/pom.xml" -pl apps/game-service -am install )
if [[ "${ET_RELEASE_SKIP_TESTS:-0}" == "1" ]]; then
  maven_args+=( -Dmaven.test.skip=true )
fi
mvn "${maven_args[@]}"

mkdir -p "$stage/app" "$stage/bin" "$stage/lib" "$stage/licenses/from-jars" "$stage/sbom" "$stage/source"

mvn -q -f "$service_dir/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.7.1:copy-dependencies \
  -DincludeScope=runtime \
  -DoutputDirectory="$stage/lib"

install -m 0644 "$service_dir/target/game-service-$version.jar" "$stage/app/emergent-terrain-app.jar"
install -m 0755 "$repo_root/scripts/release-game-start.sh" "$stage/bin/start"
install -m 0644 "$repo_root/LICENSE" "$stage/licenses/EmergentTerrain-AGPL-3.0-or-later.txt"
install -m 0644 "$repo_root/THIRD_PARTY_NOTICES.md" "$stage/licenses/THIRD_PARTY_NOTICES.md"
install -m 0644 "$repo_root/DEPENDENCY_LICENSE_CHOICES.md" "$stage/licenses/DEPENDENCY_LICENSE_CHOICES.md"
install -m 0644 "$repo_root/release-licenses/GNU-GPL-3.0.txt" "$stage/licenses/GNU-GPL-3.0.txt"
install -m 0644 "$repo_root/release-licenses/GNU-LGPL-2.1.txt" "$stage/licenses/GNU-LGPL-2.1.txt"
install -m 0644 "$repo_root/release-licenses/GNU-LGPL-3.0.txt" "$stage/licenses/GNU-LGPL-3.0.txt"
install -m 0644 "$repo_root/release-licenses/README.md" "$stage/licenses/README.md"
install -m 0644 "$repo_root/sbom/maven.cdx.json" "$stage/sbom/maven.cdx.json"
install -m 0644 "$repo_root/sbom/cargo.cdx.json" "$stage/sbom/cargo.cdx.json"
install -m 0644 "$repo_root/docs/release-distribution.md" "$stage/SOURCE-AND-BUILD.md"

copy_jar_notices "$stage/lib" "$stage/licenses/from-jars"

commit="$(git -C "$repo_root" rev-parse HEAD)"
git -C "$repo_root" archive --format=tar.gz --prefix="$release_name-source/" HEAD \
  > "$stage/source/$release_name-source.tar.gz"

jar --create --file "$archive" --no-manifest -C "$release_root" "$release_name"
echo "Wrote replaceable-dependency release: $archive"
echo "Release source revision: $commit"
