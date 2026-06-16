#!/usr/bin/env bash
#
# demo-up.sh — one-command P1 demo bootstrap.
#
# Brings up all Phase-1 services, waits for them to be READY, drives the Simulator to ingest a
# grounded 10-site topology snapshot (real mode, HTTP upload → topology), waits for the
# topology→trails→codebook chain to settle, then prints the web-ui URL + a walkthrough guide.
#
# Usage:
#   ./scripts/demo-up.sh           # rebuild images then up (default — always reflects current code)
#   ./scripts/demo-up.sh --fast    # skip rebuild (quick restart; may show stale code)
#
# Stop / clean up:   ./scripts/demo-down.sh        (keeps data)
#                    ./scripts/demo-down.sh --wipe (also removes volumes for a clean cold start)
#
set -euo pipefail

# --- config (override via env) ----------------------------------------------------------------
SITE_COUNT="${SITE_COUNT:-10}"           # 10 → full UK/EU basemap (catalogue max 12)
IGP_AREA_COUNT="${IGP_AREA_COUNT:-3}"
TOPOLOGY_NODE_COUNT="${TOPOLOGY_NODE_COUNT:-20}"
WEB_UI_URL="http://localhost:8086"
READY_TIMEOUT="${READY_TIMEOUT:-300}"    # seconds to wait for each service to become healthy

# P1 services only (P2/P3 collaborators aren't built; the SPA placeholders those views).
P1_SERVICES=(kafka postgres nebula-metad nebula-storaged nebula-graphd kafka-init \
             knowledge topology trail-builder codebook-generator codebook-api web-ui)

# Health endpoints (host ports) → poll until 200.
declare -a HEALTH=(
  "knowledge|http://localhost:8081/health"
  "topology|http://localhost:8082/actuator/health"
  "trail-builder|http://localhost:8083/health"
  "codebook-api|http://localhost:8084/health"
  "web-ui|http://localhost:8086/"
)

# --- locate repo root + compose ---------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

if docker compose version >/dev/null 2>&1; then DC="docker compose"; else DC="docker-compose"; fi

REBUILD=1
for arg in "$@"; do
  case "$arg" in
    --fast|--no-build) REBUILD=0 ;;
    --build) REBUILD=1 ;;
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $arg (use --fast to skip rebuild)"; exit 2 ;;
  esac
done

say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()  { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
warn(){ printf '  \033[1;33m!\033[0m %s\n' "$*"; }

# --- 1. bring up the stack --------------------------------------------------------------------
if [ "$REBUILD" -eq 1 ]; then
  say "Building + starting P1 services (use --fast to skip the rebuild)…"
  $DC up -d --build "${P1_SERVICES[@]}"
else
  say "Starting P1 services (no rebuild)…"
  $DC up -d "${P1_SERVICES[@]}"
fi

# --- 2. wait for READY ------------------------------------------------------------------------
say "Waiting for services to become READY (JVM cold start can take ~2-3 min)…"
wait_for() {
  local name="$1" url="$2" deadline=$((SECONDS + READY_TIMEOUT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -sf -o /dev/null "$url" 2>/dev/null; then ok "$name ready ($url)"; return 0; fi
    sleep 3
  done
  warn "$name did NOT become ready within ${READY_TIMEOUT}s ($url)"
  warn "check logs: $DC logs $name"
  return 1
}
fail=0
for entry in "${HEALTH[@]}"; do
  wait_for "${entry%%|*}" "${entry##*|}" || fail=1
done
if [ "$fail" -ne 0 ]; then
  echo; warn "Some services aren't ready. Inspect with: $DC ps   and   $DC logs <service>"
  exit 1
fi

# --- 3. ingest a P1 topology snapshot via the Simulator (real mode, HTTP upload) --------------
# One-off run (the default simulator service runs --phase p2 and exits; #215). The P1 ingest
# generates a grounded snapshot and POSTs it to topology /topology/snapshots (no Kafka needed).
say "Ingesting a ${SITE_COUNT}-site topology snapshot via the Simulator (real mode)…"
if $DC run --rm \
     -e PHASE=p1 \
     -e TOPOLOGY_API_MODE=real \
     -e TOPOLOGY_API_BASE_URL=http://topology:8080 \
     -e KNOWLEDGE_MODE=real \
     -e SITE_COUNT="$SITE_COUNT" \
     -e IGP_AREA_COUNT="$IGP_AREA_COUNT" \
     -e TOPOLOGY_NODE_COUNT="$TOPOLOGY_NODE_COUNT" \
     -e SIM_OUTPUT_DIR=/tmp/sim \
     simulator --phase p1; then
  ok "Topology snapshot ingested."
else
  warn "Simulator ingest returned non-zero. The stack is up; re-run the ingest with:"
  warn "  $DC run --rm -e PHASE=p1 -e TOPOLOGY_API_MODE=real -e TOPOLOGY_API_BASE_URL=http://topology:8080 -e KNOWLEDGE_MODE=real -e SITE_COUNT=$SITE_COUNT simulator --phase p1"
  exit 1
fi

# --- 4. wait for the chain to settle (topology.changed → trails.built → codebook.generated) ---
say "Waiting for the correlation chain to settle…"
chain_ok=0
deadline=$((SECONDS + 120))
while [ "$SECONDS" -lt "$deadline" ]; do
  # codebook becomes queryable once the chain completes; /codebooks lists by domain.
  if curl -sf "http://localhost:8084/codebooks?domain=core-ip" 2>/dev/null | grep -q '"codebookId"'; then
    ok "Chain complete — codebook generated and queryable."
    chain_ok=1; break
  fi
  sleep 3
done
[ "$chain_ok" -eq 1 ] || warn "Chain not confirmed within 120s (topology/trails are likely still usable; check $DC logs codebook-generator)."

# --- 5. confirm the basemap asset is served (offline geography) -------------------------------
if curl -sf -o /dev/null "$WEB_UI_URL/geo/europe.json" 2>/dev/null; then
  ok "Basemap asset served ($WEB_UI_URL/geo/europe.json)."
else
  warn "Basemap asset not reachable at $WEB_UI_URL/geo/europe.json (map may show blank)."
fi

# --- 6. print the walkthrough -----------------------------------------------------------------
printf '\n\033[1;32m%s\033[0m\n' "════════════════════════════════════════════════════════════════════════════"
printf ' P1 DEMO IS UP  —  open:  \033[1;36m%s\033[0m\n' "$WEB_UI_URL"
printf '\033[1;32m%s\033[0m\n' "════════════════════════════════════════════════════════════════════════════"
cat <<EOF

WALKTHROUGH (network-operator persona):

  1. TOPOLOGY (default view) — a real UK/EU basemap with $SITE_COUNT green "Monitored"
     status-dot site pins (London, Frankfurt, Madrid, Stockholm, Warsaw, ...) and a
     "Fault: 0 . Warning: 0 . Monitored: $SITE_COUNT" status bar.

  2. DRILL IN — click a site pin (e.g. London Docklands) -> its device-level graph:
     nodes colour-coded by layer (fiber/IP/IGP/LSP/service), edges, layer toggles,
     and an attribute detail panel. Use the breadcrumb ("Topology & trails") to go back.

  3. TRAILS — on the site graph, the Trail-clusters list shows the area-bounded trails;
     selecting a device highlights the trails it belongs to.

  4. CONFIG — the Config tab is LIVE (Knowledge model-params: DBSCAN/window/prefixspan).

  LIVE (P1, real data):   Topology . Site graph . Trails . Config
  PLACEHOLDER (P2/P3):    Dashboard . Streaming . Patterns . Incidents . Stats . Chatter
                          (their backing services aren't built yet — they degrade gracefully)

Stop the demo:   ./scripts/demo-down.sh          (keeps data)
                 ./scripts/demo-down.sh --wipe   (also wipes volumes for a clean cold start)
EOF
