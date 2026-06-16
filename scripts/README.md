# Demo / dev bootstrap scripts

One-command bring-up of the **Phase-1** stack for a look-and-feel walkthrough of the platform.

## Quick start

```bash
./scripts/demo-up.sh            # build + start all P1 services, ingest a 10-site snapshot,
                                # wait for the chain, then print the web-ui URL + walkthrough
./scripts/demo-up.sh --fast     # skip the image rebuild (quick restart; may show stale code)

# then open the printed URL:
open http://localhost:8086      # (macOS)  — or paste into a browser

./scripts/demo-down.sh          # stop (keeps data/volumes — fast restart)
./scripts/demo-down.sh --wipe   # stop + wipe volumes (clean cold start next time)
```

## What it does

1. `docker compose up -d --build` the P1 services: kafka, postgres, the 3 NebulaGraph services,
   knowledge (8081), topology (8082), trail-builder (8083), codebook-generator + codebook-api (8084),
   web-ui (8086).
2. Polls each service's health endpoint until READY (JVM cold start can take ~2-3 min — the script
   waits up to `READY_TIMEOUT`, default 300s).
3. Runs the **Simulator** once in P1 real mode (`docker compose run --rm simulator --phase p1`):
   generates a grounded `SITE_COUNT`-site topology and **HTTP-uploads** it to topology
   `POST /topology/snapshots` (no Kafka needed — sidesteps the known simulator P2 Kafka issue #215).
4. Waits for the correlation chain (`topology.changed → trails.built → codebook.generated`) to settle.
5. Confirms the offline basemap asset is served, then prints the web-ui URL + a network-operator
   walkthrough guide.

## What you'll see (P1 scope)

- **LIVE (real data):** Topology (UK/EU basemap + status-dot site pins) → Site graph (device topology,
  layer toggles, attributes) → Trails → **Config** (Knowledge model-params).
- **Placeholders (P2/P3):** Dashboard, Streaming, Patterns, Incidents, Stats, Chatter — their backing
  services aren't built yet; the UI degrades these views gracefully.

## Tunables (env overrides)

| Var | Default | Notes |
|---|---|---|
| `SITE_COUNT` | `10` | 1–12; 10 gives the full UK/EU map |
| `IGP_AREA_COUNT` | `3` | IGP areas for area-bounded trails |
| `TOPOLOGY_NODE_COUNT` | `20` | devices per area |
| `READY_TIMEOUT` | `300` | seconds to wait per service health check |

```bash
SITE_COUNT=12 ./scripts/demo-up.sh     # all 12 catalogue PoPs
```

## Troubleshooting

- A service didn't come up: `docker compose ps` and `docker compose logs <service>`.
- Re-run just the ingest (stack already up):
  ```bash
  docker compose run --rm -e PHASE=p1 -e TOPOLOGY_API_MODE=real \
    -e TOPOLOGY_API_BASE_URL=http://topology:8080 -e KNOWLEDGE_MODE=real \
    -e SITE_COUNT=10 simulator --phase p1
  ```
- Blank map / stale UI: rebuild without `--fast` so the latest web-ui bundle (incl. `public/geo/europe.json`)
  is served — `./scripts/demo-up.sh`.
