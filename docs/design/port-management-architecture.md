# Port Management Architecture

**Status:** Design Phase
**Phase:** 13 (AI Orchestration)
**Date:** 2025-11-24

---

## Problem Statement

With multiple AI instances and dedicated MCP servers running simultaneously, we need:

1. **Port allocation** - Assign unique ports without conflicts
2. **Port discovery** - Find running services by domain/purpose
3. **Port tracking** - Know what's running where
4. **Port cleanup** - Reclaim ports when services stop
5. **Port persistence** - Survive bb-mcp-server restarts

**Scale:** Could have 10+ concurrent experts, each with dedicated MCP server = 20+ ports active simultaneously.

---

## Current State

### What We Have
- `pid_util.clj` - PID files per port (`.pid/<port>.pid`)
- Manual port assignment in expert configs
- Domain port ranges (19880-19889, 19890-19899, etc.)

### Problems
1. **No central registry** - Can't query "what's on port 19880?"
2. **No discovery** - Can't ask "where is clojure-tools server?"
3. **Race conditions** - Two processes could try same port
4. **No persistence** - Port state lost on main server restart
5. **Manual ranges** - Have to pre-allocate ranges per domain

---

## Port Management System Design

### Architecture

```
┌─────────────────────────────────────────────────┐
│          Port Registry (Central State)          │
│  - Active allocations (port → service)          │
│  - Domain mappings (domain → port)              │
│  - PID tracking (port → PID)                    │
│  - Timestamps (port → created-at)               │
└─────────────────────────────────────────────────┘
              ▲            │
              │            ▼
    ┌─────────────────────────────┐
    │    Port Allocator           │
    │  - find-available-port      │
    │  - allocate-port!           │
    │  - release-port!            │
    │  - discover-by-domain       │
    └─────────────────────────────┘
              ▲            │
              │            ▼
    ┌─────────────────────────────┐
    │   Port Health Monitor       │
    │  - Periodic health checks   │
    │  - Auto-cleanup dead ports  │
    │  - Stale allocation removal │
    └─────────────────────────────┘
```

---

## Option 1: File-Based Registry (Simple, No Dependencies)

### Storage Format: EDN

**File:** `.ports/registry.edn`

```clojure
{:allocations
 {19880 {:service-type :mcp-server
         :domain :clojure-tools
         :expert-id :clojure-coder-1
         :pid 12345
         :url "http://127.0.0.1:19880/mcp"
         :allocated-at #inst "2025-11-24T10:30:00.000-00:00"
         :last-health-check #inst "2025-11-24T10:35:00.000-00:00"
         :status :healthy}

  19881 {:service-type :mcp-server
         :domain :aws-tools
         :expert-id :aws-deployer-1
         :pid 12346
         :url "http://127.0.0.1:19881/mcp"
         :allocated-at #inst "2025-11-24T10:31:00.000-00:00"
         :last-health-check #inst "2025-11-24T10:36:00.000-00:00"
         :status :healthy}

  3000 {:service-type :main-server
        :domain :main
        :pid 12340
        :url "http://127.0.0.1:3000/mcp"
        :allocated-at #inst "2025-11-24T10:00:00.000-00:00"
        :status :healthy}}

 :domain-index
 {:clojure-tools 19880
  :aws-tools 19881
  :main 3000}

 :port-ranges
 {:clojure-tools [19880 19889]
  :aws-tools [19890 19899]
  :security-tools [19900 19909]
  :db-tools [19910 19919]
  :ephemeral [20000 29999]}}
```

### Implementation

```clojure
(ns bb-mcp-server.ports.registry
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.trove :as log]))

(def registry-file ".ports/registry.edn")

(defonce registry-atom (atom nil))

;; =============================================================================
;; Persistence
;; =============================================================================

(defn load-registry! []
  (if (.exists (io/file registry-file))
    (let [data (edn/read-string (slurp registry-file))]
      (reset! registry-atom data)
      (log/log! {:level :info
                 :msg "Port registry loaded"
                 :data {:ports (count (:allocations data))}})
      data)
    (do
      (reset! registry-atom {:allocations {}
                             :domain-index {}
                             :port-ranges {:clojure-tools [19880 19889]
                                           :aws-tools [19890 19899]
                                           :security-tools [19900 19909]
                                           :db-tools [19910 19919]
                                           :ephemeral [20000 29999]}})
      @registry-atom)))

(defn save-registry! []
  (io/make-parents registry-file)
  (spit registry-file (pr-str @registry-atom))
  (log/log! {:level :debug
             :msg "Port registry saved"}))

;; =============================================================================
;; Port Allocation
;; =============================================================================

(defn port-available? [port]
  (try
    (with-open [socket (java.net.ServerSocket. port)]
      true)
    (catch java.net.BindException e
      false)))

(defn find-available-port-in-range [[start end]]
  (loop [port start]
    (cond
      (> port end) nil
      (and (port-available? port)
           (not (get-in @registry-atom [:allocations port])))
      port
      :else (recur (inc port)))))

(defn allocate-port!
  "Allocate a port for a service.

   Options:
     :domain - Service domain (:clojure-tools, :aws-tools, etc.)
     :service-type - Type (:mcp-server, :main-server, :claude-subprocess)
     :expert-id - Expert instance ID (optional)
     :pid - Process ID
     :url - Service URL

   Returns: Port number or nil if no ports available."
  [{:keys [domain service-type expert-id pid url]}]
  (let [range (get-in @registry-atom [:port-ranges domain]
                      [20000 29999])  ; Default to ephemeral
        port (find-available-port-in-range range)]
    (when port
      (swap! registry-atom
             (fn [reg]
               (-> reg
                   (assoc-in [:allocations port]
                             {:service-type service-type
                              :domain domain
                              :expert-id expert-id
                              :pid pid
                              :url url
                              :allocated-at (java.time.Instant/now)
                              :status :starting})
                   (assoc-in [:domain-index domain] port))))
      (save-registry!)
      (log/log! {:level :info
                 :msg "Port allocated"
                 :data {:port port :domain domain :service-type service-type}})
      port)))

(defn release-port!
  "Release a port allocation."
  [port]
  (when (get-in @registry-atom [:allocations port])
    (let [domain (get-in @registry-atom [:allocations port :domain])]
      (swap! registry-atom
             (fn [reg]
               (-> reg
                   (update :allocations dissoc port)
                   (update :domain-index dissoc domain))))
      (save-registry!)
      (log/log! {:level :info
                 :msg "Port released"
                 :data {:port port}}))))

;; =============================================================================
;; Discovery
;; =============================================================================

(defn discover-by-domain
  "Find port for a domain."
  [domain]
  (get-in @registry-atom [:domain-index domain]))

(defn discover-by-expert
  "Find port for an expert instance."
  [expert-id]
  (first
   (keep (fn [[port info]]
           (when (= expert-id (:expert-id info))
             port))
         (:allocations @registry-atom))))

(defn get-port-info
  "Get full info for a port."
  [port]
  (get-in @registry-atom [:allocations port]))

(defn list-active-ports
  "List all active port allocations."
  []
  (:allocations @registry-atom))

;; =============================================================================
;; Health Monitoring
;; =============================================================================

(defn check-port-health!
  "Check if service on port is responding."
  [port]
  (let [info (get-port-info port)
        health-url (str/replace (:url info) #"/mcp$" "/health")]
    (try
      (let [resp (http/get health-url {:socket-timeout 2000})]
        (if (= 200 (:status resp))
          (do
            (swap! registry-atom
                   assoc-in [:allocations port :last-health-check]
                   (java.time.Instant/now))
            (swap! registry-atom
                   assoc-in [:allocations port :status]
                   :healthy)
            (save-registry!)
            :healthy)
          :unhealthy))
      (catch Exception e
        (swap! registry-atom
               assoc-in [:allocations port :status]
               :unhealthy)
        (save-registry!)
        :unhealthy))))

(defn cleanup-stale-allocations!
  "Remove allocations for dead processes."
  []
  (doseq [[port info] (:allocations @registry-atom)]
    (let [pid (:pid info)]
      ;; Check if process is alive
      (when-not (pid-util/process-alive? pid)
        (log/log! {:level :warn
                   :msg "Removing stale port allocation"
                   :data {:port port :pid pid}})
        (release-port! port)))))

(defn start-health-monitor!
  "Start background health monitoring."
  []
  (future
    (loop []
      (Thread/sleep 30000)  ; Check every 30s
      (try
        (cleanup-stale-allocations!)
        (doseq [[port _info] (:allocations @registry-atom)]
          (check-port-health! port))
        (catch Exception e
          (log/log! {:level :error
                     :msg "Health monitor error"
                     :data {:error (str e)}})))
      (recur))))
```

---

## Option 2: Datalevin Registry (Queryable, Persistent)

### Schema

```clojure
(def schema
  {:port/number {:db/unique :db.unique/identity}
   :port/service-type {}
   :port/domain {}
   :port/expert-id {}
   :port/pid {}
   :port/url {}
   :port/allocated-at {}
   :port/last-health-check {}
   :port/status {}})

;; Datalog queries
(defn find-port-by-domain [db domain]
  (d/q '[:find ?port .
         :in $ ?domain
         :where
         [?e :port/domain ?domain]
         [?e :port/number ?port]]
       db domain))

(defn find-all-healthy-servers [db]
  (d/q '[:find ?port ?domain ?url
         :where
         [?e :port/status :healthy]
         [?e :port/number ?port]
         [?e :port/domain ?domain]
         [?e :port/url ?url]]
       db))

(defn find-ports-needing-health-check [db max-age-ms]
  (d/q '[:find ?port
         :in $ ?cutoff
         :where
         [?e :port/number ?port]
         [?e :port/last-health-check ?t]
         [(< ?t ?cutoff)]]
       db
       (- (System/currentTimeMillis) max-age-ms)))
```

---

## Recommended Approach: Hybrid

### Phase 13E-13F: File-Based (MVP)
- Simple EDN registry
- Sufficient for 10-20 concurrent services
- Easy to debug (just `cat .ports/registry.edn`)

### Phase 14+: Migrate to Datalevin
- When we add Datalevin for expert curriculum anyway
- Enables complex queries (e.g., "find all unhealthy MCP servers")
- Better concurrency control
- History/audit trail

---

## Integration with Expert Startup

### Updated Sequence

```clojure
(defn start-expert! [expert-id]
  (let [expert-def (get-expert-definition expert-id)
        domain (get-in expert-def [:mcp-server :domain])]

    ;; 1. Allocate port FIRST
    (let [port (ports/allocate-port!
                 {:domain domain
                  :service-type :mcp-server
                  :expert-id (generate-instance-id expert-id)})]

      (if-not port
        {:error true :message "No ports available"}

        (try
          ;; 2. Start MCP server on allocated port
          (let [mcp-server (start-dedicated-mcp-server! domain port)

                ;; 3. Update port info with PID and URL
                _ (ports/update-port-info! port
                    {:pid (:pid mcp-server)
                     :url (:url mcp-server)})

                ;; 4. Health check
                _ (wait-for-mcp-health! (:url mcp-server) 5000)

                ;; 5. Mark port as healthy
                _ (ports/update-port-status! port :healthy)

                ;; 6. Now spawn Claude with strict config
                claude-proc (spawn-claude-with-mcp!
                              {:model (:model expert-def)
                               :system-prompt (load-essential-curriculum expert-def)
                               :mcp-config (generate-mcp-config (:url mcp-server))
                               :strict true})]

            ;; 7. Register expert
            {:id (generate-instance-id expert-id)
             :expert-type expert-id
             :mcp-port port
             :claude-process claude-proc
             :status :ready})

          (catch Exception e
            ;; Cleanup on failure
            (ports/release-port! port)
            {:error true :message (str e)}))))))
```

### Discovery Example

```clojure
;; Find existing clojure-tools server
(if-let [port (ports/discover-by-domain :clojure-tools)]
  ;; Reuse existing server
  (let [info (ports/get-port-info port)]
    (if (= :healthy (:status info))
      (use-existing-mcp-server (:url info))
      (restart-unhealthy-server port)))
  ;; Start new server
  (start-new-mcp-server :clojure-tools))
```

---

## Port Ranges Strategy

### Domain-Specific Ranges (Predictable)

```clojure
{:port-ranges
 {:main-server [3000 3000]       ; Fixed port for main bb-mcp-server
  :clojure-tools [19880 19889]   ; 10 concurrent Clojure experts
  :aws-tools [19890 19899]       ; 10 concurrent AWS experts
  :security-tools [19900 19909]
  :db-tools [19910 19919]
  :ml-tools [19920 19929]
  :documentation [19930 19939]
  :testing [19940 19949]
  :devops [19950 19959]
  :ephemeral [20000 29999]}}     ; Dynamic/unknown domains
```

**Benefits:**
- Predictable port assignments
- Easy to configure firewalls
- Simple to document
- Fast discovery (check range first)

---

## CLI Commands

```bash
# List all active ports
bb ports:list

# Find port for domain
bb ports:find :clojure-tools

# Release port
bb ports:release 19880

# Health check
bb ports:health

# Cleanup stale allocations
bb ports:cleanup
```

---

---

## Option 3: Directory Service (Production Scale)

### When File/Datalevin Isn't Enough

**Threshold:** ~10+ concurrent services per machine, or multi-machine deployment

### Problems at Scale

1. **Multi-machine coordination** - File/Datalevin are single-machine
2. **Service discovery** - "Find any healthy clojure-tools server across cluster"
3. **Load balancing** - "Route to least-loaded server"
4. **Failure detection** - "Which servers are down?"
5. **Dynamic reconfiguration** - "Add/remove servers without restart"

### Directory Service Options

#### Option 3A: Consul (HashiCorp)

**Use case:** Multi-machine clusters, production deployments

```clojure
;; Register MCP server with Consul
(defn register-with-consul! [port domain]
  (consul/register-service
    {:name (str "mcp-" (name domain))
     :id (str "mcp-" (name domain) "-" port)
     :port port
     :address "127.0.0.1"
     :tags [(name domain) "mcp-server"]
     :check {:http (str "http://127.0.0.1:" port "/health")
             :interval "10s"
             :timeout "2s"}}))

;; Discover services
(defn discover-mcp-servers [domain]
  (consul/list-services
    {:service (str "mcp-" (name domain))
     :passing true}))  ; Only healthy

;; Find least-loaded server
(defn find-best-server [domain]
  (let [servers (discover-mcp-servers domain)]
    (first (sort-by :load servers))))
```

**Benefits:**
- ✅ Multi-machine service discovery
- ✅ Built-in health checks
- ✅ DNS integration (mcp-clojure-tools.service.consul)
- ✅ Key-value store for config
- ✅ Leader election

**Cons:**
- ❌ External dependency (Consul agent)
- ❌ More complex setup
- ❌ Overkill for single machine

#### Option 3B: etcd (CoreOS)

**Use case:** Kubernetes deployments, distributed systems

```clojure
(defn register-with-etcd! [port domain]
  (etcd/put
    (str "/mcp-servers/" (name domain) "/" port)
    {:port port
     :domain domain
     :status :healthy
     :url (str "http://127.0.0.1:" port "/mcp")}
    {:ttl 30}))  ; Auto-expire if not renewed

;; Watch for changes
(etcd/watch
  "/mcp-servers/"
  (fn [event]
    (case (:type event)
      :put (log/info "Server added" (:value event))
      :delete (log/info "Server removed" (:key event)))))
```

**Benefits:**
- ✅ Strong consistency
- ✅ Watch mechanism (real-time updates)
- ✅ TTL-based expiration
- ✅ Kubernetes-native

#### Option 3C: ZooKeeper (Apache)

**Use case:** Java ecosystem, mature production systems

**Benefits:**
- ✅ Battle-tested (Kafka, Hadoop use it)
- ✅ Strong consistency guarantees
- ✅ Leader election primitives

**Cons:**
- ❌ Heavy (JVM required)
- ❌ Complex to operate
- ❌ Arguably legacy (etcd/Consul preferred)

### Recommendation: Progressive Adoption

```
Phase 13E-13F: File-based (.ports/registry.edn)
    ↓
    Good for: 2-10 concurrent services, single machine
    ↓
Phase 14: Datalevin (embedded DB)
    ↓
    Good for: 10-50 concurrent services, single machine
    Complex queries, history tracking
    ↓
Phase 15+: Consul (distributed)
    ↓
    Good for: Multi-machine, production clusters
    Service mesh, dynamic scaling
```

### Hybrid Approach (Best of Both Worlds)

**Strategy:** Use local registry as cache, sync with Consul

```clojure
(defn allocate-port-with-consul! [opts]
  ;; 1. Allocate locally first (fast)
  (let [port (local-registry/allocate-port! opts)]

    ;; 2. Register with Consul (if available)
    (when (consul/available?)
      (try
        (consul/register-service port (:domain opts))
        (catch Exception e
          (log/warn "Consul registration failed, continuing locally" e))))

    port))

(defn discover-by-domain [domain]
  ;; Try Consul first (authoritative)
  (if (consul/available?)
    (consul/find-service (str "mcp-" (name domain)))
    ;; Fallback to local registry
    (local-registry/discover-by-domain domain)))
```

**Benefits:**
- ✅ Works without Consul (development, single-machine)
- ✅ Upgrades to distributed when Consul available
- ✅ Graceful degradation if Consul fails

### Multi-Machine Discovery Example

```clojure
;; Machine A: Start clojure-tools server
(start-mcp-server! :clojure-tools 19880)
;; → Registers in Consul as "mcp-clojure-tools"

;; Machine B: Expert needs clojure-tools
(let [servers (consul/list-services {:service "mcp-clojure-tools"
                                      :passing true})]
  ;; servers = [{:address "192.168.1.10" :port 19880}
  ;;            {:address "192.168.1.11" :port 19880}]

  ;; Connect to any healthy instance
  (connect-to-mcp (first servers)))
```

### Service Mesh Integration

With Consul, we can use **Consul Connect** for encrypted service-to-service:

```clojure
;; MCP server with mTLS via Consul Connect
(start-mcp-server!
  :clojure-tools
  19880
  {:consul-connect true})  ; Automatic mTLS, no code changes

;; Claude expert connects via sidecar proxy
(spawn-claude-with-mcp!
  {:mcp-config {:url "http://localhost:20000"}})  ; Local proxy
;; → Proxy handles mTLS to actual MCP server
```

---

### Option 3D: bb-mcp-server REST API (Self-Service Discovery)

**Key Insight:** We already have a REST API! Use it for port discovery.

**Use case:** Lightweight directory service without external dependencies

#### Expose Port Registry via REST API

```clojure
;; Add to modules/rest-api/src/rest_api/handlers.clj

(defn handle-list-ports [req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str
           {:ports (ports/list-active-ports)
            :domains (ports/list-domains)})})

(defn handle-discover-port [req]
  (let [domain (keyword (get-in req [:params :domain]))]
    (if-let [port (ports/discover-by-domain domain)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str (ports/get-port-info port))}
      {:status 404
       :body "Domain not found"})))

;; Router additions
(defroutes port-registry-routes
  (GET "/api/ports" [] handle-list-ports)
  (GET "/api/ports/:domain" [domain] handle-discover-port)
  (POST "/api/ports/:domain" [domain] handle-allocate-port)
  (DELETE "/api/ports/:port" [port] handle-release-port))
```

#### Discovery from Claude Experts

```bash
# Expert startup script queries main server
PORT=$(curl -s http://localhost:3000/api/ports/clojure-tools | jq -r '.port')

if [ -z "$PORT" ]; then
  # Not found, allocate new port
  PORT=$(curl -s -X POST http://localhost:3000/api/ports/clojure-tools | jq -r '.port')

  # Start MCP server on allocated port
  bb server --http $PORT --config config/clojure-tools.edn &
fi

# Use discovered/allocated port
claude --mcp-config "{\"mcpServers\":{\"tools\":{\"url\":\"http://127.0.0.1:$PORT/mcp\"}}}"
```

#### Multi-Machine: API Gateway Pattern

```
┌─────────────────────────────────────────────┐
│  Machine A: Main bb-mcp-server (port 3000)  │
│  - REST API: /api/ports                     │
│  - Port registry: .ports/registry.edn       │
│  - Responds to discovery requests           │
└─────────────────────────────────────────────┘
              ▲
              │ HTTP GET /api/ports/:domain
              │
    ┌─────────────────┐
    │  Machine B      │
    │  Expert startup │
    │  curl localhost │  → Actually proxied to Machine A
    └─────────────────┘
```

**Setup:** Use nginx/HAProxy to proxy `/api/ports` to main server

#### Benefits

- ✅ No external dependencies (Consul, etcd, ZooKeeper)
- ✅ Works with existing REST API
- ✅ Simple HTTP queries (curl, any language)
- ✅ Supports multi-machine via reverse proxy
- ✅ Already authenticated/authorized (if REST API has auth)

#### Limitations

- ❌ No automatic health checks (client must poll)
- ❌ No watches/notifications (must poll for changes)
- ❌ Manual proxy setup for multi-machine
- ❌ No service mesh features

#### When to Use

**Perfect for:**
- Small deployments (2-5 machines)
- Internal tooling (not public-facing)
- Teams that don't want Consul complexity

**Don't use for:**
- Large clusters (50+ machines)
- Need for real-time updates (watches)
- Production service mesh requirements

---

## Decision Matrix: Which Approach?

| Scale | Servers | Machines | Recommendation | Notes |
|-------|---------|----------|----------------|-------|
| Development | 1-5 | 1 | **File-based** (.ports/registry.edn) | Simplest, debug-friendly |
| Single Machine | 5-20 | 1 | **Datalevin** (embedded DB) | Complex queries, history |
| Small Cluster | 10-50 | 2-5 | **REST API + nginx** | No external deps, simple HTTP |
| Medium Cluster | 20-100 | 5-20 | **Consul** (distributed) | Service discovery, health checks |
| Production | 100+ | 20+ | **Consul + Service Mesh** | Full observability, mTLS |

---

## Related Documents

- [AI Experts Framework](ai-experts-framework.md)
- [AI Orchestrator Architecture](ai-orchestrator-architecture.md)
