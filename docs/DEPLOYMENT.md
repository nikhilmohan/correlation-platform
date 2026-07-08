# Deployment & Operations Guide

How to deploy the AI/ML Alarm Correlation Platform on a remote VM, run the P1 → P2 → P3
phases, extend it to new domains, and the remote-readiness notes (ports, env, no code changes
needed for a different host).

Deploy from the **`integrate-baseline`** branch — the single branch that builds and runs the whole
platform (all 19 containers via `docker-compose.yml`).

---

## 1. Prerequisites (remote VM)

| Requirement | Recommendation | Why |
|---|---|---|
| **CPU** | 8+ vCPU | Spark pattern-mining (P2) + JVM services + Kafka/Nebula/Postgres |
| **RAM** | **≥ 16 GB** (8 GB is NOT enough) | Spark driver alone wants ~2–4 GB; the 19-container stack incl. Nebula + Kafka needs headroom. On 8 GB, P2 Spark mining OOMs and JVM services thread-starve. |
| **Disk** | ≥ 30 GB free | Docker images + Kafka/Postgres/Nebula volumes |
| **OS** | Linux x86-64 (Ubuntu 22.04+ / similar) | |
| **Docker** | Docker Engine 24+ **and** the Compose v2 plugin (`docker compose`) | The stack is pure Docker Compose |
| **Toolchain (build-time only, inside images)** | Java 17, Python 3.13, Node 24 — **pinned in the Dockerfiles**, you do NOT install them on the VM | Images build from source; the VM only needs Docker |
| **Network** | Outbound internet for the first image build (pulls base images + deps) | After the first `--build`, it runs offline |

No language runtimes, databases, or Kafka need to be installed on the VM — everything runs in
containers. You only need Docker + the repo.

### Firewall / port exposure (important for a public VM)

`docker-compose.yml` publishes these host ports (they bind to `0.0.0.0` by default, i.e. the VM's
interfaces):

| Port | Service | Needed by |
|---|---|---|
| **8086** | **web-ui (the SPA)** | **The only port an operator's browser needs.** |
| 8081–8092 | individual service APIs (knowledge, topology, trail-builder, codebook, simulator, noise-filter, pattern-manager, alarm-manager, correlation-engine, enrichment) | Direct API/debug access only — the browser does NOT use these (see §5). |
| 5432 | Postgres | DB tooling/debug only |
| 9092 | Kafka (host listener) | Host-side Kafka tooling only |
| 9669 | NebulaGraph | Graph tooling/debug only |

**Recommendation for a public VM:** firewall everything except **8086** (and SSH). The platform
works with only 8086 reachable — all inter-service traffic is internal to the Docker network.
If you want the debug ports, restrict them to your IP / a VPN. To bind a port to localhost-only
instead of all interfaces, prefix the mapping with `127.0.0.1:` in `docker-compose.yml`
(e.g. `"127.0.0.1:5432:5432"`).

---

## 2. Deploy (bring the stack up)

```bash
git clone -b integrate-baseline <repo-url>
cd correlation-platform

# Build all images from source and start the full stack.
# First run builds ~12 images (several minutes); subsequent runs reuse them.
docker compose up -d --build

# Watch until everything is healthy (Java services take longest, ~2-3 min).
docker compose ps
```

The `kafka-init` one-shot job provisions the Kafka topic catalog automatically. Knowledge
auto-seeds its model-params, and **pattern-manager auto-seeds 8 pre-approved patterns** (see §4) —
so P3 correlation can run **immediately** after P1 onboarding, without waiting for P2 mining.

Open the SPA at **`http://<VM-IP>:8086`**.

> **Never run `docker compose down -v`** to "clean" the stack — the `-v` wipes ALL volumes,
> destroying the P1 topology + P2 patterns (one-time bootstrap data). Use the **Reset** action /
> the P3 purge endpoints to clear only live (P3) alarms. `docker compose down` (no `-v`) + `up`
> preserves the bootstrap data.

---

## 3. Running the phases (P1 → P2 → P3)

The platform runs in three phases (see `docs/architecture.md`):

- **P1 — Topology onboarding** (one-time): ingest a topology snapshot → build trails → generate a
  codebook. Bootstrap data; persists.
- **P2 — Pattern learning** (one-time / on-demand refresh): generate a labeled alarm corpus →
  noise-filter clusters it → the pattern-miner mines patterns → operator approves them. Bootstrap
  data; persists. **Ships with seed patterns**, so P2 is optional for a first run.
- **P3 — Real-time correlation** (the repeatable operational loop): ingest live alarms →
  enrich → filter → correlate → incidents with RCA. Reset/re-ingest freely; P1/P2 are untouched.

### P1 — Topology onboarding (run once per deploy)

The Simulator ingests a grounded topology snapshot into the Topology Service (real-mode HTTP
upload → topology → trails → codebook). The compose `simulator` runs P3 serve-mode by default, so
P1 is a one-off `docker compose run`:

```bash
docker compose run --rm --no-deps \
  -e PHASE=p1 -e SIM_MODE=generate \
  -e P3_NETWORK_WIDE=false -e P3_TOTAL_ALARMS=0 -e P3_AUTO_CORRELATION_TARGET=0.6 \
  -e TOPOLOGY_API_MODE=real -e TOPOLOGY_API_BASE_URL=http://topology:8080 \
  -e KNOWLEDGE_MODE=real -e KNOWLEDGE_API_BASE_URL=http://knowledge:8080 \
  -e SITE_COUNT=10 -e TOPOLOGY_NODE_COUNT=50 \
  --entrypoint python simulator -m simulator --phase p1
```

Verify: `curl http://localhost:8082/topology/sites?domain=core-ip&snapshotId=current` should list
the sites; the `trails.built` and `codebook.generated` chain settles within ~1–2 min.

> The convenience script `scripts/demo-up.sh` automates P1 (it drives this same ingest and waits
> for the chain). Run it **on the VM** (its health-polls use `localhost`, which = the VM itself).

### P2 — Pattern learning (optional; seed patterns cover the first run)

**Option A — use the seed patterns (default, no compute):** the deploy already ships 8 approved
patterns, so you can skip straight to P3. Confirm:
`curl "http://localhost:8090/patterns?lifecycle=approved"` → `total ≥ 8`.

**Option B — mine fresh patterns (needs the ≥16 GB VM):** from the SPA, use
**Actions → Mine patterns** (or `POST http://localhost:8085/mine/run`). This generates a P2 corpus;
the always-running pattern-miner consumes it and emits **draft** patterns to pattern-manager. Then
review + approve them in **ML → Pattern mining** (each pattern has an Approve action). Approved
patterns become available to the Correlation Engine for P3.

- The **pattern-miner is a continuous live consumer** (not a batch job) — it mines whatever lands
  on `transactions.clean`. "Mine patterns" just triggers the corpus feed; the miner does the rest.
- Mining uses Spark (`MINING_ENGINE=spark`, `--driver-memory 2g`). On a smaller host you can fall
  back to the pure-Python engine (`MINING_ENGINE=local`) with a small `MAX_TRAILS_PER_BATCH`, but a
  full mine is genuinely resource-intensive — the ≥16 GB VM is the intended target.

### P3 — Real-time correlation (the operational loop)

Once approved patterns exist (seed or mined):

- **Actions → Ingest alarms** (or `POST http://localhost:8085/synth/run`, body `{}` or
  `{"totalAlarms":150}`) starts a live alarm run. Alarms flow
  simulator → enrichment → alarm-manager → correlation-engine → incidents.
- Watch the **Alarms** view (incident groups with RCA), the **dashboard KPIs**
  (auto-correlation %, RCA accuracy, live incidents), and the **topology map** (sites/nodes turn
  red/amber/green by worst active alarm severity).
- **Actions → Purge alarms** (Reset) clears all P3 live alarms + incidents and resets the KPIs,
  returning the map to green. **P1 topology and P2 patterns are preserved** — only P3 is purged.

---

## 4. Bootstrap / seed data

Seed data is loaded automatically on startup (idempotent — re-loads only what's missing):

| Seed | Owner | File | Purpose |
|---|---|---|---|
| Model params (DBSCAN, mining, correlation thresholds) + domain vocabulary | Knowledge | `services/knowledge/src/main/resources/seed/<domain>.json` | Config every ML stage reads (no hard-coded thresholds) |
| Pre-approved patterns | pattern-manager | `services/pattern-manager/src/main/resources/seed/<domain>-patterns.json` | Out-of-the-box P3 correlation without mining |

The seed patterns are structurally generalized: the Correlation Engine matches patterns to trails
**by structure (object-type compatibility), not by exact trailId** — so the shipped patterns work
against a freshly-ingested topology snapshot on any deploy. They are rooted at true upstream causes,
so they give correct RCA.

To disable pattern seeding (e.g. to force mining), set `PATTERN_SEED_ON_STARTUP=false` on the
pattern-manager service.

Topology (P1) is **not** seeded as a file — it's ingested via the P1 step above, because it must be
grounded in the Topology Service's graph.

---

## 5. Remote-readiness audit (no code changes needed for a different host)

The platform was designed host-agnostic. Verified:

- **Browser → backend is same-origin + relative paths.** The SPA calls `/api/<svc>/...` on its own
  origin (`:8086`); nginx *inside the web-ui container* proxies each `/api/<svc>/` to the backend
  service by its in-network name. So there is **no hard-coded host/localhost in the browser path** —
  it works from any VM IP or hostname automatically. The `*_API_BASE_URL` env vars are all relative
  (`/api/topology`, `/api/simulator`, …); leave them as-is.
- **nginx is host-agnostic** — `server_name _;` (any host) and a Docker-DNS `resolver`; no server
  name to change.
- **All inter-service traffic uses Docker service names** (`kafka:29092`, `postgres:5432`,
  `topology:8080`, …) resolved on the internal `cp-net` network — independent of the host machine.
- **No CORS allowlist** pins a host (same-origin proxying means no cross-origin requests).
- **Kafka `advertised.listeners` includes `PLAINTEXT://localhost:9092`** — this is the *host-side*
  listener for optional Kafka tooling run **on the VM**; all platform services use the
  `INTERNAL://kafka:29092` listener. You only need to touch this if an **external** (off-VM) Kafka
  client must connect — then set `KAFKA_ADVERTISED_LISTENERS`'s PLAINTEXT host to the VM's
  reachable IP/DNS. Not required for normal operation.
- **Healthchecks use `http://localhost:...`** — these run *inside* each container against itself;
  correct, leave them.

**What you may want to change for remote (all optional / ops, not code):**

1. **Firewall** — expose only `8086` publicly (see §1). This is the main action for a public VM.
2. **TLS** — the SPA is plain HTTP on 8086. For internet exposure, put a reverse proxy (nginx/
   Caddy/Traefik) with TLS in front of `:8086`, or add a `443` listener. No app change needed.
3. **DB credentials** — Postgres defaults to `correlation`/`correlation`
   (`SPRING_DATASOURCE_USERNAME`/`PASSWORD`, `POSTGRES_*`). Change these in compose for a real
   deployment.
4. **Resource tuning** — on a large VM you can raise Spark parallelism/heap
   (`SPARK_MASTER=local[N]`, `PYSPARK_SUBMIT_ARGS=--driver-memory Ng`) and
   `MAX_TRAILS_PER_BATCH` for faster mining.

There are **no source-code changes required** to run remotely — only the optional ops items above.

---

## 6. Extending to a new domain

The platform is multi-domain by design; **Core IP is the MVP domain pack**. Alarm correlation for a
different network/technology (e.g. optical transport, RAN, data-center fabric) is added as a new
domain, not a fork. The engine code references nothing by domain name — everything domain-specific
is behind a `DomainPack` protocol + Knowledge-authored config.

To add a domain `<newdom>`:

1. **Simulator domain pack** — add `services/simulator/src/simulator/domains/<newdom>/` implementing
   the `DomainPack` Protocol (mirror `domains/coreip/`). Declare:
   - `domain_id()` → `"<newdom>"`
   - `object_types()`, `edge_relations()`, `attribute_keys()` — the managed-object type vocabulary
     and topology relations
   - `alarm_type_vocabulary()` + `alarm_shape(type)` — the domain's alarm types and their
     event-type / probable-cause / severity shapes
   - `propagation_templates()` — how a root fault cascades (drives realistic synthetic cascades)
   - `scenario_library()` — the labeled fault scenarios (the eval oracle's ground truth)
   - `noise_classes()`, `geo_sites()`, `placement_affinity()`, `build_topology()`
   Then select it: the pack is chosen by the `make_pack()` factory in
   `services/simulator/src/simulator/run.py` (today a single-domain factory that returns
   `CoreIPPack()`). Extend `make_pack()` to return the new pack based on the `SYNTH_DOMAIN` /
   `DOMAIN` setting (e.g. `core-ip` → `CoreIPPack()`, `<newdom>` → `NewDomPack()`). The engine
   (`engine/`) references only the `DomainPack` Protocol, so no engine edit is needed.

2. **Knowledge seed** — add `services/knowledge/src/main/resources/seed/<newdom>.json`: the domain
   vocabulary + per-service model-params (DBSCAN eps/minSamples, mining `prefixspan.*` +
   `anchoring.*` + `sample.maxAlarms`, correlation thresholds, session-window, enrichment ruleset
   references). Mirror `seed/core-ip.json` exactly — the pattern-miner and others fail-fast if a
   required param key is missing.

3. **Pattern seed (optional but recommended)** — add
   `services/pattern-manager/src/main/resources/seed/<newdom>-patterns.json` with a handful of
   known-good approved cascades rooted at true causes, so P3 works out-of-the-box for the new
   domain (same shape as `core-ip-patterns.json`).

4. **Enrichment ruleset** — enrichment owns a mounted per-source YAML ruleset validated against
   Knowledge's vocabulary; add/point to the `<newdom>` ruleset.

5. **Point the stack at the domain** — set the domain env on the relevant services in
   `docker-compose.yml`: `DOMAIN`, `KNOWLEDGE_DOMAIN`, `ENRICHMENT_DOMAIN`,
   `KNOWLEDGE_MODEL_PARAMS_RECORD_ID` (`<newdom>/modelParams/pattern-miner`), etc. — everywhere
   `core-ip` currently appears.

6. **Run the phases** for the new domain: P1 (ingest the new domain's topology via the new pack's
   `build_topology`), P2 (mine or seed patterns), P3 (correlate).

What you do **not** need to touch: the correlation engine, alarm manager, trail-builder,
codebook-generator, noise-filter, or web-ui — they are domain-agnostic and driven entirely by the
domain pack + Knowledge config + the event-model contract (`libs/event-model`, which is frozen and
must stay byte-identical to `main`).

---

## 7. Quick reference — operational endpoints

All reachable through the SPA's nginx at `http://<VM>:8086/api/<svc>/...`, or directly on the
service's host port:

| Action | Endpoint | Host port |
|---|---|---|
| Ingest live alarms (P3) | `POST /synth/run` + `GET /synth/status` | simulator :8085 |
| Mine patterns (P2 corpus) | `POST /mine/run` + `GET /mine/status` | simulator :8085 |
| Purge live alarms (P3 reset) | `POST /admin/purge-live-alarms` | alarm-manager :8091 |
| Reset correlation (P3 reset) | `POST /admin/reset-correlation` | correlation-engine :8092 |
| Approve a pattern | `POST /patterns/{id}/approve` `{"decision":"approve","reviewer":"..."}` | pattern-manager :8090 |
| List approved patterns | `GET /patterns?lifecycle=approved` | pattern-manager :8090 |
| Correlation KPIs | `GET /stats` | correlation-engine :8092 |

Every service also exposes `/health` (or `/actuator/health`) and `/metrics`.
