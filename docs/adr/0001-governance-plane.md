# ADR 0001 — The governance plane is separate from production observability

- **Status:** Accepted (2026-06-13)
- **Deciders:** platform / EA
- **Context tags:** evolutionary-architecture, observability, trust-boundary

## Context

The EA program emits one verdict per fitness-function evaluation in a single shape — the
[`FitnessResult` contract](../fitness-result.md) (`system / rule / layer / verdict / mode / …`).
Two sources produce it:

- **static / CI-time** — ArchUnit (`ea-archrules` harness) and policy gates, run per build. They have
  no long-lived process to scrape; a verdict exists once per build.
- **dynamic / runtime** — SLO evaluation over live metrics (latency, lag, error budget). These derive
  from metrics that already live in the production observability stack.

The local experiment visualised conformance by standing up a **Pushgateway inside the cluster** and
an **"EA Architecture Conformance" Grafana dashboard**, with a local `scorecard.py --pushgateway`
push. That was useful — it proved the contract works end-to-end — but it has two architectural
problems that must not carry into production:

1. **Wrong home for the data.** Conformance is *governance* data. Putting it in the *production
   observability plane* (Prometheus/Grafana) conflates two concerns. Prometheus should hold only what
   it can **scrape** from running workloads; Pushgateway-for-conformance is also a known stale-metric
   anti-pattern.
2. **Trust boundary.** Automating the local shortcut by having **CI push into the production
   cluster** would let the build plane reach into the runtime plane — the wrong direction across a
   boundary that is (and should be) closed in production. The local demo sidesteps this only because
   the push runs from a local terminal, not from CI.

## Decision

Treat the **governance plane as a distinct subsystem**, separate from any environment's production
observability, fed by the existing verdict contract.

1. **Static / CI-time verdicts go to a durable governance store**, not to production Prometheus. CI
   writes `FitnessResult` events to a platform-owned store (a table / object store / event store).
   CI **never** pushes into a production cluster.
2. **Runtime / SLO fitness stays in production Prometheus/Grafana** (scraped) for ops, **and** an SLO
   evaluator emits the same `FitnessResult` (`layer: runtime`) to the governance store, so static and
   runtime conformance unify in one place.
3. **The portal is the home for conformance** — Backstage Software Catalog + Tech Insights /
   Scorecards, keyed on the registry `system` id. The registry remains the SSOT (today YAML; at scale
   it graduates into the catalog).
4. **The two planes meet only at the governance store / portal**, never inside a production cluster.
5. **The local Pushgateway + Grafana EA dashboard are scaffolding**, retained for local demos only and
   retired once the governance plane exists. The `FitnessResult` contract is unchanged — only the
   *destination* of static verdicts moves (Pushgateway → governance store).

```
 build plane                          governance plane                     runtime plane (per env)
 ───────────                          ────────────────                     ──────────────────────
 CI (ea-archrules / OPA)
   └─ FitnessResult ───────────────▶  verdict store ──▶ Backstage
                                          ▲             Tech Insights / Scorecards
 SLO evaluator                            │                 (keyed on registry id)
   ▲                                      │
   └── prod Prometheus/Grafana ───────────┘   (runtime metrics scraped here, stay here for ops)
       (scrape from workloads)
```

## Consequences

**Positive**
- Clean separation of concerns: prod observability holds only scrapeable runtime signal; governance
  data lives in a governance-owned store.
- Trust boundary preserved: no CI → prod-cluster path.
- One conformance view (static + runtime) in the portal, because both sources share the contract.
- The estate-scale story (registry → Backstage) is now the explicit target, not an accident.

**Costs / negative**
- A new subsystem to build and operate (store + portal integration). Until it exists, conformance
  visibility stays at the local-demo level.
- The registry must eventually graduate from a bundled YAML to a queryable catalog.
- Runtime fitness needs per-component SLOs defined (separate work; see follow-ups).

**Follow-ups**
- Prototype a minimal governance plane locally: a durable verdict store + Backstage Tech Insights
  reading it (replaces the Pushgateway/Grafana-EA scaffolding). *(ADR 0002 candidate.)*
- Define per-component runtime objectives (SLOs) in the registry and an SLO → `FitnessResult`
  (`layer: runtime`) bridge, including drift detection (SLI vs baseline), not just absolute alerts.

## Alternatives considered

- **CI pushes verdicts into production Prometheus (the local shortcut, productionised).** Rejected:
  crosses the trust boundary, puts governance data in the wrong plane, and leans on Pushgateway (a
  stale-metric anti-pattern for non-ephemeral data).
- **Leave conformance as CI artifacts only.** Rejected: no rollup, no trend, no per-P&L view, no
  waiver-aging surface — i.e. no scorecard.
- **A central Pushgateway in the governance network (not the prod cluster).** Partial improvement
  (boundary respected) but still forces governance data into a metrics shape; an event/record store
  + portal models verdicts, trends, and waivers far better.
