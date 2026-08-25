#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
release_root="$(cd "$script_dir/.." && pwd)"
application_jar="$release_root/app/emergent-terrain-app.jar"
library_dir="$release_root/lib"
main_class="com.emergentterrain.EmergentTerrainApplication"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [[ ! -f "$application_jar" ]]; then
  echo "Release application JAR is missing: $application_jar" >&2
  exit 1
fi
if [[ ! -d "$library_dir" ]] || ! compgen -G "$library_dir/*.jar" > /dev/null; then
  echo "Release dependency directory is missing or empty: $library_dir" >&2
  exit 1
fi

# The ordinary Java classpath deliberately keeps every dependency as a separate,
# replaceable JAR. Do not shade, relocate, or merge the third-party libraries.
read -r -a java_options <<< "${JAVA_OPTS:-}"
cd "$release_root"
exec "$java_bin" "${java_options[@]}" -cp "$application_jar:$library_dir/*" "$main_class" "$@"
