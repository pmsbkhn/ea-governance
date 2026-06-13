#!/usr/bin/env python3
"""Governance-plane prototype — the durable home for conformance verdicts (ADR 0001).

A tiny, dependency-free service that IS the governance plane in miniature:

  POST /verdicts        ingest FitnessResult events (NDJSON or JSON array) into a durable SQLite
                        store on a PVC. Append-only — history is kept (enables trend later).
  GET  /                HTML scorecard: latest verdict per (system, rule), rolled up by system and
                        P&L, joined with the registry; registry-coverage + waiver-aging surfaced.
  GET  /api/conformance JSON rollup — the Backstage-ready shape a Tech Insights fact retriever reads.
  GET  /healthz         liveness/readiness.

Deliberately NOT a Prometheus target and NOT in the prod-observability namespace: conformance is
governance data, it lands here, not in prod Prometheus (see docs/adr/0001-governance-plane.md).
"""
import json
import os
import sqlite3
from datetime import date, datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.environ.get("DB_PATH", "/data/governance.db")
REGISTRY_PATH = os.environ.get("REGISTRY_PATH", "/app/registry.json")
PORT = int(os.environ.get("PORT", "8080"))
VERDICTS = ["pass", "warn", "fail", "waived", "skipped"]


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS verdicts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                system TEXT NOT NULL, rule TEXT NOT NULL, layer TEXT, verdict TEXT NOT NULL,
                mode TEXT, violations INTEGER, waiver_expires TEXT, ts TEXT, source TEXT,
                received_at TEXT NOT NULL
            )""")
        conn.execute("CREATE INDEX IF NOT EXISTS ix_sysrule ON verdicts(system, rule, received_at)")


def load_registry():
    try:
        with open(REGISTRY_PATH) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


REGISTRY = load_registry()


def ingest(payload):
    body = payload.strip()
    events = json.loads(body) if body.startswith("[") else \
        [json.loads(line) for line in body.splitlines() if line.strip()]
    now = datetime.now(timezone.utc).isoformat()
    with db() as conn:
        for e in events:
            conn.execute(
                "INSERT INTO verdicts(system,rule,layer,verdict,mode,violations,waiver_expires,ts,source,received_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?)",
                (e.get("system"), e.get("rule"), e.get("layer"), e.get("verdict"), e.get("mode"),
                 int(e.get("violations") or 0), e.get("waiverExpires"), e.get("ts"),
                 e.get("source"), now))
    return len(events)


def latest_verdicts():
    """Newest verdict per (system, rule) — the current state, not the append-only history."""
    with db() as conn:
        rows = conn.execute("""
            SELECT v.* FROM verdicts v
            JOIN (SELECT system, rule, MAX(received_at) mx FROM verdicts GROUP BY system, rule) last
              ON v.system = last.system AND v.rule = last.rule AND v.received_at = last.mx
        """).fetchall()
    return [dict(r) for r in rows]


def counter(rows):
    c = {v: 0 for v in VERDICTS}
    for r in rows:
        if r["verdict"] in c:
            c[r["verdict"]] += 1
    return c


def applicable(c):
    return sum(c.values()) - c["skipped"]


def conformance(c):
    app = applicable(c)
    return 1.0 if app == 0 else c["pass"] / app


def status(c):
    return "FAIL" if c["fail"] else ("DEBT" if c["warn"] or c["waived"] else "CLEAN")


def rollup():
    rows = latest_verdicts()
    systems = sorted({r["system"] for r in rows})
    by_system = {s: counter([r for r in rows if r["system"] == s]) for s in systems}
    pnl_of = {s: (REGISTRY.get(s) or {}).get("pnl", "?") for s in systems}
    pnls = sorted(set(pnl_of.values()))
    by_pnl = {p: counter([r for r in rows if pnl_of[r["system"]] == p]) for p in pnls}
    total = counter(rows)
    missing = sorted(s for s in REGISTRY if s not in by_system)
    waivers = sorted(
        [{"system": r["system"], "rule": r["rule"], "expires": r["waiver_expires"]}
         for r in rows if r["verdict"] == "waived" and r["waiver_expires"]],
        key=lambda w: w["expires"])
    return {
        "systems": {s: {**by_system[s], "pnl": pnl_of[s],
                        "domain": (REGISTRY.get(s) or {}).get("domain", "?"),
                        "conformance": round(conformance(by_system[s]), 4),
                        "status": status(by_system[s])} for s in systems},
        "pnls": {p: {**by_pnl[p], "conformance": round(conformance(by_pnl[p]), 4)} for p in pnls},
        "cohort": {**total, "conformance": round(conformance(total), 4),
                   "enforced_violations": total["fail"], "evaluations": sum(total.values())},
        "not_reported": missing,
        "waivers": waivers,
    }


def render_html(r):
    def days(exp):
        return (date.fromisoformat(exp) - date.today()).days

    def badge(st):
        color = {"CLEAN": "#1d9e75", "DEBT": "#ba7517", "FAIL": "#e24b4a"}[st]
        return f'<span style="color:#fff;background:{color};padding:2px 8px;border-radius:6px">{st}</span>'

    cells = lambda c: "".join(f"<td>{c[v]}</td>" for v in VERDICTS)
    vh = "".join(f"<th>{v}</th>" for v in VERDICTS)
    sys_rows = "".join(
        f"<tr><td><code>{s}</code></td><td>{d['pnl']}</td><td>{d['domain']}</td>{cells(d)}"
        f"<td>{round(d['conformance']*100)}%</td><td>{badge(d['status'])}</td></tr>"
        for s, d in sorted(r["systems"].items()))
    pnl_rows = "".join(
        f"<tr><td>{p}</td>{cells(d)}<td>{round(d['conformance']*100)}%</td></tr>"
        for p, d in sorted(r["pnls"].items()))
    miss = "".join(f"<li><code>{s}</code></li>" for s in r["not_reported"])
    waiv = "".join(
        f"<li><code>{w['system']}</code> / {w['rule']} — until {w['expires']} "
        f"({days(w['expires'])}d{' ⚠️' if days(w['expires']) < 30 else ''})</li>"
        for w in r["waivers"])
    c = r["cohort"]
    return f"""<!doctype html><meta charset=utf-8><title>EA conformance — governance plane</title>
<style>
 body{{font:15px/1.6 system-ui,sans-serif;max-width:960px;margin:2rem auto;padding:0 1rem;color:#1a1a1a}}
 h1{{font-size:22px;font-weight:500}} h2{{font-size:16px;font-weight:500;margin-top:1.8rem}}
 table{{border-collapse:collapse;width:100%;font-size:13px;margin-top:.4rem}}
 th,td{{padding:6px 10px;border-bottom:1px solid #eee;text-align:right}}
 th:nth-child(-n+3),td:nth-child(-n+3){{text-align:left}}
 .big{{font-size:30px;font-weight:500}} code{{font-family:ui-monospace,monospace}}
 ul{{font-size:13px}} .muted{{color:#888;font-size:12px}}
</style>
<h1>EA architecture conformance <span class=muted>· governance plane</span></h1>
<p class=big>{round(c['conformance']*100)}% conformant
<span style="font-size:15px;color:#666">· {c['enforced_violations']} enforced violation(s) · {c['evaluations']} evaluations · {len(r['systems'])} system(s)</span></p>
<h2>By system</h2>
<table><tr><th>system</th><th>P&amp;L</th><th>domain</th>{vh}<th>conf</th><th>status</th></tr>{sys_rows}</table>
<h2>By P&amp;L</h2>
<table><tr><th>P&amp;L</th>{vh}<th>conf</th></tr>{pnl_rows}</table>
{f'<h2>Active waivers (aging)</h2><ul>{waiv}</ul>' if waiv else ''}
{f'<h2>Not reported (in registry, no verdicts)</h2><ul>{miss}</ul>' if miss else ''}
<p class=muted>Durable verdict store (SQLite) in namespace <code>governance</code>, separate from prod
observability. Fed by the FitnessResult contract; conf% = pass / applicable (excludes n/a).
JSON for a Backstage Tech Insights fact retriever: <code>/api/conformance</code>.</p>
"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="text/plain; charset=utf-8"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/healthz":
            self._send(200, "ok")
        elif self.path.startswith("/api/conformance"):
            self._send(200, json.dumps(rollup(), indent=2), "application/json")
        elif self.path == "/" or self.path.startswith("/scorecard"):
            self._send(200, render_html(rollup()), "text/html; charset=utf-8")
        else:
            self._send(404, "not found")

    def do_POST(self):
        if not self.path.startswith("/verdicts"):
            self._send(404, "not found")
            return
        length = int(self.headers.get("Content-Length", "0"))
        try:
            n = ingest(self.rfile.read(length).decode())
            self._send(200, json.dumps({"ingested": n}), "application/json")
        except (ValueError, KeyError) as e:
            self._send(400, json.dumps({"error": str(e)}), "application/json")

    def log_message(self, *_):
        pass  # quiet


if __name__ == "__main__":
    init_db()
    print(f"governance-plane on :{PORT} (db={DB_PATH}, registry={len(REGISTRY)} systems)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
