#!/bin/sh
set -eu

refuse() {
  printf 'refusing to start PostgreSQL: %s\n' "$1" >&2
  exit 1
}

has_entries() {
  directory=$1
  for entry in "$directory"/* "$directory"/.[!.]* "$directory"/..?*; do
    if [ -e "$entry" ] || [ -L "$entry" ]; then
      return 0
    fi
  done
  return 1
}

require_only_entry() {
  directory=$1
  expected=$2
  for entry in "$directory"/* "$directory"/.[!.]* "$directory"/..?*; do
    if [ -e "$entry" ] || [ -L "$entry" ]; then
      [ "$entry" = "$expected" ] || refuse "unrecognized nonempty data layout at ${directory}; preserve it and restore a logical backup into a separate PostgreSQL 18 volume"
    fi
  done
}

check_layout() {
  data_root=$1
  [ -d "$data_root" ] && [ -r "$data_root" ] && [ -x "$data_root" ] || refuse "cannot inspect mounted data directory ${data_root}"

  [ ! -e "$data_root/PG_VERSION" ] && [ ! -L "$data_root/PG_VERSION" ] || refuse "legacy root PG_VERSION found at ${data_root}/PG_VERSION; preserve this volume and restore a logical backup into a separate PostgreSQL 18 volume"
  [ ! -e "$data_root/pgdata/PG_VERSION" ] && [ ! -L "$data_root/pgdata/PG_VERSION" ] || refuse "legacy nested PG_VERSION found at ${data_root}/pgdata/PG_VERSION; preserve this volume and restore a logical backup into a separate PostgreSQL 18 volume"

  major_root="$data_root/18"
  data_directory="$major_root/docker"
  if [ -e "$major_root" ] || [ -L "$major_root" ]; then
    [ -d "$major_root" ] && [ ! -L "$major_root" ] || refuse "PostgreSQL 18 data root ${major_root} is not a directory"
    [ -d "$data_directory" ] && [ ! -L "$data_directory" ] || refuse "PostgreSQL 18 data directory ${data_directory} is missing or unsafe"
    [ -f "$data_directory/PG_VERSION" ] && [ ! -L "$data_directory/PG_VERSION" ] || refuse "PostgreSQL 18 data directory ${data_directory} has no regular PG_VERSION file"
    version=$(cat "$data_directory/PG_VERSION") || refuse "cannot read ${data_directory}/PG_VERSION"
    [ "$version" = "18" ] || refuse "expected PostgreSQL major 18 at ${data_directory}/PG_VERSION"
    [ -d "$data_directory/base" ] && [ -d "$data_directory/global" ] && [ -f "$data_directory/global/pg_control" ] || refuse "PostgreSQL 18 data directory ${data_directory} is incomplete"
    [ ! -L "$data_directory/base" ] && [ ! -L "$data_directory/global" ] && [ ! -L "$data_directory/global/pg_control" ] || refuse "PostgreSQL 18 data directory ${data_directory} has unsafe cluster markers"
    require_only_entry "$data_root" "$major_root"
    require_only_entry "$major_root" "$data_directory"
    return 0
  fi

  if has_entries "$data_root"; then
    refuse "unrecognized nonempty data layout at ${data_root}; preserve it and restore a logical backup into a separate PostgreSQL 18 volume"
  fi
}

if [ "${1:-}" = "--check-layout" ]; then
  [ "$#" -eq 2 ] || refuse "usage: $0 --check-layout <data-directory>"
  check_layout "$2"
  exit 0
fi

check_layout /var/lib/postgresql
exec /usr/local/bin/docker-entrypoint.sh "$@"
