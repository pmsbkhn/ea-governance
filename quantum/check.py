#!/usr/bin/env python3
"""Whole-graph quantum sync-boundary check — centralized, static, repo-independent.

The per-service `quantumSyncBoundary` fitness (ea-archrules) runs in each *caller's* CI and only sees
that caller's code. This check sees the WHOLE estate at once: it scans every governed system's
synchronous outbound clients (the `..outbound/client/*ClientOa` convention) across all repos and
checks each against that system's declared `allowedSyncQuanta` in the registry. One place catches any
bounded context that synchronously calls a quantum it wasn't granted — even a service that doesn't run
its own fitness CI.

  python3 quantum/check.py --workspace ~/Projects [--registry ../registry] [--emit out.ndjson]

`--workspace` is a directory containing the governed repos checked out by their repo name
(e.g. <workspace>/marketplace-aigen, <workspace>/msfw). Exits non-zero on any cross-quantum violation.
"""
import argparse
import glob
import json
import os
import sys
from datetime import datetime, timezone

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: pip install pyyaml")

CLIENT_SUFFIX = "ClientOa.java"


def load_registry(registry_dir):
    reg = {}
    for f in sorted(glob.glob(os.path.join(registry_dir, "*.yaml"))):
        doc = yaml.safe_load(open(f)) or {}
        meta = doc.get("metadata") or {}
        spec = doc.get("spec") or {}
        sid = meta.get("id")
        if not sid:
            continue
        q = spec.get("quantum") or {}
        reg[sid] = {
            "repo": meta.get("repo"),
            "quantum": (q.get("id") or sid),
            "allowed": {str(x).strip().lower() for x in (q.get("allowedSyncQuanta") or [])},
            "pnl": spec.get("pnl", "?"),
        }
    return reg


def find_sync_clients(source_dir):
    """[(target_quantum, file)] for every ..outbound/client/<Quantum>ClientOa.java under source_dir."""
    pattern = os.path.join(source_dir, "**", "outbound", "client", "*" + CLIENT_SUFFIX)
    found = []
    for f in glob.glob(pattern, recursive=True):
        target = os.path.basename(f)[:-len(CLIENT_SUFFIX)].lower()
        found.append((target, f))
    return found


def main():
    ap = argparse.ArgumentParser(description="Whole-graph quantum sync-boundary check")
    ap.add_argument("--workspace", required=True, help="dir holding the governed repos by repo name")
    ap.add_argument("--registry", default=str(os.path.join(os.path.dirname(__file__), "..", "registry")))
    ap.add_argument("--emit", help="write FitnessResult NDJSON here")
    args = ap.parse_args()

    reg = load_registry(args.registry)
    now = datetime.now(timezone.utc).isoformat()
    events, violations, scanned, missing = [], 0, 0, []

    for sid, info in sorted(reg.items()):
        if not info["repo"]:
            continue
        src = os.path.join(args.workspace, info["repo"])      # <workspace>/<org-repo>/<service-subdir>
        if not os.path.isdir(src):
            missing.append(sid)
            continue
        scanned += 1
        clients = find_sync_clients(src)
        bad = [(t, f) for (t, f) in clients
               if t not in info["allowed"] and t != info["quantum"]]
        verdict = "fail" if bad else "pass"
        violations += len(bad)
        events.append({"schemaVersion": "1", "system": sid, "rule": "quantumGraphSyncBoundary",
                       "layer": "code", "verdict": verdict, "mode": "enforce", "violations": len(bad),
                       "waiverExpires": None, "ts": now, "source": "quantum-graph"})
        mark = "OK" if not bad else "FAIL"
        print(f"{sid:28} {len(clients)} client(s) · allowed={sorted(info['allowed'])} -> {mark}")
        for target, f in bad:
            print(f"    x sync -> quantum '{target}' not granted  ({os.path.relpath(f, args.workspace)})")

    if missing:
        print("\nnot scanned (repo not in workspace): " + ", ".join(missing))
    print(f"\n{scanned} system(s) scanned · {violations} cross-quantum violation(s).")

    if args.emit:
        with open(args.emit, "w") as fh:
            fh.write("\n".join(json.dumps(e) for e in events) + "\n")
        print(f"verdicts -> {args.emit}")

    sys.exit(1 if violations else 0)


if __name__ == "__main__":
    main()
