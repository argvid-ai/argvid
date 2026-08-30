#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <file-or-directory> [...]" >&2
  exit 2
fi

scan_list="$(mktemp)"
cleanup_scan_list() {
  rm -f -- "$scan_list"
}
trap cleanup_scan_list EXIT INT TERM

for scan_target in "$@"; do
  if [[ -f "$scan_target" ]]; then
    printf '%s\n' "$scan_target"
  elif [[ -d "$scan_target" ]]; then
    rg --files "$scan_target"
  else
    echo "Scan target does not exist: $scan_target" >&2
    exit 2
  fi
done | sort -u > "$scan_list"

scan_status=0
while IFS= read -r scan_file; do
  if ! LC_ALL=C grep -Iq . "$scan_file"; then
    continue
  fi

  awk '
    {
      line = tolower($0)
      if (line ~ /authorization:[[:space:]]*bearer[[:space:]]+[a-z0-9._-]+/) {
        print FILENAME ":" FNR ":bearer-header"
        found = 1
        next
      }
      if (line ~ /-----begin (rsa |ec |openssh )?private key-----/) {
        print FILENAME ":" FNR ":private-key"
        found = 1
        next
      }
      if (line ~ /(api[_-]?key|client[_-]?secret)[[:space:]]*[:=][[:space:]]*[^[:space:]]+/) {
        print FILENAME ":" FNR ":key-assignment"
        found = 1
        next
      }
    }
    END { if (found) exit 1 }
  ' "$scan_file" || scan_status=1
done < "$scan_list"

exit "$scan_status"
