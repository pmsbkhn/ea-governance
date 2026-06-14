# Whole-graph quantum check (centralized, static)

The per-service `quantumSyncBoundary` fitness (in `ea-archrules`) runs in each **caller's** CI and
only sees that caller's code — so it catches an illegal cross-quantum call **only if that caller runs
the fitness**. This check closes the gap: from **one place** (ea-governance CI) it scans **every
governed system's** synchronous outbound clients across **all repos** and checks each against the
system's `allowedSyncQuanta` in the registry.

> So "any bounded context that synchronously calls a quantum it wasn't granted" is caught here —
> independent of whether that service has its own fitness CI. The static, centralized complement to
> the (future) runtime PEP (ZTA) that would catch the *actual* calls at run time.

```bash
pip install pyyaml
# locally (repos checked out side by side under ~/Projects):
python3 quantum/check.py --workspace ~/Projects --registry registry
```

**How it maps:** each registry entry's `metadata.repo` (e.g. `marketplace-aigen/payment`) points at the
system's source under `<workspace>/<repo>`. The check finds `…/outbound/client/<Quantum>ClientOa.java`,
derives the target quantum from the class name, and fails if it isn't in that system's
`spec.quantum.allowedSyncQuanta` (and isn't the system's own quantum). Emits the `FitnessResult`
contract (`rule: quantumGraphSyncBoundary`, `source: quantum-graph`) for the scorecard; exits non-zero
on any violation.

**Convention + limits (static):** it keys on the `..outbound/client/*ClientOa` naming. A sync call made
some other way (a raw `RestClient` not following the convention) is *not* seen — that's what the
runtime tier (ZTA PEP / trace-based) is for. It also assumes the repos are present in the workspace
(the CI clones them).

## CI

`.github/workflows/quantum-graph.yml` clones the governed repos and runs the check on every push/PR.
Requires a repo secret **`GH_REPO_PAT`** (a PAT with `contents: read` on the governed repos) to clone
them — `GITHUB_TOKEN` can't read sibling repos.

## Operating model

This whole-graph check is the **estate safety net**, complementing the caller-side `quantumSyncBoundary`
(per-PR, per repo) and the future runtime PEP (ZTA). Who owns each tier, when each runs, how the
registry's `allowedSyncQuanta` is change-controlled, and the warn→enforce / waiver process are defined in
[ADR 0002 — Quantum governance operating model](../docs/adr/0002-quantum-governance-operating-model.md).
