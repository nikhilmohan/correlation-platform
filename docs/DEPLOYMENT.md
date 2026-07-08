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

The platform is **multi-domain by design**; Core IP is the MVP domain. Correlation for a different
network/technology (optical transport, RAN, data-center fabric, …) is added as a new domain, **not a
fork**. The whole pipeline — enrichment, noise-filter, trail-builder, codebook-generator,
pattern-miner, pattern-manager, correlation-engine, topology — is **domain-agnostic**: each takes the
`domain` as a runtime parameter (from the event/snapshot or Knowledge) and drives its behaviour from
**Knowledge-authored config** + the **event-model contract**. Domain specifics live in three places:
**Knowledge** (the authored ontology), the **Simulator** (the synthetic-data domain pack), and a
small **web-ui** presentation map.

_(Every claim, file path, method, record type and env-var name below has been verified against the
`integration` source.)_

### What each service needs (verified)

| Service | Change for a new domain | What |
|---|---|---|
| **knowledge** | **Authored data** | Add `seed/<newdom>.json` (the domain ontology + params). |
| **simulator** | **Code + data** | Add a `domains/<newdom>/` DomainPack + make `make_pack()` domain-aware. |
| **web-ui** | **Code (small)** | Extend the alarm-type label map (presentation only). |
| **pattern-manager** | Authored data (optional) | Add `seed/<newdom>-patterns.json` for out-of-box P3. |
| **enrichment** | Authored data (real sources only) | Per-source ruleset mapping vendor alarms → the domain vocabulary. Not needed with the Simulator (it emits canonical `alarmType`). |
| topology · trail-builder · codebook-generator · noise-filter · pattern-miner · correlation-engine | **No change** | Fully domain-parameterized via the Knowledge API + event-model. Verified: none hardcodes alarm-type/object-type vocabulary — only a `core-ip` config *default* and doc-comments. |

**Net: CODE in exactly 2 services (simulator pack + web-ui labels); AUTHORED DATA in 1 required
(Knowledge) + 2 optional (pattern-manager, enrichment); NOTHING in the 6 core-engine services.**
The **event-model contract (`libs/event-model`) needs no change** — generic envelope, `alarmType` is
a free string token (no enum), `managedObjectId` is `<objectType>:<id>`; it stays byte-identical to
`main`.

### Step-by-step checklist for a new domain `<newdom>`

**① Knowledge ontology — `services/knowledge/src/main/resources/seed/<newdom>.json`** (required)

Mirror `seed/core-ip.json`. It has **8 record types** (core-ip = 43 records total); the `SeedLoader`
reads the `"domain"` field and auto-scopes everything (loads vocabularies → templates → params):

- `objectTypeVocabulary` ×1 — managed-object types (must include `Site`); match the pack's `object_types()`.
- `edgeRelationVocabulary` ×1 — topology relations (must include `LOCATED_AT`); match `edge_relations()`.
- `alarmTypeVocabulary` ×1 — canonical alarm-type tokens (the universal join key); match `alarm_type_vocabulary()`.
- `faultOriginType` ×N (core-ip: 7) — one per root-cause object type: `{objectType, originAlarmType, description}`.
- `propagationTemplate` ×N (core-ip: 27) — trigger `{objectType,alarmType}` → effect `{objectType,alarmType}` + traversal.
- `trailPolicy` ×1 — trail-closure edge types + boundary + SRLG rule.
- `attributeCatalogue` ×1 — well-known device/connection attribute keys.
- `modelParams` ×4 — one set each for **noise-filter** (DBSCAN eps/minSamples, window, feature keys),
  **pattern-miner** (`prefixspan.*` + the fail-fast keys `anchoring.matchConfidenceThreshold`,
  `anchoring.weights.order`, `anchoring.weights.jaccard`, `sample.maxAlarms`), **correlation-engine**,
  **pattern-manager**. A missing required key makes the consuming service fail fast.

Validate: start Knowledge, confirm the log `pattern seed pack … loaded (N new records …)`.

**② Simulator domain pack** (required — the one substantive code addition)

Create `services/simulator/src/simulator/domains/<newdom>/` mirroring `domains/coreip/`. Core-IP has
these modules (create the analogues): `__init__.py`, `pack.py` (the `<NewDom>Pack` class),
`alarm_shapes.py`, `propagation.py`, `scenario_library.py`, `topology_model.py`, `geo_catalogue.py`,
`p3_placement.py`.

Implement the **`DomainPack` Protocol** — the exact 12 methods in
`services/simulator/src/simulator/engine/domain_pack.py`: `domain_id`, `object_types`,
`edge_relations`, `attribute_keys`, `alarm_type_vocabulary`, `alarm_shape(alarm_type)`,
`propagation_templates`, `scenario_library` (≥8 labeled fault scenarios — the eval-oracle ground
truth), `noise_classes` (≥3), `geo_sites` (≥10), `placement_affinity`, `build_topology(params, rng)`.
**Every value must match the Knowledge seed** — same object types, relations, and alarm tokens (two
halves of one contract).

Then wire selection. Today `make_pack()` in `services/simulator/src/simulator/run.py` takes **no
arguments** and hardcodes `return CoreIPPack()`, and it's called at three sites (P2/P3 generate + the
serve path). To make it domain-aware you must:
- give `make_pack()` access to the domain, e.g. `def make_pack(settings: Settings) -> DomainPack:` and
  branch on `settings.synth_domain` (the `SYNTH_DOMAIN` env, default `core-ip`) —
  `core-ip → CoreIPPack()`, `<newdom> → <NewDom>Pack()`; and
- update the **three `make_pack()` call sites** in `run.py` to pass `settings`.
The `engine/` references only the Protocol, so no engine edit is needed.

**③ web-ui labels** (small code change — presentation only)

`services/web-ui/src/app/patterns/alarm-type-labels.ts` — the `ALARM_TYPE_LABELS` map hardcodes
Core-IP `alarmType → human-label` pairs (e.g. `AdjDown: 'Adjacency Down'`). Add entries for the new
domain's alarm types. (Unmapped tokens fall back to the raw token, so the UI still works without
this — labels just read better with it.) The node-token extraction in `topology/alarm-severity.ts`
uses an `N\d+` regex with a **full-moid fallback**, so non-`N##` node schemes already work; override
only if the new domain needs a specific node token.

**④ pattern-manager seed** (optional — instant P3 without mining)

Add `services/pattern-manager/src/main/resources/seed/<newdom>-patterns.json` (mirror
`core-ip-patterns.json`): a few known-good approved cascades rooted at true upstream causes. Point the
loader at it via `PATTERN_SEED_PACK`.

**⑤ enrichment ruleset** (only for REAL alarm sources, not the simulator)

Enrichment maps raw vendor alarms → the domain's canonical `alarmType`. With the Simulator (synthetic
alarms already carry canonical `alarmType`), nothing is needed. For real NMS/OSS sources, author the
per-source ruleset validated against the domain's Knowledge vocabulary.

**⑥ point the stack at the domain** (config)

In `docker-compose.yml` set the domain env everywhere `core-ip` appears: `DOMAIN`,
`KNOWLEDGE_DOMAIN`, `ENRICHMENT_DOMAIN`, `SYNTH_DOMAIN` (simulator),
`KNOWLEDGE_MODEL_PARAMS_RECORD_ID` (`<newdom>/modelParams/pattern-miner`), and the web-ui `DOMAIN`.

**⑦ run the phases** — P1 (ingest the new domain's topology via the pack's `build_topology`), P2
(mine or seed patterns), P3 (correlate) — exactly as in §3.

### Two contracts a new developer must respect

1. **Knowledge seed ↔ Simulator pack must agree** — the object types, relations, and alarm-type
   vocabulary in `seed/<newdom>.json` and the `DomainPack` are the *same* contract; a mismatch fails
   snapshot validation or mining.
2. **The alarm-type vocabulary is the universal join key** — consistent across the Knowledge seed,
   the simulator pack, the (optional) enrichment rulesets, and the mined/seeded patterns. Everything
   downstream keys off it.

Start from `domains/coreip/` + `seed/core-ip.json` as reference implementations, and the `DomainPack`
Protocol in `engine/domain_pack.py` as the method checklist.

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
