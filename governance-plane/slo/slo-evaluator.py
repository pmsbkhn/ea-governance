#!/usr/bin/env python3
"""Runtime/SLO fitness evaluator (ADR 0001, dynamic tier).

Reads each system's `runtimeObjectives` from the registry, runs the PromQL against the production
Prometheus, turns each SLO into a `FitnessResult` (`layer: runtime`), and POSTs them to the
governance plane — the SAME verdict contract the static (CI) tier emits, so structure and behaviour
unify in one scorecard. Also a cheap **drift** check: compares the current SLI to a baseline
(`offset DRIFT_OFFSET`) and emits a companion `<id>Drift` warn when it has regressed beyond
DRIFT_PCT — catching slow creep that stays within the absolute threshold.

Runs in-cluster (reads prod Prometheus, writes the governance store). Per ADR 0001 this is the right
home for the dynamic tier; the static tier writes from CI, never into a prod cluster.

Env: PROM_URL, GOVERNANCE_URL, REGISTRY_PATH, DRIFT_OFFSET (default 1h), DRIFT_PCT (default 0.5).
"""
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

PROM = os.environ["PROM_URL"].rstrip("/")
GOV = os.environ["GOVERNANCE_URL"].rstrip("/")
REGISTRY_PATH = os.environ.get("REGISTRY_PATH", "/app/registry.json")
DRIFT_OFFSET_SECONDS = int(os.environ.get("DRIFT_OFFSET_SECONDS", "3600"))
DRIFT_PCT = float(os.environ.get("DRIFT_PCT", "0.5"))


def prom(query, at=None):
    """Evaluate `query` (optionally at a past unix time `at`) → single scalar, or None on
    no-series / NaN / query error. Resilient: a bad query never aborts the whole run."""
    params = {"query": query}
    if at is not None:
        params["time"] = str(at)
    url = PROM + "/api/v1/query?" + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            result = json.load(resp).get("data", {}).get("result", [])
    except (urllib.error.URLError, ValueError):
        return None
    if not result:
        return None
    try:
        v = float(result[0]["value"][1])
    except (ValueError, KeyError, IndexError):
        return None
    return None if v != v else v  # NaN guard


def slo_verdict(val, obj):
    if val is None:
        return "skipped", 0
    thr = float(obj["threshold"])
    warn = float(obj.get("warnAt", thr))
    if val >= thr:
        return "fail", 1
    if val >= warn:
        return "warn", 0
    return "pass", 0


def event(system, rule, verdict, violations, ts):
    return {"schemaVersion": "1", "system": system, "rule": rule, "layer": "runtime",
            "verdict": verdict, "mode": None, "violations": violations, "waiverExpires": None,
            "ts": ts, "source": "slo-evaluator"}


def main():
    reg = json.load(open(REGISTRY_PATH))
    now_dt = datetime.now(timezone.utc)
    now = now_dt.isoformat()
    baseline_at = now_dt.timestamp() - DRIFT_OFFSET_SECONDS
    events = []
    for system, spec in reg.items():
        for obj in (spec.get("runtimeObjectives") or []):
            val = prom(obj["query"])
            verdict, violations = slo_verdict(val, obj)
            events.append(event(system, obj["id"], verdict, violations, now))
            shown = "no-data" if val is None else round(val, 4)
            print(f"{system}/{obj['id']}: {shown} vs {obj['threshold']} -> {verdict}", flush=True)
            # drift: same query evaluated at a past time. No-op when there is no history yet, or
            # when the SLI is below `driftMin` — ratio-based drift on near-zero/idle values is noise
            # (e.g. 7ms vs 4ms is >1.5x but meaningless), so a floor keeps it from false-warning.
            drift_min = float(obj.get("driftMin", 0))
            if val is not None and val > drift_min:
                base = prom(obj["query"], at=baseline_at)
                if base and base > drift_min:
                    drifted = val > base * (1 + DRIFT_PCT)
                    # Always emit (pass or warn) so the verdict self-clears — under the latest-per-rule
                    # model a one-shot warn would otherwise linger after the drift subsides.
                    events.append(event(system, obj["id"] + "Drift", "warn" if drifted else "pass", 0, now))
                    if drifted:
                        print(f"  drift: {round(val,4)} > {round(base,4)}*(1+{DRIFT_PCT}) -> warn", flush=True)

    data = json.dumps(events).encode()
    req = urllib.request.Request(GOV + "/verdicts", data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        print(f"posted {len(events)} runtime verdict(s) -> {GOV}: HTTP {resp.status}", flush=True)


if __name__ == "__main__":
    main()
