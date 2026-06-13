#!/usr/bin/env bash
# Deploy the governance-plane prototype into the `governance` namespace.
# Creates the gov-server (server.py) and gov-registry (registry/ -> JSON) ConfigMaps, then applies
# the workload. Re-run after editing server.py or the registry to roll the change out.
#
#   ./deploy.sh            # build configmaps + apply + restart
#   ./deploy.sh push FILE  # POST a FitnessResult NDJSON file to the running service (via port-forward)
set -euo pipefail
cd "$(dirname "$0")"
NS=governance

if [ "${1:-}" = "push" ]; then
  file="${2:?usage: ./deploy.sh push <ndjson-file>}"
  echo "port-forwarding governance-plane :8080 ..."
  kubectl -n "$NS" port-forward svc/governance-plane 8080:8080 >/tmp/gov-pf.log 2>&1 &
  pf=$!; trap 'kill $pf 2>/dev/null' EXIT; sleep 4
  curl -s -X POST --data-binary @"$file" http://localhost:8080/verdicts; echo
  exit 0
fi

if [ "${1:-}" = "slo-now" ]; then
  job="slo-now-$(date +%s)"
  kubectl -n "$NS" create job "$job" --from=cronjob/slo-evaluator
  kubectl -n "$NS" wait --for=condition=complete --timeout=120s "job/$job" || true
  kubectl -n "$NS" logs "job/$job"
  exit 0
fi

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

# registry/*.yaml -> one registry.json (system id -> owner/domain/pnl/stack)
python3 - > /tmp/gov-registry.json <<'PY'
import glob, json, yaml
reg = {}
for f in glob.glob("../registry/*.yaml"):
    d = yaml.safe_load(open(f)) or {}
    sid = (d.get("metadata") or {}).get("id")
    spec = d.get("spec") or {}
    if sid:
        entry = {k: spec.get(k) for k in ("owner", "domain", "pnl", "stack")}
        if spec.get("runtimeObjectives"):
            entry["runtimeObjectives"] = spec["runtimeObjectives"]
        reg[sid] = entry
print(json.dumps(reg))
PY

kubectl -n "$NS" create configmap gov-registry \
  --from-file=registry.json=/tmp/gov-registry.json \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create configmap gov-server \
  --from-file=server.py=server.py \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$NS" create configmap gov-slo \
  --from-file=slo-evaluator.py=slo/slo-evaluator.py \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f deploy/governance-plane.yaml
kubectl apply -f slo/slo-cron.yaml
kubectl -n "$NS" rollout restart deploy/governance-plane
kubectl -n "$NS" rollout status deploy/governance-plane --timeout=120s
echo "Done. View:  kubectl -n $NS port-forward svc/governance-plane 8080:8080  then open http://localhost:8080"
echo "Runtime SLO: ./deploy.sh slo-now  (or wait for the 5-min CronJob)"
