# Estate Landscape

Every component currently running across the estate — business runtime, platform, observability,
tracing, governance, and CI — in one picture. For the deeper per-area views see
[SYSTEM_VIEW.md](SYSTEM_VIEW.md) (EA program), the msfw `docs/MODULE_VIEW.md`, and the marketplace
`docs/CONTEXT_MAP.md`.

```mermaid
flowchart TB
  classDef biz fill:#1d9e75,stroke:#0f6e56,color:#fff;
  classDef orch fill:#ba7517,stroke:#854f0b,color:#fff;
  classDef data fill:#888780,stroke:#5f5e5a,color:#fff;
  classDef obs fill:#378add,stroke:#185fa5,color:#fff;
  classDef trace fill:#d4537e,stroke:#993556,color:#fff;
  classDef gov fill:#534ab7,stroke:#3c3489,color:#fff;
  classDef build fill:#d85a30,stroke:#993c1d,color:#fff;
  classDef plat fill:#444441,stroke:#2c2c2a,color:#fff;

  FW["msfw framework<br/>(library in every service)"]:::plat

  subgraph BUILD["Build & CI · per repo (GitHub Actions)"]
    HARNESS["ea-archrules harness<br/>ArchUnit · -Pfitness"]:::build
    OPA["OPA / conftest<br/>k8s manifest policy"]:::build
  end

  subgraph RUNTIME["Business runtime · ns marketplace"]
    CK["checkout<br/>saga orchestrator (CompensatingWorkflow)"]:::orch
    CAT["catalog · Product"]:::biz
    ORD["order · Order"]:::biz
    PAY["payment · Payment/Settlement"]:::biz
    INV["inventory · Stock/Reservation"]:::biz
    NOT["notification · sink"]:::biz
    KAFKA["Kafka (Strimzi)<br/>event bus"]:::data
    PG[("PostgreSQL<br/>per-service DBs")]:::data
  end

  subgraph PLATFORM["Platform"]
    ISTIO["Istio mesh<br/>mTLS · routing"]:::plat
    ARGO["ArgoCD · GitOps"]:::plat
  end

  subgraph OBS["Observability · ns infra"]
    PROM["Prometheus<br/>scrape + msfw alert rules"]:::obs
    GRAF["Grafana<br/>MSFW Overview · EA Conformance · SLO/runtime"]:::obs
    TEMPO["Tempo<br/>trace store"]:::trace
  end

  subgraph GOV["Governance plane · ns governance"]
    REG[("registry · SSOT<br/>modes · SLOs · waivers")]:::gov
    GP["governance-plane<br/>verdict store + scorecard + /api/conformance"]:::gov
    SLO["slo-evaluator (CronJob)<br/>SLO + drift"]:::gov
    BACK["Backstage Tech Insights<br/>(target)"]:::gov
  end

  FW --> CK & CAT & ORD & PAY & INV & NOT

  CK -->|"validate"| CAT
  CK -->|"reserve"| INV
  CK -->|"place order"| ORD
  CK -->|"escrow"| PAY
  CAT & ORD & PAY & INV -.->|"publish events"| KAFKA
  KAFKA -.->|"consume"| ORD & PAY & INV & NOT
  CAT & ORD & PAY & INV --> PG

  ARGO -.->|"deploy"| RUNTIME
  ISTIO -.-> RUNTIME

  PROM -->|"scrape metrics"| RUNTIME
  RUNTIME -->|"OTLP traces · OTel agent"| TEMPO
  GRAF -->|"datasource"| PROM
  GRAF -->|"datasource"| TEMPO

  PROM -->|"read SLI"| SLO
  SLO -->|"runtime verdict"| GP
  HARNESS -->|"FitnessResult (code)"| GP
  OPA -->|"FitnessResult"| GP
  REG -.->|"config"| HARNESS
  REG -.->|"thresholds"| SLO
  REG -.->|"join on system"| GP
  GP -.->|"/api/conformance"| BACK
```

## Planes (by colour)

- 🟧 **Orchestrator** — `checkout` (synchronous `CompensatingWorkflow` over four contexts).
- 🟩 **Business services** — catalog, order, payment, inventory, notification (each its own aggregate + DB).
- ⬜ **Data / messaging** — Kafka (Strimzi) event bus, PostgreSQL per-service DBs.
- ⬛ **Platform** — the msfw framework (library in every service), Istio mesh, ArgoCD (GitOps).
- 🔵 **Observability** — Prometheus (scrape + alert rules), Grafana (MSFW Overview · EA Conformance · SLO/runtime dashboards).
- 🩷 **Tracing** — OpenTelemetry Java agent in each pod → Tempo → Grafana.
- 🟪 **Governance plane** — registry (SSOT), governance-plane (durable verdict store + scorecard + `/api/conformance`), slo-evaluator CronJob, Backstage Tech Insights (target).
- 🟥 **Build / CI** — ea-archrules harness (ArchUnit, `-Pfitness`), OPA/conftest.

## Main flows

- **Business** — checkout orchestrates four services synchronously (solid); services publish/consume
  domain events over Kafka (dashed); state in PostgreSQL.
- **Observability** — Prometheus scrapes metrics; the OTel agent exports OTLP traces to Tempo;
  Grafana reads both as datasources.
- **Governance** — `FitnessResult` verdicts from the **static** tier (CI: harness + OPA) **and** the
  **runtime** tier (slo-evaluator reading Prometheus) land in the **governance-plane** store/scorecard,
  keyed on the **registry** `system` id, heading toward Backstage. Per
  [ADR 0001](adr/0001-governance-plane.md): CI never writes into the prod cluster, and the governance
  plane is separate from prod observability.

> The early Pushgateway was scaffolding, replaced by the governance-plane per ADR 0001 — it is not
> shown here; this is the running target architecture.
