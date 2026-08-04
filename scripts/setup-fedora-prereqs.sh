#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: setup-fedora-prereqs.sh [options]

Installs the Fedora prerequisites for the EmergentTerrain Spring app and the
monorepo GPU engine, initializes PostgreSQL, and creates the
default app databases used by the normal and perf profiles.

Options:
  --postgres-password PASSWORD   Password to set on the postgres user.
                                 Default: NewStrongPasswordHere
  --app-db NAME                  Default app database. Default: emergentterrain
  --perf-db NAME                 Perf profile database. Default: emergentterrain_perf
  --gpu-repo PATH                Rust GPU repo path. Default: engines/gpu-engine in this monorepo
  --skip-packages                Skip dnf package installation
  --skip-postgres                Skip PostgreSQL init/start/password/database setup
  --skip-node-deps               Skip npm install in the Java repo
  --skip-playwright              Skip npx playwright install chromium
  --skip-verify                  Skip final toolchain verification output
  -h, --help                     Show this help
EOF
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

sql_escape_literal() {
  printf "%s" "${1//\'/\'\'}"
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

postgres_password="NewStrongPasswordHere"
app_db="emergentterrain"
perf_db="emergentterrain_perf"
gpu_repo="${GPU_REPO:-$repo_root/engines/gpu-engine}"
skip_packages=0
skip_postgres=0
skip_node_deps=0
skip_playwright=0
skip_verify=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --postgres-password)
      [[ $# -ge 2 ]] || fail "--postgres-password requires a value"
      postgres_password="$2"
      shift 2
      ;;
    --app-db)
      [[ $# -ge 2 ]] || fail "--app-db requires a value"
      app_db="$2"
      shift 2
      ;;
    --perf-db)
      [[ $# -ge 2 ]] || fail "--perf-db requires a value"
      perf_db="$2"
      shift 2
      ;;
    --gpu-repo)
      [[ $# -ge 2 ]] || fail "--gpu-repo requires a value"
      gpu_repo="$2"
      shift 2
      ;;
    --skip-packages)
      skip_packages=1
      shift
      ;;
    --skip-postgres)
      skip_postgres=1
      shift
      ;;
    --skip-node-deps)
      skip_node_deps=1
      shift
      ;;
    --skip-playwright)
      skip_playwright=1
      shift
      ;;
    --skip-verify)
      skip_verify=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

require_command sudo
require_command dnf

if [[ -r /etc/os-release ]]; then
  . /etc/os-release
  [[ "${ID:-}" == "fedora" ]] || fail "This script is intended for Fedora. Detected ID=${ID:-unknown}."
fi

[[ -d "$repo_root" ]] || fail "Java repo path not found: $repo_root"
[[ -d "$gpu_repo" ]] || fail "GPU repo path not found: $gpu_repo"

java_home="/usr/lib/jvm/java-21-openjdk"
java_package="java-21-openjdk-devel"
# Fedora 44 no longer publishes the native Java 21 package. When the operator has
# installed the official Temurin JDK, keep the helper from trying to install the
# unavailable OpenJDK package and print the matching project JAVA_HOME instead.
if [[ -x /usr/lib/jvm/java-21-temurin-jdk/bin/java ]]; then
  java_home="/usr/lib/jvm/java-21-temurin-jdk"
  java_package=""
fi

packages=(
  maven
  postgresql-server
  postgresql
  rust
  cargo
  protobuf-compiler
  vulkan-loader
  vulkan-tools
  mesa-vulkan-drivers
  clang
  nodejs
  npm
  jq
  curl
  powershell
)

if [[ -n "$java_package" ]]; then
  packages=("$java_package" "${packages[@]}")
fi

if [[ $skip_packages -eq 0 ]]; then
  echo "Installing Fedora packages..."
  sudo dnf install -y "${packages[@]}"
fi

require_command java
require_command mvn
require_command cargo
require_command rustc
require_command protoc
require_command psql
require_command npm
require_command jq
require_command curl
require_command pwsh
require_command vulkaninfo

if [[ $skip_postgres -eq 0 ]]; then
  echo "Configuring PostgreSQL..."
  if [[ ! -f /var/lib/pgsql/data/PG_VERSION ]]; then
    sudo postgresql-setup --initdb
  fi

  sudo systemctl enable --now postgresql

  escaped_password="$(sql_escape_literal "$postgres_password")"
  sudo -u postgres psql -v ON_ERROR_STOP=1 -d postgres \
    -c "ALTER USER postgres WITH PASSWORD '$escaped_password';"

  for db_name in "$app_db" "$perf_db"; do
    if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$db_name'" | grep -q 1; then
      sudo -u postgres createdb "$db_name"
    fi
  done
fi

if [[ $skip_node_deps -eq 0 && -f "$repo_root/package.json" ]]; then
  echo "Installing npm dependencies..."
  (
    cd "$repo_root"
    npm install
  )
fi

if [[ $skip_playwright -eq 0 && -f "$repo_root/package.json" ]]; then
  echo "Installing Playwright Chromium..."
  (
    cd "$repo_root"
    npx playwright install chromium
  )
fi

if [[ $skip_verify -eq 0 ]]; then
  echo
  echo "Verification:"
  java -version
  mvn -version
  rustc --version
  cargo --version
  protoc --version
  psql --version
  node --version
  npm --version
  pwsh --version
  vulkaninfo --summary | sed -n '1,40p'
  if [[ $skip_postgres -eq 0 ]]; then
    pg_isready -h localhost -p 5432
  fi
fi

cat <<EOF

Fedora setup complete.

Next steps:
  1. Start the GPU engine:
     cd $gpu_repo
     cargo run

  2. Start the Spring app:
     export JAVA_HOME=$java_home
     export PATH="\$JAVA_HOME/bin:\$PATH"
     cd $repo_root
     mvn -f apps/game-service/pom.xml spring-boot:run

  3. Open:
     http://127.0.0.1:8080/game/test

Optional checks:
  cd $repo_root
  bash ./scripts/run-real-gpu-e2e.sh
EOF
