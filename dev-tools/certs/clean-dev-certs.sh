#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Remove generated certificates so tooling can start from scratch without
# deleting the tracked helper scripts in dev-tools/certs/.
TARGET="${1:-}"
if [ -n "$TARGET" ]; then
  CERT_DIR="$TARGET"
else
  CERT_DIR="${CERT_DIR:-$SCRIPT_DIR}"
fi

refuse_symlink_path() {
  local path="$1"
  local description="$2"
  local current_path="$path"

  while [[ "$current_path" == */ && "$current_path" != "/" ]]; do
    current_path="${current_path%/}"
  done

  while [[ "$current_path" != "." && "$current_path" != "/" ]]; do
    [[ ! -L "$current_path" ]] || {
      echo "Refusing symlinked $description; preserving it: $path" >&2
      exit 1
    }
    current_path="$(dirname -- "$current_path")"
  done
}

refuse_symlink_path "$CERT_DIR" "certificate directory"
if [ ! -d "$CERT_DIR" ]; then
  echo "No certificate directory at $CERT_DIR"
  exit 0
fi

removed_any=false
preserved_any=false

report_preserved() {
  echo "Preserving unexpected certificate entry: $1" >&2
  preserved_any=true
}

remove_known_file() {
  local path="$1"
  if [[ -L "$path" ]]; then
    report_preserved "$path (symlink)"
  elif [[ -f "$path" ]]; then
    rm -- "$path"
    removed_any=true
  elif [[ -e "$path" ]]; then
    report_preserved "$path (not a regular file)"
  fi
}

report_directory_contents() {
  local directory="$1"
  local path
  while IFS= read -r -d '' path; do
    report_preserved "$path"
  done < <(find "$directory" -mindepth 1 -print0)
}

remove_empty_directory() {
  local directory="$1"
  if [[ -L "$directory" ]]; then
    report_preserved "$directory (symlink directory)"
  elif [[ -d "$directory" ]]; then
    if rmdir -- "$directory" 2>/dev/null; then
      removed_any=true
    else
      preserved_any=true
      echo "Preserving non-empty or inaccessible certificate directory: $directory" >&2
      report_directory_contents "$directory"
    fi
  elif [[ -e "$directory" ]]; then
    report_preserved "$directory (not a directory)"
  fi
}

generated_files=(
  ca.crt
  ca.key
  ca.srl
  client.crt
  client.key
  dev-ca.pem
  dev-cert.pem
  dev-key.pem
  server.crt
  server.key
  server.csr
  dev-cert.cnf
)

for filename in "${generated_files[@]}"; do
  remove_known_file "$CERT_DIR/$filename"
done

workloads=(account-service game-session-service social-groups-service)
workload_dir="$CERT_DIR/workloads"
if [[ -L "$workload_dir" ]]; then
  report_preserved "$workload_dir (symlink directory)"
elif [[ -d "$workload_dir" ]]; then
  for workload in "${workloads[@]}"; do
    remove_known_file "$workload_dir/$workload.crt"
    remove_known_file "$workload_dir/$workload.key"
  done
  remove_empty_directory "$workload_dir"
elif [[ -e "$workload_dir" ]]; then
  report_preserved "$workload_dir (not a directory)"
fi

runtime_root="$CERT_DIR/local-runtime"
runtime_files=(ca.crt client.crt client.key server.crt server.key dev-ca.pem dev-cert.pem dev-key.pem)
if [[ -L "$runtime_root" ]]; then
  report_preserved "$runtime_root (symlink directory)"
elif [[ -d "$runtime_root" ]]; then
  profiles=(default "${workloads[@]}")
  for profile in "${profiles[@]}"; do
    profile_dir="$runtime_root/$profile"
    if [[ -L "$profile_dir" ]]; then
      report_preserved "$profile_dir (symlink directory)"
      continue
    elif [[ ! -d "$profile_dir" ]]; then
      if [[ -e "$profile_dir" ]]; then
        report_preserved "$profile_dir (not a directory)"
      fi
      continue
    fi

    if [[ "$profile" != "default" ]]; then
      leaf_dir="$profile_dir/workloads"
      if [[ -L "$leaf_dir" ]]; then
        report_preserved "$leaf_dir (symlink directory)"
      elif [[ -d "$leaf_dir" ]]; then
        remove_known_file "$leaf_dir/$profile.crt"
        remove_known_file "$leaf_dir/$profile.key"
        remove_empty_directory "$leaf_dir"
      elif [[ -e "$leaf_dir" ]]; then
        report_preserved "$leaf_dir (not a directory)"
      fi
    fi

    for filename in "${runtime_files[@]}"; do
      remove_known_file "$profile_dir/$filename"
    done
    remove_empty_directory "$profile_dir"
  done
  remove_empty_directory "$runtime_root"
elif [[ -e "$runtime_root" ]]; then
  report_preserved "$runtime_root (not a directory)"
fi

if [ "$removed_any" = true ]; then
  echo "Removed known generated certificates from $CERT_DIR"
fi
if [ "$preserved_any" = true ]; then
  echo "Unexpected entries were preserved; inspect the paths reported above."
elif [ "$removed_any" = false ]; then
  echo "No generated certificates found in $CERT_DIR"
fi
