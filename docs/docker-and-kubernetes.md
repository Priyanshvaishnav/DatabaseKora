# Docker & Kubernetes Deployment Guide

> Production-grade containerization, orchestration, and horizontal read scaling for KoraDB.

---

## 1. Overview & Cloud-Native Architecture

KoraDB is built to run as a first-class cloud-native relational database engine. Milestone 14 provides:
1. **Multi-Stage Docker Image**: Hardened Eclipse Temurin JRE 21 runtime running as non-root user `koradb:koradb` (`uid=10001`).
2. **12-Factor Bootstrapper**: `dev.koradb.server.ServerMain` automatically detects container and cluster environments, configuring roles (`PRIMARY`, `REPLICA`, `STANDALONE`), replication streams, and telemetry through environment variables.
3. **Docker Compose Cluster**: Orchestrates Primary (RW), two Read Replicas (RO), Prometheus time-series scraping, and Grafana visualization with pre-provisioned dashboards.
4. **Kubernetes StatefulSets**: Stable network identities (`koradb-primary-0`), dedicated `volumeClaimTemplates` for persistent storage, liveness/readiness probes, pod disruption budgets, and headless routing.
5. **Helm 3 Chart**: Production chart packaging all manifests with configurable resources, persistence, replication topologies, and Prometheus Operator integration.

---

## 2. Cluster Topology & Network Architecture

```
                    +------------------------------------+
                    |        Client Applications         |
                    +-----------------+------------------+
                                      |
                      Write Queries   |   Read Queries (Load-Balanced)
                      (INSERT/UPDATE) |   (SELECT)
                                      v
                 +--------------------+--------------------+
                 |                                         |
                 v                                         v
       +--------------------+                    +--------------------+
       |  koradb-primary    |                    |  koradb-replicas   |
       |  Service (ClusterIP|                    |  Service (ClusterIP|
       +---------+----------+                    +---------+----------+
                 |                                         |
                 v                                         +-------------------+
       +--------------------+                                                  |
       |  koradb-primary-0  |                                                  |
       |  (StatefulSet)     |                                                  v
       |                    |                                        +--------------------+
       |  Port 8765 (SQL)   |                                        |  koradb-replica-0  |
       |  Port 8766 (Master)|===== Physical WAL Replication ======>  |  (StatefulSet)     |
       |  Port 9100 (Health)|  (Async / Sync Streaming)              |                    |
       +---------+----------+                                        |  Port 8765 (RO SQL)|
                 |                                                   |  Port 9100 (Health)|
                 |                                                   +---------+----------+
                 v                                                             |
       +--------------------+                                                  v
       |  PersistentVolume  |                                        +--------------------+
       |  (10Gi PVC)        |                                        |  PersistentVolume  |
       +--------------------+                                        |  (10Gi PVC)        |
                                                                     +--------------------+
```

---

## 3. Container-Aware 12-Factor Bootstrapper

`ServerMain` reads configuration with precedence: **CLI flags > Environment Variables > Defaults**.

| Environment Variable | CLI Flag | Default | Description |
|---|---|---|---|
| `KORA_HOST` | `-h, --host` | `0.0.0.0` (container) / `127.0.0.1` | Network interface to bind |
| `KORA_PORT` | `-p, --port` | `8765` | TCP SQL query port |
| `KORA_DATA_DIR` | `-d, --data-dir` | `/var/lib/koradb/data` | Persistent disk database directory |
| `KORA_ROLE` | `--role` | `PRIMARY` | Cluster role: `PRIMARY`, `REPLICA`, or `STANDALONE` |
| `KORA_REPLICATION_PORT`| `--replication-port` | `8766` | WAL replication master server port |
| `KORA_REPLICATION_MODE`| `--replication-mode` | `ASYNC` | Streaming replication mode (`ASYNC` or `SYNC`) |
| `KORA_PRIMARY_HOST` | `--primary-host` | `localhost` | Primary host for replica to track |
| `KORA_PRIMARY_PORT` | `--primary-port` | `8766` | Primary replication port for replica |
| `KORA_REPLICA_ID` | `--replica-id` | Pod hostname / UUID | Unique replica identifier |
| `KORA_METRICS_ENABLED` | `--no-metrics` | `true` | Enable/disable Prometheus HTTP exporter |
| `KORA_METRICS_PORT` | `--metrics-port` | `9100` | HTTP metrics and `/health` probe port |
| `JAVA_OPTS` | N/A | `-XX:MaxRAMPercentage=75.0 ...` | JVM memory and GC ergonomics |

---

## 4. Docker Architecture & Multi-Stage Build

### 4.1 Multi-Stage Dockerfile Highlights
- **Build Stage**: Uses `maven:3.9-eclipse-temurin-21` to compile and build self-contained fat jars (`koradb-server.jar` and `koradb-cli.jar`).
- **Runtime Stage**: Uses minimal, secure `eclipse-temurin:21-jre-jammy`.
- **Security Posture**: Runs under dedicated non-root user `koradb` (`uid=10001`, `gid=10001`).
- **Built-in Healthcheck**: Probes `http://127.0.0.1:9100/health` every 10 seconds.
- **Entrypoint**: `docker-entrypoint.sh` handles server flags, CLI execution, or interactive bash debugging.

### 4.2 Building the Docker Image
```bash
docker build -t koradb:latest .
```

### 4.3 Running Single Container
```bash
# Run standalone server
docker run -d --name koradb -p 8765:8765 -p 9100:9100 koradb:latest

# Run CLI REPL connecting to container
docker run -it --rm --network host koradb:latest cli -h 127.0.0.1 -p 8765
```

---

## 5. Docker Compose Multi-Node Cluster

The included `docker-compose.yml` spins up a complete 5-container cluster:
1. `koradb-primary`: Accepts read-write transactions, serves port `8765`, replicates on port `8766`.
2. `koradb-replica-1`: Receives WAL stream, serves read-only queries on host port `8767`.
3. `koradb-replica-2`: Receives WAL stream, serves read-only queries on host port `8768`.
4. `prometheus`: Scrapes metrics from all three nodes every 5s on port `9090`.
5. `grafana`: Pre-loaded with Prometheus data source and KoraDB Overview dashboard on port `3000`.

### 5.1 Starting the Cluster
```bash
docker compose up -d
```

Verify services:
```bash
docker compose ps
```

### 5.2 Connecting via CLI
```bash
# Connect to Primary (Read-Write)
docker run -it --rm --network host koradb:latest cli -h 127.0.0.1 -p 8765

# Create tables and insert data on Primary
koradb> CREATE DATABASE finance;
koradb> USE finance;
koradb> CREATE TABLE accounts (id INT, name VARCHAR, balance DOUBLE);
koradb> INSERT INTO accounts VALUES (1, 'Alice', 10000.0);

# Connect to Replica 1 (Read-Only)
docker run -it --rm --network host koradb:latest cli -h 127.0.0.1 -p 8767

# Query data instantly replicated via WAL stream
koradb> USE finance;
koradb> SELECT * FROM accounts;
# Alice | 10000.0

# Verify mutating statements are cleanly rejected on replica
koradb> INSERT INTO accounts VALUES (2, 'Bob', 5000.0);
# Error: Cannot execute mutating statement on read-only replica: INSERT INTO accounts VALUES (2, 'Bob', 5000.0)
```

### 5.3 Viewing Observability & Dashboards
- **Prometheus UI**: Open `http://localhost:9090` to inspect `koradb_queries_total`, `koradb_buffer_pool_hit_ratio`, etc.
- **Grafana UI**: Open `http://localhost:3000` (User: `admin`, Password: `koradb`) and navigate to Dashboards -> **KoraDB Overview**.

---

## 6. Kubernetes Production Manifests (`k8s/`)

### 6.1 Manifest Architecture
- `k8s/namespace.yaml`: Dedicated `koradb` namespace.
- `k8s/configmap.yaml`: Cluster-wide configuration (ports, data directory, JVM flags).
- `k8s/primary-statefulset.yaml`:
  - 1 Primary replica (`koradb-primary-0`).
  - Headless service binding (`koradb-primary-headless`).
  - `volumeClaimTemplates` mounting 10Gi durable volume to `/var/lib/koradb/data`.
  - Non-root security context (`uid=10001`).
  - Liveness & Readiness HTTP probes on port 9100 (`/health`).
- `k8s/replica-statefulset.yaml`:
  - 2 scalable read replicas.
  - Automatically targets `koradb-primary-0.koradb-primary-headless.koradb.svc.cluster.local:8766`.
  - Injects Pod name (`metadata.name`) as `KORA_REPLICA_ID`.
- `k8s/services.yaml`:
  - `koradb-primary`: ClusterIP targeting `role: primary` on port 8765 (writes).
  - `koradb-replicas`: ClusterIP load-balancing reads across all replicas on port 8765.
  - `koradb-primary-headless`: Headless service for pod DNS resolution.
  - `koradb-metrics`: Exposes port 9100 for Prometheus scraping.
- `k8s/pdb.yaml`: Guarantees at least 1 replica is always available during disruptions.
- `k8s/servicemonitor.yaml`: Native Prometheus Operator CRD for automatic scraping.

### 6.2 Deploying to Kubernetes
```bash
# 1. Apply namespace and config
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml

# 2. Deploy services and storage
kubectl apply -f k8s/services.yaml
kubectl apply -f k8s/primary-statefulset.yaml

# 3. Deploy read replicas and high availability policies
kubectl apply -f k8s/replica-statefulset.yaml
kubectl apply -f k8s/pdb.yaml
kubectl apply -f k8s/servicemonitor.yaml
```

### 6.3 Verifying Deployment
```bash
kubectl get pods -n koradb -o wide
kubectl get svc -n koradb
kubectl get pvc -n koradb
```

---

## 7. Helm 3 Deployment Guide

### 7.1 Installing the Chart
```bash
helm install koradb ./helm/koradb -n koradb --create-namespace
```

### 7.2 Customizing Parameters
```bash
# Scale read replicas to 4 and use fast NVMe storage class
helm upgrade --install koradb ./helm/koradb \
  -n koradb \
  --set replica.replicaCount=4 \
  --set primary.persistence.storageClass=fast-nvme \
  --set replica.persistence.storageClass=fast-nvme \
  --set cluster.replicationMode=SYNC
```

### 7.3 Interacting with the Cluster
```bash
# Port forward primary SQL port
kubectl port-forward svc/koradb-primary 8765:8765 -n koradb

# Run one-shot query using ephemeral client pod
kubectl run koradb-client --rm -it --image=koradb:latest -n koradb -- \
  cli -h koradb-primary -p 8765 -e "SHOW DATABASES;"
```

---

## 8. Failover and Promotion Runbook

If the primary node experiences unrecoverable hardware failure:
1. Promote `koradb-replica-0` to primary role:
   ```bash
   # Connect to replica container
   kubectl exec -it koradb-replica-0 -n koradb -- bash
   ```
2. Trigger promotion via management API or restart with `KORA_ROLE=PRIMARY`.
3. Update service selectors or DNS mappings to point writes to the promoted node.
