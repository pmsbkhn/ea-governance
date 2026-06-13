# Governance plane (prototype)

The durable home for conformance verdicts — the system [ADR 0001](../docs/adr/0001-governance-plane.md)
says we were missing. A dependency-free service that ingests the [`FitnessResult`](../docs/fitness-result.md)
verdict stream into a durable store and serves a scorecard, deployed in its **own `governance`
namespace, separate from prod observability** (`infra`/Prometheus). It replaces the local
Pushgateway + Grafana-EA scaffolding: conformance is governance data, it lands here — not in prod
Prometheus.

```
POST /verdicts        ingest FitnessResult events (NDJSON or JSON array) → durable SQLite (PVC)
GET  /                HTML scorecard: latest verdict per (system, rule), rolled up by system & P&L
GET  /api/conformance JSON rollup — the Backstage-ready shape a Tech Insights fact retriever reads
GET  /healthz         liveness/readiness
```

The store is **append-only** (history kept → enables trend later); the scorecard shows the *latest*
verdict per `(system, rule)`. Registry coverage (systems with no verdicts) and waiver aging are
surfaced. `conf% = pass / applicable` (excludes n/a).

## Deploy (local k3d)

```bash
./deploy.sh                       # build ConfigMaps (server.py + registry→JSON), apply, roll out
kubectl -n governance port-forward svc/governance-plane 8080:8080
open http://localhost:8080        # the scorecard
```

## Feed it verdicts

The verdict stream comes from CI artifacts (`fitness-results.ndjson`) or a local collect run:

```bash
# from a CI artifact you downloaded, or /tmp/mp-fitness.ndjson from a local collect:
./deploy.sh push /path/to/fitness-results.ndjson
# or directly:
curl -X POST --data-binary @fitness-results.ndjson http://localhost:8080/verdicts
```

Per ADR 0001, **CI must not push into a production cluster**. In production the verdict store is a
platform-owned service; CI writes to it over a controlled path, and the portal (Backstage Tech
Insights) reads `/api/conformance`. This prototype stands in for that store + portal locally.

## Why a separate namespace, not `infra`

`infra` is the production-observability plane (Prometheus scrapes workloads there). The governance
plane is a different concern with a different owner and trust boundary — so it lives in `governance`,
is **not** a Prometheus scrape target, and holds verdicts as records (queryable, trend-able,
waiver-aware) rather than as scraped gauges.

## Not yet (follow-ups)

- Swap the built-in HTML for **Backstage Tech Insights / Scorecards** reading `/api/conformance`.
- Auth on `POST /verdicts` (a write token) once it leaves a single-machine demo.
- Runtime/SLO verdicts (`layer: runtime`) ingested alongside static ones (same contract).
