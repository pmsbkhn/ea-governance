# EA Governance

Independent home for the **evolutionary-architecture** program — the fitness functions, the
registry, and the operating model that govern the system estate.

> **New here?** [`docs/SYSTEM_VIEW.md`](docs/SYSTEM_VIEW.md) is the one-page bird's-eye view of the
> governance program — the three planes (build / governance / runtime), the verdict flow, and a
> component catalogue. For the **whole estate** (business runtime + platform + observability +
> tracing + governance + CI) in one diagram, see [`docs/ESTATE_LANDSCAPE.md`](docs/ESTATE_LANDSCAPE.md).

## Why this is its own repo

Governance must not be owned by the things it governs. This repo therefore:

- **Depends on nothing it audits.** The rule library (`archrules/`) references every framework
  concept — annotations, base types, package conventions — by fully-qualified **name string**, so
  it has *zero compile dependency* on msfw or any service. Dependency direction is always
  **governed → governance**, never the reverse.
- **Spans repos and stacks.** Guardrails already live in three places (ArchUnit in services, alert
  rules in `deploy/`, JSON contract fixtures). The catalog here is their single source of truth —
  it belongs to no single artifact.

## Independence is two decisions, not one

1. **Independence of dependency** (zero compile-dep, name-string references) — adopted now, always.
2. **Independence of packaging** (separate published artifact + release cadence) — *earned*, not
   assumed. During the local experiment the rule lib is consumed via `mvn install` to the local
   repo; a published-artifact pipeline is deferred until a second governance domain (e.g. infra
   policy) or a second owner makes the publish/lockstep cost worth paying.

## Layout

```
archrules/   # reusable ArchUnit fitness functions (Java lib, zero-dep, FQN-string)
             #   FitnessRules   — stack-agnostic, fully parameterized
             #   MsfwFitness    — the msfw-cohort profile (binds msfw FQNs as strings)
             #   FitnessHarness — registry-driven warn→enforce; emits a FitnessResult per evaluation
             #   FitnessResult/Sink — the verdict contract that feeds the portfolio scorecard
registry/    # one YAML per system: owner / domain / P&L / stack / fitness-functions + mode / waivers
             #   + runtimeObjectives (SLOs) + quantum.{id, allowedSyncQuanta} (the quantum boundary SSOT)
quantum/     # whole-graph quantum check: check.py clones the estate and enforces cross-quantum sync
             #   edges against the registry from one place (the estate-wide safety net)
governance-plane/ # durable verdict store + scorecard API + slo-evaluator CronJob (ns: governance)
scorecard/   # NDJSON verdicts + registry → per-P&L conformance report / HTML / Pushgateway
docs/        # fitness-result.md + .schema.json (verdict contract); SYSTEM_VIEW / FITNESS_LIFECYCLE; ADRs
FITNESS.md   # the catalog: every guardrail, its Ford classification, where it runs, its warn/enforce state
```

The scorecard that turns all this into a per-P&L conformance view is fed by one flat verdict event
emitted on every evaluation — static (this harness) and dynamic (SLO) tiers share the same shape.
See [`docs/fitness-result.md`](docs/fitness-result.md).

Where those verdicts *land* (and why the local Pushgateway/Grafana view is scaffolding, not the
target) is decided in [`docs/adr/0001-governance-plane.md`](docs/adr/0001-governance-plane.md): the
governance plane is a subsystem separate from production observability — CI never pushes into a prod
cluster.

## Trajectory (local experiment → 700-system estate)

- **Now (msfw cohort):** rules embedded-feel via local install, short feedback; registry as YAML.
- **Later (enterprise):** rule artifacts published centrally + framework-agnostic profiles per
  stack; registry graduates to a developer portal (Backstage); runtime enforcement (OPA/Kyverno)
  at the platform — the only layer that scales to a heterogeneous estate without per-team buy-in;
  federated governance (central baseline + per-P&L extensions), first-class waivers, cohort-staged
  `warn → enforce`.
