#!/usr/bin/env bash
#
# demo-down.sh — stop the P1 demo stack.
#
# Usage:
#   ./scripts/demo-down.sh          # stop + remove containers, KEEP volumes (fast restart, data kept)
#   ./scripts/demo-down.sh --wipe   # also remove volumes (clean cold start next time)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

if docker compose version >/dev/null 2>&1; then DC="docker compose"; else DC="docker-compose"; fi

WIPE=0
for arg in "$@"; do
  case "$arg" in
    --wipe|-v) WIPE=1 ;;
    -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $arg (use --wipe to also remove volumes)"; exit 2 ;;
  esac
done

if [ "$WIPE" -eq 1 ]; then
  printf '\033[1;36m▸ Stopping demo and wiping volumes (clean cold start next time)…\033[0m\n'
  $DC down -v --remove-orphans
else
  printf '\033[1;36m▸ Stopping demo (volumes kept; data persists for next ./scripts/demo-up.sh --fast)…\033[0m\n'
  $DC down --remove-orphans
fi
printf '\033[1;32m✓ Demo stopped.\033[0m\n'
