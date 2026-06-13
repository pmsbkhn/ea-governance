# Fitness Function Catalog

The single source of truth for every architectural guardrail across the estate: what it asserts,
how it's classified, where it runs, and whether it is in `warn` or `enforce` mode. Making the
implicit explicit is the management backbone — tooling comes after.

> For each function's **type (Ford), deployment location, and lifecycle phase** (commit → CI →
> deploy gate → production), see [`docs/FITNESS_LIFECYCLE.md`](docs/FITNESS_LIFECYCLE.md).

Classification follows *Building Evolutionary Architectures* (Ford/Parsons/Kua):
**atomic ↔ holistic** · **static ↔ dynamic** · **triggered ↔ continuous**.

## Registered guardrails

| ID | What it asserts | Class | Runs | Mode | Owner |
|---|---|---|---|---|---|
| `domainIsPure` | `…domain..` depends on nothing outward (app/adapter/Spring/Jakarta/msfw-infra) | atomic · static · triggered | service CI (ArchUnit, `archrules`) | enforce | platform-arch |
| `useCaseSliceIsolation` | vertical use-case slices don't cross-depend | atomic · static · triggered | service CI | enforce* | platform-arch |
| `aggregateEncapsulation` | no public `setX` on `Aggregate` subtypes | atomic · static · triggered | service CI | enforce | platform-arch |
| `entityEncapsulation` | no public `setX` on `Entity` subtypes | atomic · static · triggered | service CI | enforce | platform-arch |
| `stateWritersPublish` | `*Uc` methods that `save`/`delete` carry `@EventPublishHandler` | atomic · static · triggered | service CI | enforce | platform-arch |
| `msfwModuleGraph` | msfw module purity (domain-core ∌ Spring; producer ∌ consumer) | atomic · static · triggered | msfw CI (planned) | warn | platform |
| `jsonEventContract` | producer wire-envelope ↔ consumer DTO bind (fixtures) | holistic · static · triggered | service CI (existing `JsonEventContract`) | enforce | platform |
| `outboxParked` / `pollerStuck` / `deadLettering` / `workflowCompensationFailed` | runtime invariants of the msfw metric set | holistic · dynamic · **continuous** | Prometheus alerts (`ops/observability`) | enforce | platform-ops |
| `manifestPolicy` | k8s manifests: resource limits, no :latest, probes, scrape annotation | atomic · static · triggered | OPA/conftest (`make policy`); Kyverno admission (planned) | deny+warn | platform-ops |

\* `enforce` where the package convention supports it (see finding F-2).

## Status (Phase 0 + 1)

- **`archrules` library built** (`tech.vsf.ea:ea-archrules:0.1.0`), zero compile-dep on msfw —
  `FitnessRules` (generic) + `MsfwFitness` (cohort profile, FQN strings).
- **marketplace/order migrated** to the library: 5 `@ArchTest` bindings replace ~95 lines of
  copied rules — **green**. The 24 copied rules across the 6 marketplace services collapse to one
  source (rollout pending).
- **ecommerce/inventory** (had zero fitness functions) now runs the shared slice rule and **caught
  the real debt** it was meant to: `goodsflow → placeholder.KafkaDirectPublisher`.


## Compliance baseline (Phase 1 rollout — 10 systems)

| System | Result | Note |
|---|---|---|
| msfw (domain-core purity) | ✅ pass | `msfwModuleGraph` now wired in domain-core's build |
| marketplace × 6 (order, catalog, payment, inventory, notification, checkout) | ✅ pass | 24 copied rules → 6 lib bindings (DRY collapse complete) |
| ecommerce/catalog | ✅ pass | had zero fitness functions before; clean |
| ecommerce/order-management | ⚠️ 1 violation | F-4 |
| ecommerce/inventory | ⚠️ 1 violation | F-1 (waiver) |

8/10 clean; 2 real violations surfaced — exactly the discovery value of the rollout. Both sit in
`warn` mode in the registry with a recorded waiver until fixed; flipping to `enforce` is the
Phase-2 promotion.

## Findings (feed the backlog / operating model)

- **F-1 — real violation:** `ecommerce/inventory` `goodsflow` use-cases depend on the temporary
  `placeholder.KafkaDirectPublisher` stub (msfw-migration debt). Recorded as a time-boxed **waiver**
  on that system; `useCaseSliceIsolation` kept at `warn` there until removed.
- **F-2 — cross-P&L convention divergence:** marketplace keeps slices under `…application.<slice>`
  (slice-isolation enforceable cleanly); ecommerce keeps slices as **siblings of `domain`** under
  the service root, so the slice matcher also treats `domain` as a peer slice → false positives
  (`goodsflow → domain`). Enterprise implication: a fitness function is only as portable as the
  conventions it assumes — the registry must record each system's **package-convention profile**.
  **Resolved in lib:** `useCaseSlices(matcher, sharedPackages...)` ignores dependencies whose
  target is a shared layer (domain/common) — ecommerce/inventory now reports exactly the one real
  edge (`goodsflow → placeholder`), false positives gone, so the rule can be enforced there too.
- **F-4 — real violation (rollout discovery):** `ecommerce/order-management`
  `RegisterProductUc.execute` writes state via the repository but is **not** annotated
  `@EventPublishHandler` — a state change that does not go through the outbox (lost-event risk).
  Recorded as a waiver; `stateWritersPublish` kept at `warn` there until fixed.
- **F-5 — real violation (harness discovery):** `ecommerce/inventory` `CreateStockReceiptUc` and
  `RegisterProductUc` write state via the repository without `@EventPublishHandler` (same class as
  F-4). Surfaced by the Phase-2 harness. **Fixed** — both use-cases now carry `@EventPublishHandler`
  (inventory's adapter already wires OutboxConfiguration); `stateWritersPublish` back to enforce.
- **F-3 — msfw self-governance gap:** msfw's clean module graph is held by convention only; the
  `msfwModuleGraph` guardrail now runs inside msfw's `domain-core` build (consuming this lib) — **wired, green**. Producer/consumer split check still pending (needs an aggregator module).
