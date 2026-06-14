# ADR 0002 — Quantum governance operating model

- **Status:** Accepted (2026-06-14)
- **Deciders:** platform / EA
- **Context tags:** evolutionary-architecture, architectural-quantum, governance-process, SDLC

## Context

We now enforce the **architectural quantum** boundary — an architect declares each quantum's permitted
synchronous neighbours in the registry (`spec.quantum.allowedSyncQuanta`, SSOT) and a fitness function
fails any synchronous cross-quantum call (`*ClientOa` in `..outbound.client..`) that the registry did
not grant. It exists at two coverage points:

- **Caller-side** (`quantumSyncBoundary`, `eventStoreInQuantum` in `ea-archrules`): runs in the calling
  repo's own CI / `mvn test`, fast per-PR feedback — but only on repos that adopted the harness.
- **Whole-graph** (`quantum/check.py` + `quantum-graph.yml` in ea-governance): clones the whole estate
  and enforces every cross-quantum edge against the registry from one place — catches violations even
  in repos that never adopted the caller-side harness.

The whole-graph check is a *holistic* fitness function (Ford taxonomy): cross-repo, registry-driven,
estate-wide. As a process it is heavier than a single-repo rule — it needs cross-repo read access, a
current registry, and a clear owner — so the question is whether and **how** to make it a standard part
of the SDLC rather than an ad-hoc script.

## Decision

Adopt the quantum boundary as a **layered, paved-road standard**, not a single heavyweight gate. The
boundary is one definition (the registry); enforcement is defense-in-depth across tiers, each with a
clear owner and cadence:

| Tier | Owner | Cadence | Role | Blocking? |
|---|---|---|---|---|
| **Caller-side** (`ea-archrules`) | the service team | every PR / `mvn test` | primary fast feedback | **yes**, on that repo |
| **Whole-graph** (`quantum/check.py`) | platform / EA (governance repo) | nightly + on registry change | estate safety net + coverage report | warn first, then estate gate |
| **Runtime PEP** (future, ZTA) | platform (separate project) | production, per call | enforce the *actual* call, language-agnostic | n/a yet |

Process rules that make it a standard:

1. **The registry is the single source of truth.** A quantum's `allowedSyncQuanta` is an architecture
   decision. Editing it is **change-controlled**: the PR that widens a quantum's allowed neighbours
   must carry (or link) an ADR/architecture note and be approved by the architect/EA reviewer — it is
   not a routine dev edit. Narrowing or holding the line needs no ADR.
2. **Onboarding is part of the paved road.** A new service registers in the registry (with `quantum.id`)
   and adopts the caller-side harness as part of scaffolding. Until it does, the whole-graph tier still
   scans it (it reads the source, not the harness), and the scorecard flags it as *registered but not
   self-checking* (coverage gap).
3. **warn → enforce rollout.** A newly-introduced or newly-tightened boundary lands in `warn` (logged,
   non-blocking) for one adoption window, then flips to `enforce`. Same engine as every other rule.
4. **Time-boxed waivers only.** A legitimate temporary cross-quantum call is granted an `expires`-dated
   waiver in the registry, which auto-reverts. No permanent silent exceptions.
5. **Convention is governed.** The detection convention (`*ClientOa` under `..outbound.client..`) and
   the registry schema are themselves versioned governance artifacts; changing them follows the same
   change-control as a rule change. Its **limit** is explicit: the static tiers only see calls that go
   through the convention — a sync call made some other way is invisible to them and is exactly what the
   runtime PEP tier will later cover.

## Consequences

- **Where it sits in the SDLC:** caller-side is a PR gate (developer's loop); whole-graph is a nightly
  estate scan + a required check on registry-change PRs (architect's loop); both feed one `FitnessResult`
  stream into the governance plane, so the quantum boundary reads in the same conformance view as every
  other guardrail. No team is asked to run the heavy whole-graph clone on every commit.
- **The heavy process is centralized, not distributed.** The cost (cross-repo PAT, cloning, keeping the
  registry current) lives with the platform/governance team as a paved-road capability — teams get
  enforcement without operating it.
- **Honest scope:** these tiers govern *source structure*. They do not prove a call cannot happen at
  runtime; that is deferred to the PEP (ZTA) tier and is intentionally out of scope here.
- **Future plan:** at estate scale this productizes into a developer-portal capability (Backstage
  Software Catalog + Tech Insights / Scorecards) — the registry becomes the catalog, the whole-graph
  check becomes a scheduled Tech Insights fact, and the runtime PEP closes the loop. The verdict contract
  is the stable seam that survives those swaps.

## Alternatives considered

- **Whole-graph as a blocking gate on every PR.** Rejected: slow (clones the estate), a central
  bottleneck, and pushes a platform concern onto every team — the anti-pattern of "one team gates
  everyone". Kept as a nightly safety net + registry-change gate instead.
- **Caller-side only.** Rejected as the *sole* mechanism: coverage depends on every repo adopting the
  harness, so a non-adopting (or non-JVM) service could violate the boundary undetected.
- **Runtime-only (PEP/ZTA).** Deferred, not chosen-instead: it enforces real calls but gives no
  build-time feedback and does not yet exist. The static tiers shift the failure left in the meantime.
