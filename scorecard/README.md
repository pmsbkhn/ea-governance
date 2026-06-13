# Conformance scorecard collector

Turns the **fitness-result verdict stream** (the [contract](../docs/fitness-result.md) emitted by the
`ea-archrules` harness and any dynamic/SLO exporter) into a conformance view — per system, per P&L,
and cohort-wide. Cohort-agnostic: point it at any NDJSON artifacts plus the registry.

```bash
pip install pyyaml                      # one dependency

# collect a verdict stream (e.g. from a service's fitness tests)
cd <svc> && mvn test -Dtest=FitnessFunctionsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dea.fitness.sink=stdout | grep EA_FITNESS_RESULT | sed 's/^.*EA_FITNESS_RESULT //' > results.ndjson

# report (reads ../registry for owner/domain/P&L by default)
python3 scorecard/scorecard.py results.ndjson [more.ndjson ...] [--html scorecard.html]
```

## What it shows

- **per-system** counts (`pass`/`warn`/`fail`/`waived`/`n/a`), conformance %, and a status
  (`CLEAN` / `DEBT` / `FAIL`);
- **per-P&L** rollup (joined from the registry) — the slice an enterprise owner cares about;
- **registry coverage** — systems that exist in the registry but reported *no* verdicts this run
  (a silent gap is worse than a red one);
- **enforced violations** and **active waivers sorted by expiry** (aging — flags `<30d` / expired);
- `--html` writes a standalone scorecard page (no server needed yet).

`conf% = pass / applicable`, where applicable excludes `n/a` (a value-object-only model with no
Entity subtype is *covered*, not a gap). **Exits non-zero if any enforced `fail` is present**, so a
pipeline can run it over all collected artifacts as an aggregate gate.

## Where this goes

This is the local/portable form. At estate scale the same verdict stream feeds a developer portal
(Backstage Software Catalog + Scorecards) keyed on the same registry id — nothing above the emitters
changes when the store does. See the repo [README](../README.md) trajectory.
