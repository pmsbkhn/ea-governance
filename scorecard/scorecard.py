#!/usr/bin/env python3
"""EA conformance scorecard collector.

Reads the fitness-result verdict stream (NDJSON emitted by the ea-archrules harness with
``-Dea.fitness.sink=stdout``, and by any dynamic/SLO exporter using the same contract) and the
registry, joins them on ``system`` to resolve owner / domain / P&L, and reports conformance per
system and per P&L. Cohort-agnostic: point it at any set of NDJSON artifacts and the registry.

  python3 scorecard.py results.ndjson [more.ndjson ...] [--registry ../registry] [--html out.html]

Exits non-zero if any enforced violation (verdict "fail") is present, so it doubles as an aggregate
gate. See docs/fitness-result.md for the event contract.
"""
import argparse
import collections
import glob
import json
import sys
from datetime import date, datetime
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: pip install pyyaml")

VERDICTS = ["pass", "warn", "fail", "waived", "skipped"]


def load_registry(registry_dir):
    """system id -> {owner, domain, pnl, stack}."""
    reg = {}
    for path in sorted(Path(registry_dir).glob("*.yaml")):
        doc = yaml.safe_load(path.read_text()) or {}
        sid = (doc.get("metadata") or {}).get("id")
        spec = doc.get("spec") or {}
        if sid:
            reg[sid] = {k: spec.get(k, "?") for k in ("owner", "domain", "pnl", "stack")}
    return reg


def load_events(paths):
    events = []
    for pattern in paths:
        for fname in glob.glob(pattern):
            with open(fname) as fh:
                for line in fh:
                    line = line.strip()
                    if line:
                        events.append(json.loads(line))
    return events


def applicable(counter):
    """Evaluations that could pass (excludes n/a)."""
    return sum(counter.values()) - counter["skipped"]


def conformance(counter):
    app = applicable(counter)
    return 100.0 if app == 0 else 100.0 * counter["pass"] / app


def status(counter):
    if counter["fail"]:
        return "FAIL"
    if counter["warn"] or counter["waived"]:
        return "DEBT"
    return "CLEAN"


def rollup(events, key):
    out = collections.defaultdict(collections.Counter)
    for e in events:
        out[key(e)][e["verdict"]] += 1
    return out


def fmt_row(name, c, width):
    return (f"{name:<{width}} " + " ".join(f"{c[v]:>4}" for v in VERDICTS)
            + f"  {conformance(c):5.0f}%  {status(c)}")


def report(events, reg, html_path):
    by_system = rollup(events, lambda e: e["system"])
    pnl_of = {sid: reg.get(sid, {}).get("pnl", "?") for sid in by_system}
    by_pnl = rollup(events, lambda e: pnl_of.get(e["system"], "?"))
    total = collections.Counter()
    for c in by_system.values():
        total.update(c)

    width = max([len(s) for s in by_system] + [len("system"), 22])
    head = f"{'system':<{width}} " + " ".join(f"{v[:4]:>4}" for v in VERDICTS) + "  conf   status"
    print("\n" + head)
    print("-" * len(head))
    for sid in sorted(by_system):
        print(fmt_row(sid, by_system[sid], width))
    print("-" * len(head))
    print(fmt_row("COHORT", total, width))

    print("\nby P&L:")
    for pnl in sorted(by_pnl):
        print("  " + fmt_row(pnl, by_pnl[pnl], width - 2))

    # registry coverage — systems that exist but reported nothing this run
    missing = [s for s in reg if s not in by_system]
    if missing:
        print("\nnot reported (in registry, no verdicts this run):")
        for s in sorted(missing):
            print(f"  ! {s}")

    fails = [e for e in events if e["verdict"] == "fail"]
    if fails:
        print("\nENFORCED VIOLATIONS:")
        for e in fails:
            print(f"  x {e['system']} / {e['rule']} ({e.get('violations', '?')} violation(s))")

    waivers = sorted([e for e in events if e["verdict"] == "waived" and e.get("waiverExpires")],
                     key=lambda e: e["waiverExpires"])
    if waivers:
        print("\nactive waivers (soonest expiry first):")
        today = date.today()
        for e in waivers:
            exp = datetime.fromisoformat(e["waiverExpires"]).date()
            days = (exp - today).days
            mark = " <= EXPIRED" if days < 0 else (" <= <30d" if days < 30 else "")
            print(f"  ~ {e['system']} / {e['rule']} until {e['waiverExpires']} ({days}d){mark}")

    app = applicable(total)
    print(f"\n{round(conformance(total))}% conformant "
          f"({total['pass']}/{app} applicable clean) · {total['fail']} enforced violation(s) · "
          f"{len(events)} evaluations across {len(by_system)} system(s).")

    if html_path:
        write_html(html_path, by_system, by_pnl, reg, total)
        print(f"\nHTML scorecard → {html_path}")

    return total["fail"]


def write_html(path, by_system, by_pnl, reg, total):
    def cells(c):
        return "".join(f"<td>{c[v]}</td>" for v in VERDICTS)

    def badge(c):
        s = status(c)
        color = {"CLEAN": "#1d9e75", "DEBT": "#ba7517", "FAIL": "#e24b4a"}[s]
        return f'<span style="color:#fff;background:{color};padding:2px 8px;border-radius:6px">{s}</span>'

    rows = "".join(
        f"<tr><td><code>{s}</code></td><td>{reg.get(s, {}).get('pnl', '?')}</td>"
        f"<td>{reg.get(s, {}).get('domain', '?')}</td>{cells(by_system[s])}"
        f"<td>{conformance(by_system[s]):.0f}%</td><td>{badge(by_system[s])}</td></tr>"
        for s in sorted(by_system))
    pnl_rows = "".join(
        f"<tr><td>{p}</td>{cells(by_pnl[p])}<td>{conformance(by_pnl[p]):.0f}%</td></tr>"
        for p in sorted(by_pnl))
    vh = "".join(f"<th>{v}</th>" for v in VERDICTS)
    Path(path).write_text(f"""<!doctype html><meta charset=utf-8>
<title>EA conformance scorecard</title>
<style>
 body{{font:15px/1.6 system-ui,sans-serif;max-width:920px;margin:2rem auto;padding:0 1rem;color:#1a1a1a}}
 h1{{font-size:22px;font-weight:500}} h2{{font-size:16px;font-weight:500;margin-top:2rem}}
 table{{border-collapse:collapse;width:100%;font-size:13px;margin-top:.5rem}}
 th,td{{padding:6px 10px;border-bottom:1px solid #eee;text-align:right}}
 th:first-child,td:first-child,th:nth-child(2),td:nth-child(2),th:nth-child(3),td:nth-child(3){{text-align:left}}
 .big{{font-size:30px;font-weight:500}} code{{font-family:ui-monospace,monospace}}
</style>
<h1>EA architecture conformance</h1>
<p class=big>{round(conformance(total))}% conformant
<span style="font-size:15px;color:#666">· {total['fail']} enforced violation(s) · {sum(total.values())} evaluations</span></p>
<h2>By system</h2>
<table><tr><th>system</th><th>P&amp;L</th><th>domain</th>{vh}<th>conf</th><th>status</th></tr>{rows}</table>
<h2>By P&amp;L</h2>
<table><tr><th>P&amp;L</th>{vh}<th>conf</th></tr>{pnl_rows}</table>
<p style="color:#888;font-size:12px;margin-top:2rem">Generated from the fitness-result verdict stream
(ea-archrules contract v1). conf% = pass / applicable (excludes n/a).</p>
""")


def to_exposition(events, reg):
    """Render the verdict stream as Prometheus text — gauges keyed by system/P&L so the existing
    Grafana/Prometheus can chart conformance alongside the runtime msfw.* metrics."""
    by_system = rollup(events, lambda e: e["system"])
    pnl = {s: reg.get(s, {}).get("pnl", "?") for s in by_system}
    dom = {s: reg.get(s, {}).get("domain", "?") for s in by_system}
    out = []

    def header(name, help_, typ="gauge"):
        out.append(f"# HELP {name} {help_}")
        out.append(f"# TYPE {name} {typ}")

    header("ea_fitness_evaluations", "Fitness evaluations by verdict")
    for s, c in by_system.items():
        for v in VERDICTS:
            out.append(f'ea_fitness_evaluations{{system="{s}",pnl="{pnl[s]}",'
                       f'domain="{dom[s]}",verdict="{v}"}} {c[v]}')

    header("ea_conformance_ratio", "Pass / applicable per system (0..1)")
    for s, c in by_system.items():
        out.append(f'ea_conformance_ratio{{system="{s}",pnl="{pnl[s]}"}} {conformance(c) / 100:.4f}')

    header("ea_enforced_violations", "Enforced (fail) verdicts per system")
    for s, c in by_system.items():
        out.append(f'ea_enforced_violations{{system="{s}",pnl="{pnl[s]}"}} {c["fail"]}')

    total = collections.Counter()
    for c in by_system.values():
        total.update(c)
    header("ea_cohort_conformance_ratio", "Cohort pass / applicable (0..1)")
    out.append(f"ea_cohort_conformance_ratio {conformance(total) / 100:.4f}")
    header("ea_systems_reported", "Systems that reported verdicts this run")
    out.append(f"ea_systems_reported {len(by_system)}")

    waivers = [e for e in events if e["verdict"] == "waived" and e.get("waiverExpires")]
    if waivers:
        header("ea_waiver_days_remaining", "Days until a waiver expires (negative = expired)")
        today = date.today()
        for e in waivers:
            days = (datetime.fromisoformat(e["waiverExpires"]).date() - today).days
            out.append(f'ea_waiver_days_remaining{{system="{e["system"]}",'
                       f'rule="{e["rule"]}"}} {days}')
    return "\n".join(out) + "\n"


def push_prometheus(url, text):
    import urllib.request
    endpoint = url.rstrip("/") + "/metrics/job/ea_fitness"
    req = urllib.request.Request(endpoint, data=text.encode(), method="PUT",
                                 headers={"Content-Type": "text/plain"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.status


def main():
    ap = argparse.ArgumentParser(description="EA conformance scorecard collector")
    ap.add_argument("ndjson", nargs="+", help="fitness-result NDJSON file(s) / globs")
    ap.add_argument("--registry", default=str(Path(__file__).parent.parent / "registry"),
                    help="registry directory (default: ../registry)")
    ap.add_argument("--html", help="also write a static HTML scorecard to this path")
    ap.add_argument("--pushgateway", metavar="URL",
                    help="PUT conformance metrics to this Prometheus Pushgateway "
                         "(e.g. http://localhost:9091), under job=ea_fitness")
    args = ap.parse_args()

    events = load_events(args.ndjson)
    if not events:
        sys.exit("no verdict events found in: " + " ".join(args.ndjson))
    reg = load_registry(args.registry)
    failures = report(events, reg, args.html)

    if args.pushgateway:
        status = push_prometheus(args.pushgateway, to_exposition(events, reg))
        print(f"\nPushed metrics to {args.pushgateway} (job=ea_fitness, HTTP {status})")

    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
