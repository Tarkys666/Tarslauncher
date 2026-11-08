#!/usr/bin/env bash
set -euo pipefail

valid_tag() {
  [[ -z "$1" || "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?(\+[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]]
}

case "${1:-}" in
  validate)
    valid_tag "${2:-}" || { printf '%s\n' 'Invalid release tag. Expected v1.2.3 with optional prerelease/build suffix.' >&2; exit 1; }
    ;;
  test)
    for tag in '' v1.2.3 v2.0.27 v1.2.3-beta.1 v1.2.3+build.2 v1.2.3-rc.1+build.2; do
      valid_tag "$tag" || exit 1
    done
    for tag in main --test v1.2 'v1.2.3$(printf unsafe)' 'v1.2.3";exit 0;#' "v1.2.3'" $'v1.2.3\nextra'; do
      if valid_tag "$tag"; then printf '%s\n' 'Unsafe release tag accepted' >&2; exit 1; fi
    done
    printf '%s\n' 'Release tag validation tests passed'
    ;;
  *) exit 2 ;;
esac
