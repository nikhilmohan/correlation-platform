# Solution Goals — AI/ML Alarm Correlation Platform (Core IP MVP)

> **Purpose.** This document states the **quantifiable outcomes** the MVP must demonstrate, per
> runtime phase, and the overarching goal of the solution. It is **build guidance** — read it
> alongside the hardened architecture and per-service design documents
> (`docs/architecture.md`, `docs/application-design.md`, each `services/<svc>/{spec.md,design.md}`,
> and the frozen `libs/event-model` contract). These numbers are **indicative MVP targets** that
> the design has been verified capable of achieving (see `docs/mvp-achievability.md`); they are
> the success criteria the build + integration phases must hit and measure against the Simulator
> ground-truth oracle.

## Overarching goal

Demonstrate, **evidently and accurately**, the power of **topology-grounded pattern correlation**
on a realistic Core IP network: turn a high volume of raw network alarms into a small number of
**correlated incidents with a tagged root cause**, so that the bulk of operator alarm load is
handled **automatically** by ML (statistical noise removal + pattern learning) and real-time
correlation. The MVP proves this for the **Core IP** domain; the solution is built to **extend to
other domains and cross-domain correlation** later **without re-architecture** (Knowledge-authored
records + a Simulator domain pack; domain-agnostic event model and services).

**Grounding principle.** All synthesized data should reflect **grounded telco (Core IP) network
data** as much as reasonably possible **without unnecessary complexity** — realistic topology
layers, sites, vendor/equipment attributes, IGP areas, alarm types, and fault-propagation
cascades, sized for a clear MVP demonstration.

---

## Phase 1 — Topology onboarding (offline)

**Goal:** stand up a grounded Core IP topology, derive trails from domain knowledge, compile the
codebook, and visualize it — with the UI carrying P1 elements and placeholders for P2/P3.

| # | Quantifiable outcome | Owning service(s) | How it is measured |
|---|----------------------|-------------------|--------------------|
| P1-1 | **~10 grounded geo sites** with lat/long/region, drawn from ≥10 distinct grounded telco PoP entries (`SITE_COUNT=10`, `p1-demo` profile) | simulator | site count + distinct grounded coords assertion (Simulator AC); `GET /topology/sites` returns 10 flat `SiteDto` |
| P1-2 | **Realistic multi-layer topology** spanning all 11 Core IP object types (Node, LineCard, Port, **Interface**, IPLink, IGPAdjacency, LSP, VPNService, FiberSpan, SRLG, Site), with grounded `vendor`/`model`/`equipmentType` attributes and a few devices per site | simulator → topology | snapshot validates against the canonical `snapshot.schema.json` + Knowledge vocabulary; lifts into NebulaGraph; site drill-down is non-trivial |
| P1-3 | **IGP areas populated** (`area-0` backbone + edge areas, `IGP_AREA_COUNT`) so trail closure is genuinely area-bounded | simulator → topology → trail-builder | `igpArea` present on nodes/interfaces; Trail Builder integration assertion: trails are area-bounded, no whole-network trail |
| P1-4 | **Trails built** from topology + Knowledge trail policy (IGP-area-bounded transitive closure incl. `HOSTS`/`TERMINATES`, SRLG fate-sharing) — multiple area-bounded trails, not one giant trail | trail-builder (consumes Knowledge `trailPolicy`) | `trails.built`; trails queryable; area-bound assertion holds on Simulator-generated data |
| P1-5 | **Codebook generated** from the trails — candidate-root-cause → predicted-symptom signatures, one active codebook per `(domain, snapshotId)` | codebook-generator | `codebook.generated`; `/scenarios` + `/trail-signatures` served |
| P1-6 | **Web-UI P1 elements live**: geo map of sites → drill-down to site device-level topology (Cytoscape, nodes+edges) → trail overlay → attribute detail; **P2/P3 modules placeholdered** (empty/N-A) | web-ui | P1 routes render from the Topology/Trail APIs; P2/P3 KPIs show placeholder until those phases run |

**P1 demonstrates:** the topology-correlation foundation — a grounded network, its trail structure,
and the codebook — visualized intuitively.

---

## Phase 2 — Pattern learning (offline)

**Goal:** from a realistic historical alarm corpus, remove noise statistically and discover the
recurring fault patterns, so that **~75–80% of alarms are handled automatically by ML**.

| # | Quantifiable outcome | Owning service(s) | How it is measured |
|---|----------------------|-------------------|--------------------|
| P2-1 | **~1000 alarms total** (configurable via `TOTAL_ALARMS` / the `p2-demo` profile; overridable; subset-runnable) | simulator | `simulator_alarms_emitted_total`; `p2-demo` pinned + asserted |
| P2-2 | **~20% noise (~200 alarms)** across ≥3 noise classes (flapping, self-clearing, chatty, coincidental) | simulator | configurable noise fraction; ground-truth marks each noise alarm |
| P2-3 | **8–10 distinct patterns, each spanning 10–20 alarm types**, with multiple instance occurrences, grounded in real Core IP fault cascades (drawn from the 9-scenario pack over the ~29-token alarm vocabulary + 28 propagation templates) | simulator (pack) + knowledge (vocab/templates) | scenario count + per-scenario distinct-alarmType span (Simulator ACs) |
| P2-4 | **Patterns cover ~50–60% of total alarm volume (~500–600 alarms)** | simulator (signal/background mix) | ground-truth pattern membership vs total |
| P2-5 | **Noise Filter removes the noise** at **removal ≥ 0.90 / retention ≥ 0.95** against the oracle (DBSCAN storm/noise removal on `alarms.enriched`) | noise-filter | noise-filter effectiveness metrics vs Simulator ground truth |
| P2-6 | **Pattern Miner discovers the injected patterns** at **pattern-quality ≥ 0.80** (PrefixSpan over `alarmType` sequences from cleaned transactions) | pattern-miner | pattern-quality = recovered/injected vs ground truth |
| P2-7 | **Net ~75–80% of alarms handled automatically** = noise removed (~20%) + pattern-covered (~55%) | noise-filter + pattern-miner + pattern-manager | the **auto-handled fraction** computed against the oracle (defined as a named integration metric) |
| P2-8 | **Patterns reviewed/approved with XAI + RCA** (human-in-the-loop), `rootCauseAlarmType` from the alarm-type vocabulary; structurally validated against topology | pattern-manager | `patterns.discovered` → operator approve → `patterns.approved` (carries `sessionWindow`) |
| P2-9 | **Noise → live feedback loop available**: Noise Filter's observed-chatter signatures are surfaced for operator promotion into the Enrichment live chatter list | noise-filter → web-ui → enrichment | observed-chatter read API; chatter-management page; Enrichment chatter edit API (hot-applied) |

**P2 demonstrates:** ML pattern discovery — that the platform learns the network's fault patterns
and would automatically handle the large majority of alarm load.

---

## Phase 3 — Real-time correlation (online)

**Goal:** on a live alarm stream, correlate alarms into incidents in real time using the learned
patterns + codebook, auto-correlating **~60%** and tagging root cause accurately.

| # | Quantifiable outcome | Owning service(s) | How it is measured |
|---|----------------------|-------------------|--------------------|
| P3-1 | **~500 alarms ingested live** (wall-clock paced; `p3-demo` profile; configurable; reuses the learned scenarios so there is matchable signal) | simulator | `p3-demo` pinned; live replay on `alarms.live` |
| P3-2 | **~60% of live alarms auto-correlated** into incidents via the `(trailId, patternId)` correlation-instance model + codebook decode | correlation-engine | **auto-correlation % = correlatedAlarmCount / totalAlarmsProcessed** from `GET /stats` |
| P3-3 | **Root cause tagged accurately** — RCA accuracy is a **shown** number (incident `rootCauseAlarmType` joined to ground truth; eval-mode `GET /stats.rcaAccuracy`), target consistent with the platform's **RCA accuracy ≥ 0.80** threshold | correlation-engine + web-ui | RCA accuracy on the dashboard (eval/demo mode) |
| P3-4 | **Alarm reduction** — many alarms collapse to few incidents (target consistent with **alarm-reduction ≥ 5×**) | correlation-engine | alarm-reduction ratio = totalAlarmsProcessed / totalIncidentsCreated from `GET /stats` |
| P3-5 | **Live operator visibility**: real-time streaming view (alarms ingesting + lifecycle transitions + incidents forming, configurable auto-refresh), incident-detail drill-down, dashboard KPIs (auto-correlation %, RCA accuracy, alarm-reduction, live incident count) | web-ui + alarm-manager + correlation-engine | streaming + incident-detail + dashboard render from the live read APIs |

**Matching realism note.** Patterns are learned (P2) from DBSCAN-cleaned, session-split
transactions but matched (P3) against the **raw, noisy live stream** (Noise Filter is idle in P3);
the Correlation Engine's matching is **noise-tolerant by design** (partial-match tolerance for
missing alarms, spurious-alarm penalty, per-`(trail,pattern)` instance windowing). The ~60% target
is therefore measured against **realistic noisy live conditions**, not cleaned data.

**P3 demonstrates:** real-time correlation power — watching raw alarms turn into a small set of
root-caused incidents live, with the supporting stats that quantify the value.

---

## Roadmap goals (built for, not built now)

| Goal | How the design supports it |
|------|----------------------------|
| **Extend to new domains** (fixed access, RAN, transport, …) | Author the domain's vocabulary/fault-origins/templates/trail-policy/params as **Knowledge records** + a **Simulator domain pack**; the event model is domain-agnostic; services operate generically. **No re-architecture.** |
| **Cross-domain correlation** | Structurally provisioned: domain on snapshots/trails/codebooks/patterns; cross-domain edges authorable in Knowledge. MVP runs single-domain (Core IP); the structure does not preclude cross-domain. |
| **Multiple alarm streams / sources** | Enrichment's per-source ruleset is a full independent pipeline per stream (mapping + filter/dedup/flap/chatter, keyed by source, concurrent). MVP ships one (`core-ip`) profile; N profiles + explicit source/domain-based selection is a documented extension. |
| **Reproducible / curated datasets** | Simulator **ingest mode** replays pre-created topology + alarm-corpus + labels files (skip generation); **export** round-trips a generated run to files. Enables fixed demo datasets and replaying captured/curated corpora. |

---

## Measurement & oracle

All rates are measured against the **Simulator ground-truth labels** (the eval oracle):
`{rootCause, rootCauseAlarmType, children[]}` per injected scenario, with noise alarms tagged.
The owned integration-threshold metrics — **noise-filter removal ≥ 0.90, retention ≥ 0.95,
pattern-quality ≥ 0.80, RCA accuracy ≥ 0.80, alarm-reduction ≥ 5×** — plus the derived
**auto-handled fraction (P2)** and **auto-correlation % (P3)** are the quantitative pass criteria.
The web-ui surfaces the live-demonstrable subset (auto-correlation %, RCA accuracy, alarm-reduction,
incident/pattern counts) so the outcomes are **evident**, not just computed.

> **Status of targets.** These are indicative MVP demonstration targets. The hardened design has
> been verified capable of achieving them (`docs/mvp-achievability.md`); exact values are tuned and
> confirmed during build + integration against the oracle. They guide the build — they are not
> additional contracts.
