# Fitness Result Contract

The single shape every fitness function emits, regardless of which tier it runs in. It is the unit
the **EA portfolio scorecard** is built from: collect these events, key them by `system` (the
registry SSOT id), and you have conformance over time per service, domain, and P&L — without any tier
knowing about the dashboard.

> **Why a contract, not a dashboard query.** A scorecard that scrapes each tier's native output
> (ArchUnit reports, conftest exit codes, Prometheus series) couples the dashboard to every tool.
> One flat verdict event decouples them: tiers *emit*, the scorecard *aggregates*. Add a tier, emit
> the same shape, it shows up — no scorecard change.

Schema: [`fitness-result.schema.json`](fitness-result.schema.json) (JSON Schema 2020-12).

## The shape

```json
{
  "schemaVersion": "1",
  "system": "ecommerce-inventory",
  "rule": "useCaseSliceIsolation",
  "layer": "code",
  "verdict": "pass",
  "mode": "enforce",
  "violations": 0,
  "waiverExpires": null,
  "ts": "2026-06-13T10:00:00Z",
  "source": "ea-archrules"
}
```

| Field | Meaning |
|---|---|
| `system` | Registry id — the join key to owner / P&L / domain. |
| `rule` | Fitness-function id (`useCaseSliceIsolation`, `latencySloP99`, …). |
| `layer` | `code` (CI ArchUnit/OPA) · `contract` (JSON/consumer-driven) · `runtime` (production SLO). |
| `verdict` | `pass` · `warn` · `fail` · `waived` · `skipped`. **`waived`/`skipped` are distinct from `pass`** so the board separates "green" from "knowingly not enforced". |
| `mode` | Registry enforcement mode (`enforce`/`warn`/`n/a`); `null` for emitters with no registry concept. |
| `violations` | 0 on pass/skipped. For a runtime SLO: 0 = within budget, ≥1 = breaching windows. |
| `waiverExpires` | ISO date (only on `waived`) — feeds the waiver-aging panel. |
| `ts` | Evaluation time, RFC 3339 UTC. |
| `source` | Emitter id (`ea-archrules`, `conftest`, `slo-exporter`). |

## Tier 1 — static (code), already wired

`ea-archrules`' `FitnessHarness` emits one result per `evaluate(...)`, on **every** path
(pass / warn / waived / fail / skipped), through a `FitnessResultSink`:

- **default** `NOOP` — computed, dropped (existing test runs stay silent until a team opts in).
- **`-Dea.fitness.sink=stdout`** (or env `EA_FITNESS_SINK=stdout`) — one line per result, prefixed
  `EA_FITNESS_RESULT `, for a CI log shipper to scrape:

  ```
  EA_FITNESS_RESULT {"schemaVersion":"1","system":"ecommerce-inventory",...}
  ```
- **custom** — register a `tech.vsf.ea.archrules.FitnessResultSink` via `ServiceLoader`
  (`META-INF/services/...`) to push straight to a Pushgateway or a results table; a registered sink
  wins over the property switch.

A CI step then ships the tagged lines, e.g.:

```bash
mvn -B test -Dea.fitness.sink=stdout \
  | grep '^EA_FITNESS_RESULT ' | sed 's/^EA_FITNESS_RESULT //' > fitness-results.ndjson
# → forward to the scorecard store (Loki/Elasticsearch/a table/Pushgateway adapter)
```

The OPA/conftest gate (`marketplace-aigen/deploy/policy`) is the same tier: wrap `conftest` so each
policy result prints a `source:"conftest"` line in this shape.

## Tier 3 — dynamic (runtime), the SLO bridge

An SLO **is** a continuous fitness function; turn its burn into the same verdict. A small exporter
(or a Prometheus recording-rule → webhook) evaluates the budget and emits:

```json
{
  "schemaVersion": "1",
  "system": "marketplace-order",
  "rule": "latencySloP99",
  "layer": "runtime",
  "verdict": "fail",
  "mode": null,
  "violations": 2,
  "waiverExpires": null,
  "ts": "2026-06-13T10:05:00Z",
  "source": "slo-exporter"
}
```

Mapping convention: **within budget → `pass`, burning fast / window breached → `fail`** (use `warn`
for an early-warning burn rate). `violations` = number of breaching windows. `system`/`rule` come
from the SLO definition; reuse the registry id so it joins to the same row as the code-tier results.

## Roll-up

The scorecard reads the event stream and, joined on `system` against the registry, renders:

- **conformance %** per system / domain / P&L (share of rules `pass`),
- **arch-debt trend** (`warn` + `waived` counts over time),
- **waiver aging** (`waiverExpires` approaching / passed),
- **SLO compliance** (runtime-layer `pass` rate).

At enterprise scale this is Backstage Software Catalog + Scorecards over the same registry — the
contract here is what feeds it; nothing above the emitters changes when the store does.
