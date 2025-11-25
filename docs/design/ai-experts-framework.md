# AI Domain Experts Framework - Architecture & Design

**Status:** Architecture Design Phase
**Phase:** Future (Post Phase 13)
**Date:** 2025-11-24
**Related:** ai-orchestrator-architecture.md

---

## Overview

This document explores a **domain expert system** layered on top of the AI orchestrator. Domain experts are specialized AI instances pre-loaded with curriculum knowledge and configured for specific tasks (code review, AWS deployment, documentation, etc.).

### Vision

**Problem:** Generic AI instances require extensive context on every interaction. Users must repeatedly explain project standards, domain knowledge, and best practices.

**Solution:** Pre-configured domain experts with:
- **Curriculum knowledge** - Pre-loaded domain-specific context
- **Capability metadata** - Searchable skills and domains
- **Tool access** - Restricted to relevant tools
- **Dynamic orchestration** - Created on-demand for subtasks

---

## Core Concepts

### Expert Definition vs Expert Instance

```
┌────────────────────────────────────────────────────────┐
│            Expert Registry (Static)                     │
│  Definitions: What experts exist, what they know        │
│                                                         │
│  - clojure-coder: code review, refactoring             │
│  - aws-deployment: serverless, Lambda, SAM             │
│  - documentation-writer: technical writing             │
└────────────────────────────────────────────────────────┘
                         ▲
                         │ lookup & instantiate
                         │
┌────────────────────────────────────────────────────────┐
│          Expert Instances (Dynamic)                     │
│  Running AI instances with loaded curriculum           │
│                                                         │
│  - "my-coder" (clojure-coder expert)                   │
│  - "deployer-1" (aws-deployment expert)                │
│  - "reviewer" (clojure-coder expert)                   │
└────────────────────────────────────────────────────────┘
```

### Key Features

1. **Discovery** - "Who can review Clojure code?"
2. **Curriculum** - Domain knowledge pre-loaded
3. **Capabilities** - Searchable skills (`:code-review`, `:deployment`)
4. **Dynamic creation** - Spawn experts on-demand for tasks
5. **Communication** - Experts coordinate through message bus

---

## Architecture Options

### Option 1: File-Based Registry (MVP - Simplest)

**Storage:** Manifest files in `curricula/*/manifest.edn`

**Pros:**
- Simple implementation (days, not weeks)
- Easy to edit/version control
- No database dependencies
- Works immediately

**Cons:**
- No querying beyond file scanning
- No relationships between experts
- No semantic search of curriculum
- No learning/evolution

**Structure:**
```
modules/domain-experts/
├── curricula/
│   ├── clojure-coder/
│   │   ├── manifest.edn
│   │   ├── essential/
│   │   │   ├── standards.md
│   │   │   └── telemetry.md
│   │   └── reference/
│   │       └── architecture.md
│   └── aws-deployment/
│       ├── manifest.edn
│       └── docs/
└── src/domain_experts/
    ├── core.clj
    ├── registry.clj      # Load manifests from disk
    └── curriculum.clj
```

**Querying:**
```clojure
;; Simple in-memory filtering
(filter #(contains? (:capabilities %) :code-review)
        (load-all-manifests))
```

---

### Option 2: Datalevin/Datalog Database (Structured)

**Storage:** Datalevin embedded database with Datalog queries

**Pros:**
- **Semantic search** - Find curriculum by topic/keyword
- **Relationships** - Link experts, prerequisites, shared knowledge
- **History** - Track curriculum versions, changes over time
- **Querying** - Complex queries ("experts who know Clojure AND AWS")
- **Learning** - Store interaction history, improve over time
- **Vector embeddings** - Semantic similarity search

**Cons:**
- Additional dependency (Datalevin ~500KB JAR)
- Learning curve (Datalog syntax)
- More complex setup
- Persistence/migration concerns

**Schema:**
```clojure
;; Expert definitions
{:expert/id               {:db/unique :db.unique/identity}
 :expert/title            {:db/valueType :db.type/string}
 :expert/capabilities     {:db/valueType :db.type/keyword
                           :db/cardinality :db.cardinality/many}
 :expert/domains          {:db/valueType :db.type/keyword
                           :db/cardinality :db.cardinality/many}
 :expert/version          {:db/valueType :db.type/string}
 :expert/curriculum       {:db/valueType :db.type/ref
                           :db/cardinality :db.cardinality/many}
 :expert/recommended-ai   {:db/valueType :db.type/string} ; EDN
 :expert/created-at       {:db/valueType :db.type/instant}

 ;; Curriculum documents
 :curriculum/id           {:db/unique :db.unique/identity}
 :curriculum/title        {:db/valueType :db.type/string}
 :curriculum/path         {:db/valueType :db.type/string}
 :curriculum/content      {:db/valueType :db.type/string}
 :curriculum/type         {:db/valueType :db.type/keyword} ; :essential/:reference
 :curriculum/topics       {:db/valueType :db.type/keyword
                           :db/cardinality :db.cardinality/many}
 :curriculum/order        {:db/valueType :db.type/long}
 :curriculum/embedding    {:db/valueType :db.type/tuple} ; Vector

 ;; Relationships
 :expert/prerequisites    {:db/valueType :db.type/ref ; Other experts
                           :db/cardinality :db.cardinality/many}
 :expert/related          {:db/valueType :db.type/ref
                           :db/cardinality :db.cardinality/many}}
```

**Queries:**
```clojure
;; Find experts with both Clojure AND AWS knowledge
(d/q '[:find ?title ?caps
       :where
       [?e :expert/domains :clojure]
       [?e :expert/domains :aws]
       [?e :expert/title ?title]
       [?e :expert/capabilities ?caps]]
     @conn)

;; Find curriculum about "telemetry"
(d/q '[:find ?expert ?doc-title ?content
       :where
       [?c :curriculum/topics :telemetry]
       [?c :curriculum/title ?doc-title]
       [?c :curriculum/content ?content]
       [?e :expert/curriculum ?c]
       [?e :expert/title ?expert]]
     @conn)

;; Find experts with prerequisites satisfied
(d/q '[:find ?expert
       :in $ ?available-experts
       :where
       [?e :expert/id ?expert]
       [?e :expert/prerequisites ?prereq]
       [(contains? ?available-experts ?prereq)]]
     @conn #{:clojure-basics :git-basics})
```

---

### Option 3: Hybrid (Recommended)

**Phase 1: File-based for MVP**
- Get experts working quickly
- Validate the concept
- Build core features

**Phase 2: Migrate to Datalevin when needed**
- When querying becomes complex
- When curriculum grows large (>50 docs)
- When relationships matter
- When learning/history needed

**Migration path:**
```clojure
;; Load manifest files INTO Datalevin
(defn migrate-manifests-to-db! []
  (doseq [manifest (load-all-manifests)]
    (transact-expert! manifest)))

;; Dual-mode registry (during transition)
(defn get-expert [expert-id]
  (or (db-get-expert expert-id)      ; Try DB first
      (file-get-expert expert-id)))   ; Fall back to files
```

---

## Datalog Database: Deep Dive

### Why Datalevin (vs other options)?

**Datalevin vs Datomic:**
- Datalevin: Embedded, no server, LMDB storage
- Datomic: Client/server, more complex setup
- **Decision:** Datalevin for simplicity

**Datalevin vs SQLite:**
- Datalevin: Datalog queries, schema, relationships
- SQLite: SQL, more tooling, wider support
- **Decision:** Datalevin for Datalog expressiveness

**Datalevin vs plain EDN files:**
- Datalevin: Querying, relationships, history
- EDN: Simple, no dependencies
- **Decision:** Start EDN, migrate to Datalevin

### Datalevin Benefits for This Use Case

1. **Semantic curriculum search**
   ```clojure
   ;; "What curriculum covers telemetry and error handling?"
   (d/q '[:find ?title
          :where
          [?c :curriculum/topics :telemetry]
          [?c :curriculum/topics :error-handling]
          [?c :curriculum/title ?title]]
        @conn)
   ```

2. **Expert relationships**
   ```clojure
   ;; "What experts work well together?"
   (d/q '[:find ?e1-title ?e2-title
          :where
          [?e1 :expert/related ?e2]
          [?e1 :expert/title ?e1-title]
          [?e2 :expert/title ?e2-title]]
        @conn)
   ```

3. **Curriculum evolution tracking**
   ```clojure
   ;; Datalevin supports history/time-travel
   (d/q '[:find ?title ?version ?time
          :where
          [?e :expert/title ?title ?tx]
          [?e :expert/version ?version ?tx]
          [?tx :db/txInstant ?time]]
        (d/history @conn))
   ```

4. **Vector similarity search** (with embeddings)
   ```clojure
   ;; Find curriculum similar to query
   (d/q '[:find ?title ?similarity
          :in $ ?query-embedding
          :where
          [?c :curriculum/title ?title]
          [?c :curriculum/embedding ?embedding]
          [(cosine-similarity ?query-embedding ?embedding) ?similarity]
          [(> ?similarity 0.8)]]
        @conn query-vec)
   ```

### Datalevin Cost/Complexity

**Dependency:**
```clojure
;; bb.edn
{:deps {datalevin/datalevin {:mvn/version "0.9.10"}}} ; ~500KB JAR
```

**Setup:**
```clojure
(require '[datalevin.core :as d])

;; Create DB
(def conn (d/get-conn "data/experts.db" schema))

;; Query
(d/q '[:find ?title :where [?e :expert/title ?title]] @conn)

;; Close
(d/close conn)
```

**Learning curve:** 1-2 days to be productive with Datalog

---

## Communication Architecture: Message Bus

### Problem

How do experts communicate?

**Scenarios:**
1. **User → Orchestrator → Expert** - User asks question, routed to right expert
2. **Orchestrator → Multiple Experts** - Distribute subtasks
3. **Expert → Expert** - Peer collaboration (code review → deployment)
4. **Expert → Orchestrator → User** - Results aggregation

### Option A: Centralized (Hub-and-Spoke)

All communication through orchestrator:

```
         ┌──────────────┐
         │ Orchestrator │  ← Central message router
         └──────────────┘
          ↙    ↓    ↘
    ┌────┐  ┌────┐  ┌────┐
    │E1  │  │E2  │  │E3  │  ← Experts never talk directly
    └────┘  └────┘  └────┘
```

**Pros:**
- Simple - single point of control
- Easy to log/audit all communication
- Clear authority (orchestrator decides routing)

**Cons:**
- Bottleneck - all messages through one point
- No peer collaboration patterns
- Orchestrator must understand all message types

**Implementation:**
```clojure
;; Orchestrator receives all messages
(defn route-message [from to message]
  (let [target (get-instance to)]
    (send-message target message)))

;; Expert sends to orchestrator, which routes
(ask-orchestrator "expert-2" "Review this code")
```

---

### Option B: Peer-to-Peer (Distributed)

Experts can communicate directly:

```
         ┌──────────────┐
         │ Orchestrator │  ← Facilitates, doesn't control
         └──────────────┘
                ↓
    ┌────┐  ┌────┐  ┌────┐
    │E1  │←→│E2  │←→│E3  │  ← Experts talk directly
    └────┘  └────┘  └────┘
```

**Pros:**
- Flexible collaboration patterns
- No bottleneck
- Emergent workflows

**Cons:**
- Complex - who coordinates?
- Hard to audit/debug
- Potential loops/deadlocks

**Implementation:**
```clojure
;; Expert gets registry of other experts
(defn ask-peer [peer-name message]
  (let [peer (registry/get-instance peer-name)]
    (ai/ask peer message)))

;; Orchestrator just provides discovery
(find-experts-by-capability :code-review)
```

---

### Option C: Team-Based (Recommended)

Experts organized into teams with a coordinator:

```
┌─────────────────────────────────────────────┐
│              Orchestrator                    │  ← Creates teams
└─────────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────────┐
│         Team: "deploy-clojure-app"          │
│  Coordinator: orchestrator or lead expert   │
├─────────────────────────────────────────────┤
│  clojure-coder    (reviews code)            │
│  aws-deployment   (creates SAM template)    │
│  documentation    (updates README)          │
└─────────────────────────────────────────────┘
         ↕                ↕              ↕
    Team bus       Team bus         Team bus
```

**Pros:**
- Structured collaboration
- Clear responsibilities
- Scalable (many teams in parallel)
- Auditable per team

**Cons:**
- More complex setup
- Need team lifecycle management

**Implementation:**
```clojure
(defn create-team
  [team-id expert-ids]
  {:team-id team-id
   :members (map start-expert! expert-ids)
   :bus (create-message-bus)
   :coordinator (first members)})

;; Team members communicate via team bus
(defn team-broadcast [team message]
  (doseq [member (:members team)]
    (send-to-bus (:bus team) member message)))
```

---

### Message Bus Implementation Options

**Option 1: core.async channels**
```clojure
(require '[clojure.core.async :as async])

(def message-bus (async/chan 100))

;; Producer
(async/>!! message-bus {:to :expert-2 :msg "Review code"})

;; Consumer
(async/go-loop []
  (when-let [msg (async/<! message-bus)]
    (route-message msg)
    (recur)))
```

**Option 2: Simple atom queue**
```clojure
(defonce message-queue (atom clojure.lang.PersistentQueue/EMPTY))

(defn enqueue! [msg]
  (swap! message-queue conj msg))

(defn dequeue! []
  (let [msg (peek @message-queue)]
    (swap! message-queue pop)
    msg))
```

**Option 3: Pub/Sub with topics**
```clojure
;; core.async pub/sub
(def bus-chan (async/chan))
(def pub (async/pub bus-chan :topic))

;; Subscribe to topic
(def code-review-chan (async/chan))
(async/sub pub :code-review code-review-chan)

;; Publish
(async/>!! bus-chan {:topic :code-review :msg "Review needed"})
```

**Recommendation:** Start with Option C (Team-Based) + core.async for MVP

---

## Dynamic Expert Orchestration

### On-Demand Expert Creation

**Scenario:** User asks: "Deploy my Clojure app to AWS"

**Orchestrator analysis:**
1. Parse request → identify subtasks
2. Match subtasks to expert capabilities
3. Create required experts on-demand
4. Coordinate execution
5. Aggregate results
6. Cleanup experts

**Flow:**
```
User Request: "Deploy my Clojure app to AWS"
        ↓
┌─────────────────────────────────────────┐
│    Orchestrator (Task Analysis)         │
│  - Code needs review? → need clojure-   │
│    coder                                │
│  - AWS deployment? → need aws-deployment│
│  - Documentation? → need doc-writer     │
└─────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────┐
│  Create Team:                           │
│  1. Start clojure-coder                 │
│  2. Start aws-deployment                │
│  3. Start documentation-writer          │
└─────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────┐
│  Execute Tasks:                         │
│  1. clojure-coder: review & lint code   │
│  2. aws-deployment: create SAM template │
│  3. documentation-writer: update README │
└─────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────┐
│  Aggregate Results → User               │
│  Stop team experts                      │
└─────────────────────────────────────────┘
```

### Implementation

```clojure
(defn execute-complex-task
  "Analyze task, create experts, coordinate, cleanup.

   Arguments:
     task-description - User's request string

   Returns:
     Aggregated results from all experts"
  [task-description]

  ;; 1. Task analysis
  (let [subtasks (analyze-task task-description)
        ;; => [{:task "review code" :capability :code-review}
        ;;     {:task "deploy to AWS" :capability :deployment}]

        ;; 2. Find required experts
        required-experts (mapcat #(find-experts-by-capability (:capability %))
                                 subtasks)
        ;; => [:clojure-coder :aws-deployment]

        ;; 3. Create team
        team (create-team (str "task-" (random-uuid))
                         required-experts)

        ;; 4. Distribute subtasks
        results (doall
                 (for [subtask subtasks]
                   (let [expert (find-expert-for-task team subtask)]
                     (ask-expert expert (:task subtask)))))

        ;; 5. Aggregate results
        final-result (aggregate-results results)]

    ;; 6. Cleanup
    (stop-team! team)

    final-result))

(defn analyze-task
  "Use meta-orchestrator AI to break down task into subtasks.

   Could use Claude with prompt:
   'Analyze this task and list required expert capabilities'"
  [task-description]
  ;; Call orchestrator AI to analyze
  ...)
```

---

## Dedicated MCP Servers Per Expert Domain

### The Advantage

**Problem:** Single MCP server exposes ALL tools to ALL experts
- Context pollution - experts see 50+ irrelevant tools
- Security risk - experts access tools they shouldn't
- Wasted tokens - tool listings consume context

**Solution:** Each expert gets curated MCP server with only relevant tools
- **clojure-coder** → MCP server with: nrepl, clj-kondo, cljfmt, local-eval
- **aws-deployment** → MCP server with: aws-serverless tools only
- **documentation** → MCP server with: github, memory tools only

**Benefits:**
1. **Focus** - 80% reduction in tool list size
2. **Security** - Principle of least privilege
3. **Performance** - Faster startup, smaller registry
4. **Isolation** - Independent lifecycles
5. **Flexibility** - Mix local and cloud MCP servers

---

### Option A: Stdio-Based Dedicated Servers

**Pattern:** Claude subprocess spawns MCP server as child process

```
┌────────────────────────────────────────┐
│  Claude Process (expert instance)      │
│  Command: claude --mcp-server ...     │
└────────────────────────────────────────┘
         ↓ spawns & connects via stdio
┌────────────────────────────────────────┐
│  Dedicated MCP Server (subprocess)     │
│  Command: bb server:clojure-tools      │
│  Modules: nrepl, clj-kondo, cljfmt    │
└────────────────────────────────────────┘
```

**Implementation:**

**1. Create config files per domain:**
```clojure
;; config/clojure-tools.edn
{:modules [:nrepl :local-eval :clj-kondo :cljfmt]}

;; config/aws-tools.edn
{:modules [:aws-serverless]}

;; config/documentation.edn
{:modules [:github :memory]}
```

**2. Add bb tasks for each server:**
```clojure
;; bb.edn
{:tasks
 {:server:clojure-tools
  {:doc "MCP server with Clojure development tools only"
   :task (do
           (System/setProperty "mcp.config" "config/clojure-tools.edn")
           (apply (requiring-resolve 'bb-mcp-server.main/-main)
                  (cons "--stdio" *command-line-args*)))}

  :server:aws-tools
  {:doc "MCP server with AWS deployment tools only"
   :task (do
           (System/setProperty "mcp.config" "config/aws-tools.edn")
           (apply (requiring-resolve 'bb-mcp-server.main/-main)
                  (cons "--stdio" *command-line-args*)))}}}
```

**3. Expert manifest references bb task:**
```clojure
;; curricula/clojure-coder/manifest.edn
{:expert-id :clojure-coder
 :mcp-server {:command ["bb" "server:clojure-tools" "--stdio"]
              :transport :stdio}
 ...}
```

**4. Spawn expert with dedicated server:**
```clojure
(start-expert! :clojure-coder)
;; Behind the scenes:
;; 1. Load manifest
;; 2. Build Claude command:
;;    ["claude"
;;     "--mcp-server" "bb server:clojure-tools --stdio"
;;     "--model" "claude-3-5-haiku"]
;; 3. Start Claude process
;;    - Claude spawns bb server as subprocess
;;    - Claude connects via stdio
;;    - MCP server lifecycle tied to Claude
```

**Pros:**
- ✅ Simple - Claude manages subprocess lifecycle
- ✅ No ports - stdio connection (no conflicts)
- ✅ Lightweight - same binary, different config
- ✅ Auto-cleanup - server dies with Claude

**Cons:**
- ❌ Stdio only - can't connect multiple clients
- ❌ No remote servers - must be local subprocess
- ❌ Hard to debug - stdio streams are opaque
- ❌ No dynamic tool addition - must restart

---

### Option B: HTTP-Based Dedicated Servers (RECOMMENDED)

**Pattern:** Independent MCP servers on different ports, Claude connects via HTTP

```
┌──────────────────────────────────────────────────────┐
│  Claude Process (expert instance)                     │
│  Config: --mcp-server http://localhost:19880         │
└──────────────────────────────────────────────────────┘
         ↓ HTTP connection (MCP Streamable HTTP)
┌──────────────────────────────────────────────────────┐
│  Dedicated MCP Server (independent process)          │
│  Port: 19880                                         │
│  Command: bb server:clojure-tools --http 19880       │
│  Modules: nrepl, clj-kondo, cljfmt                   │
│  PID file: .pid/19880.pid                            │
└──────────────────────────────────────────────────────┘
```

**Implementation:**

**1. Port allocation strategy:**
```clojure
;; Port ranges per domain
(def domain-port-ranges
  {:clojure-tools   [19880 19889]  ; 10 possible experts
   :aws-tools       [19890 19899]
   :documentation   [19900 19909]
   :research        [19910 19919]})

(defn allocate-port [domain]
  (let [[start end] (get domain-port-ranges domain)
        available (filter port-available? (range start (inc end)))]
    (first available)))
```

**2. Start dedicated server:**
```clojure
(defn start-dedicated-mcp-server!
  "Start domain-specific MCP server on available port.

   Arguments:
     domain - Domain keyword (:clojure-tools, :aws-tools)
     config - Optional config overrides

   Returns:
     {:port ... :server ... :url ...}"
  [domain & [config]]
  (let [port (allocate-port domain)
        config-file (str "config/" (name domain) ".edn")

        ;; Start server in background
        cmd ["bb" "server" "--http" (str port)
             "--config" config-file]
        proc (p/process cmd {:out :inherit
                             :err :inherit})]

    ;; Wait for server to be ready
    (wait-for-health (str "http://localhost:" port "/health"))

    {:domain domain
     :port port
     :proc proc
     :url (str "http://localhost:" port)
     :health-url (str "http://localhost:" port "/health")
     :mcp-url (str "http://localhost:" port "/mcp")}))
```

**3. Expert manifest with HTTP server:**
```clojure
;; curricula/clojure-coder/manifest.edn
{:expert-id :clojure-coder
 :mcp-server {:domain :clojure-tools  ; Allocates port from range
              :transport :http
              :lifecycle :dedicated}   ; vs :shared
 ...}
```

**4. Start expert with HTTP-based server:**
```clojure
(defn start-expert! [expert-id]
  (let [expert-def (get-expert expert-id)
        mcp-config (:mcp-server expert-def)

        ;; 1. Start (or reuse) dedicated MCP server
        mcp-server (case (:lifecycle mcp-config)
                     :dedicated (start-dedicated-mcp-server! (:domain mcp-config))
                     :shared    (get-or-start-shared-server! (:domain mcp-config)))

        ;; 2. Build Claude command with HTTP MCP server
        claude-cmd ["claude"
                    "--mcp-server" (:mcp-url mcp-server)
                    "--model" (:model expert-def)
                    "--input-format" "stream-json"
                    "--output-format" "stream-json"]

        ;; 3. Start Claude subprocess
        claude-proc (p/process claude-cmd {:shutdown p/destroy-tree})]

    ;; 4. Return expert instance with both processes
    {:name expert-id
     :claude-proc claude-proc
     :mcp-server mcp-server
     :expert-def expert-def}))

(defn stop-expert! [expert-instance]
  ;; 1. Stop Claude process
  (p/destroy-tree (:claude-proc expert-instance))

  ;; 2. Stop dedicated MCP server (if not shared)
  (when (= :dedicated (get-in expert-instance [:expert-def :mcp-server :lifecycle]))
    (stop-mcp-server! (:mcp-server expert-instance))))
```

**Pros:**
- ✅ **Remote servers** - Can run MCP servers in cloud
- ✅ **Shared servers** - Multiple experts can use same server
- ✅ **Dynamic tools** - Can hot-reload modules without restart
- ✅ **Debuggable** - HTTP requests visible in logs
- ✅ **Multiple clients** - Same MCP server, multiple AI instances
- ✅ **Service discovery** - Can query available servers
- ✅ **Health checks** - Monitor server availability
- ✅ **REST API** - Tools also available via `/api/` endpoints

**Cons:**
- ❌ Port management - Need to allocate ports
- ❌ Cleanup complexity - Must stop servers explicitly
- ❌ Network overhead - HTTP vs stdio (minimal)

---

### Option C: Hybrid (Best of Both Worlds)

**Local experts:** Stdio (simpler)
**Remote/shared experts:** HTTP (flexible)

```clojure
;; Expert manifest with flexible transport
{:expert-id :clojure-coder

 ;; Option 1: Local stdio (simple)
 :mcp-server {:transport :stdio
              :command ["bb" "server:clojure-tools" "--stdio"]}

 ;; Option 2: Local HTTP (debuggable)
 :mcp-server {:transport :http
              :domain :clojure-tools
              :lifecycle :dedicated}

 ;; Option 3: Shared HTTP (efficient)
 :mcp-server {:transport :http
              :domain :clojure-tools
              :lifecycle :shared}

 ;; Option 4: Cloud HTTP (scalable)
 :mcp-server {:transport :http
              :url "https://clojure-tools.example.com/mcp"
              :auth {:type :api-key
                     :key-env "CLOJURE_TOOLS_API_KEY"}}}
```

---

### HTTP MCP Server: Advanced Features

#### 1. Dynamic Module Loading

```clojure
;; Start with base modules
(start-mcp-server! :clojure-tools {:modules [:nrepl :clj-kondo]})

;; Expert needs additional tool
(add-module! :clojure-tools :cljfmt)
;; Server hot-reloads, no restart needed

;; Broadcast tools/list_changed to all connected clients
(broadcast-tool-list-changed! :clojure-tools)
```

#### 2. Shared Server Pool

Multiple experts share same MCP server (resource efficient):

```clojure
;; Start shared server (first expert)
(start-expert! :clojure-coder-1)
;=> Creates MCP server on port 19880

;; Second expert reuses same server
(start-expert! :clojure-coder-2)
;=> Connects to existing server on port 19880

;; Both experts see same tools
;; Both contribute to server costs
```

#### 3. Cloud MCP Servers

Run MCP servers in cloud, experts connect remotely:

```clojure
;; Deploy MCP server to cloud
;; (One-time setup)
$ bb server --http 3000 --config config/clojure-tools.edn
$ # Deploy to Fly.io, Railway, or any cloud

;; Expert manifests reference cloud server
{:expert-id :clojure-coder
 :mcp-server {:transport :http
              :url "https://clojure-tools.fly.dev/mcp"
              :auth {:type :bearer
                     :token-env "MCP_AUTH_TOKEN"}}}

;; All experts worldwide use same MCP server
;; Centralized logging, monitoring, updates
```

#### 4. Service Discovery

```clojure
;; List available MCP servers
(list-available-servers)
;=> [{:domain :clojure-tools :port 19880 :health :healthy}
;    {:domain :aws-tools :port 19890 :health :healthy}]

;; Check server health
(check-mcp-server-health "http://localhost:19880")
;=> {:status :healthy :tools-count 5 :uptime-seconds 3600}

;; Get server info
(GET "http://localhost:19880/api/server")
;=> {:name "bb-mcp-server"
;    :version "0.11.0"
;    :modules [:nrepl :clj-kondo :cljfmt]
;    :tool-count 5}
```

---

### Comparison: Stdio vs HTTP

| Feature | Stdio | HTTP | Winner |
|---------|-------|------|--------|
| **Simplicity** | Simple subprocess | Port management | Stdio |
| **Cleanup** | Auto (child process) | Manual stop | Stdio |
| **Debugging** | Opaque streams | HTTP logs | HTTP |
| **Remote access** | ❌ Local only | ✅ Cloud-ready | HTTP |
| **Shared servers** | ❌ One client | ✅ Multiple clients | HTTP |
| **Dynamic tools** | ❌ Must restart | ✅ Hot-reload | HTTP |
| **Tool introspection** | Limited | REST API | HTTP |
| **Network overhead** | None | Minimal (~10ms) | Stdio |
| **Port conflicts** | ❌ None | ⚠️ Need allocation | Stdio |
| **Service discovery** | ❌ Not possible | ✅ Query endpoints | HTTP |

**Recommendation:** HTTP for production, stdio for quick prototyping

---

### Implementation Roadmap

**Phase 1: Stdio proof-of-concept (13E)**
- 3 config files (clojure-tools, aws-tools, documentation)
- 3 bb tasks (server:clojure-tools, etc.)
- Expert manifests reference bb tasks
- Validate concept works

**Phase 2: HTTP migration (13F)**
- Port allocation strategy
- Shared vs dedicated lifecycle
- Health monitoring
- Dynamic module loading

**Phase 3: Cloud deployment (14+)**
- Deploy MCP servers to cloud
- Authentication/authorization
- Centralized monitoring
- Load balancing

---

## Critical Design Considerations

**Source:** Gemini 3 Pro review (2025-02-25)

### 1. The Expert Driver Pattern

**Problem:** Claude instances are fundamentally *reactive*. They wait for a user message, process it, and return a response. They do not inherently "listen" to a message bus.

**Solution:** Explicitly define the **Expert Driver** component - Clojure code running in the main process that wraps the Claude subprocess.

```
┌──────────────────────────────────────────┐
│          core.async Message Bus          │
│  (orchestrator, experts, tasks)          │
└──────────────────────────────────────────┘
              ▲            │
              │            ▼
      ┌───────────────────────────┐
      │    Expert Driver (BB)     │  ◄── This is NEW
      │  - Subscribes to bus      │
      │  - Formats messages       │
      │  - Manages history        │
      │  - Publishes responses    │
      └───────────────────────────┘
              ▲            │
              │            ▼
      ┌───────────────────────────┐
      │  Claude Subprocess        │
      │  (via stdin/API)          │
      └───────────────────────────┘
```

**Driver Responsibilities:**

1. **Bus subscription** - Listen for messages addressed to this expert
2. **Message formatting** - Convert bus messages to Claude API format
3. **History management** - Maintain conversation context (see next section)
4. **Response handling** - Parse Claude output and publish to bus
5. **Lifecycle** - Start/stop/restart subprocess

**Implementation sketch:**

```clojure
(defrecord ExpertDriver
  [expert-id           ; :clojure-coder
   process             ; Claude subprocess
   bus-channel         ; core.async channel
   history             ; atom with message list
   mcp-server])        ; dedicated MCP endpoint

(defn start-driver! [expert-def]
  (let [driver (map->ExpertDriver
                 {:expert-id (:id expert-def)
                  :history (atom [])
                  :bus-channel (async/chan)})

        ;; Start dedicated MCP server first
        mcp (start-dedicated-mcp-server! (:domain expert-def))

        ;; Start Claude subprocess with MCP connection
        proc (ai-orchestrator/start-instance!
               (:expert-id driver)
               {:provider-type :claude-subprocess
                :model (:model expert-def)
                :mcp-server (:url mcp)
                :system-prompt (load-essential-curriculum expert-def)})]

    (assoc driver :process proc :mcp-server mcp)))

(defn driver-loop! [driver]
  (async/go-loop []
    (when-let [msg (async/<! (:bus-channel driver))]
      ;; Format as user message
      (let [user-msg {:role "user" :content (:content msg)}]
        ;; Add to history
        (swap! (:history driver) conj user-msg)
        ;; Send to Claude
        (let [response (ai-orchestrator/ask (:expert-id driver) (:content msg))]
          ;; Add response to history
          (swap! (:history driver) conj {:role "assistant" :content (:content response)})
          ;; Publish to bus
          (async/>! message-bus {:from (:expert-id driver)
                                  :to (:reply-to msg)
                                  :content (:content response)})))
      (recur))))
```

### 2. State Management & Conversation History

**Question:** Where does the conversation history live?

**Options:**
- **A:** Inside Claude (implicit) - Process maintains state internally
- **B:** Managed by BB Server (explicit) - Driver maintains history

**Recommendation:** **Option B - BB Server manages history**

**Why:**
1. **Pause/Resume** - Kill the process, restart later with replayed history
2. **Debugging** - Inspect reasoning chain without querying the expert
3. **Checkpointing** - Save expert state at key points
4. **Context caching** - Optimize prompt caching with explicit control
5. **Portability** - Switch between subprocess and HTTP providers

**Implementation:**

```clojure
;; In ExpertDriver record
{:history (atom [])  ; Vector of message maps
 :checkpoints (atom [])  ; Saved states for rollback
}

;; Add message
(defn add-to-history! [driver role content]
  (swap! (:history driver) conj {:role role
                                  :content content
                                  :timestamp (System/currentTimeMillis)}))

;; Resume from checkpoint
(defn resume-from-checkpoint! [driver checkpoint-id]
  (let [saved-history (get-checkpoint driver checkpoint-id)]
    (reset! (:history driver) saved-history)
    ;; Restart process if needed
    (when-not (process-alive? (:process driver))
      (restart-with-history! driver))))

;; Replay history to new process
(defn restart-with-history! [driver]
  (let [old-history @(:history driver)
        new-proc (ai-orchestrator/start-instance! (:expert-id driver) {...})]
    ;; Send all history to warm up context
    (doseq [msg old-history]
      (when (= (:role msg) "user")
        (ai-orchestrator/ask (:expert-id driver) (:content msg))))
    (assoc driver :process new-proc)))
```

**Storage options:**
- **In-memory** (Phase 13E) - Simple atom, lost on restart
- **EDN files** (Phase 13F) - Persist to disk for durability
- **Datalevin** (Phase 14) - Query relationships, semantic search

### 3. Security: HTTP Server Binding

**Problem:** Dedicated MCP servers on HTTP introduce network sockets. Misconfiguration could expose tools to network.

**Requirements:**

1. **Localhost-only binding** - Default to `127.0.0.1` (NOT `0.0.0.0`)
2. **PID tracking** - Use `pid_util.clj` to prevent orphaned processes
3. **Port allocation** - Track which ports are used, prevent conflicts
4. **Cleanup on exit** - Kill child processes in shutdown hook

**Implementation:**

```clojure
(defn start-dedicated-mcp-server! [domain]
  (let [port (allocate-port domain)
        cmd ["bb" "server"
             "--http" (str port)
             "--config" (config-path domain)]
        proc (p/process cmd {:env {"MCP_BIND_HOST" "127.0.0.1"}})]  ; Force localhost

    ;; Write PID file
    (pid-util/write-pid-file! port (:pid proc))

    ;; Wait for health check
    (wait-for-health! port 5000)

    ;; Register for cleanup
    (register-cleanup-handler! #(kill-and-cleanup! proc port))

    {:port port
     :url (str "http://127.0.0.1:" port "/mcp")
     :pid (:pid proc)}))

(defn kill-and-cleanup! [proc port]
  (p/destroy proc)
  (pid-util/delete-pid-file! port)
  (release-port port))
```

**Security checklist:**
- [ ] Verify `streamable-http` binds to `127.0.0.1` by default
- [ ] Add configuration validation (reject `0.0.0.0` in expert configs)
- [ ] Test orphan cleanup (kill parent, verify children die)
- [ ] Document security model in expert manifest schema

### 4. Terminology Refinement

**Current terminology:** "Curriculum" is used for both definition and content.

**Proposed distinction:**

| Term | Meaning | Example |
|------|---------|---------|
| **Profile/Manifest** | Static definition | `manifest.edn` with ID, model, capabilities |
| **Context/Knowledge** | Documentation files | `standards.md`, `architecture.md` |
| **Persona** | System prompt instructions | "You are a Clojure code reviewer. Follow these standards..." |
| **Curriculum** | Combination of all above | Complete package that creates an expert |

**Usage in code:**

```clojure
{:expert-id :clojure-coder
 :profile {:title "Clojure Development Expert"
           :model "claude-3-5-haiku-20241022"
           :capabilities #{:code-review :refactoring}}
 :context {:essential ["docs/CLOJURE_EXPERT_CONTEXT.md"]
           :reference ["docs/CODE_REVIEW_CHECKLIST.md"]}
 :persona "You are an expert Clojure developer..."  ; Rendered from context
 :curriculum {...}}  ; Full package
```

**Recommendation:** Keep "curriculum" as the umbrella term (it's already in use), but use sub-terms for precision in code/comments.

### 5. Implementation Details

#### A. Expert Record

Formalize the Expert entity early to simplify lifecycle management:

```clojure
(defrecord Expert
  [id                  ; :clojure-coder-1
   expert-type         ; :clojure-coder (from registry)
   driver              ; ExpertDriver record
   status              ; :starting :ready :busy :stopped
   created-at
   last-active-at])

;; Registry tracks running experts
(defonce expert-instances (atom {}))  ; id -> Expert record

(defn register-expert! [expert]
  (swap! expert-instances assoc (:id expert) expert))

(defn get-expert [id]
  (get @expert-instances id))
```

#### B. MCP Server Integration

Ensure dedicated servers leverage `bb-mcp-server.main`:

```clojure
(defn start-dedicated-mcp-server! [domain port]
  (let [cmd ["bb" "-m" "bb-mcp-server.main"
             "--http" (str port)
             "--config" (str "config/experts/" (name domain) ".edn")]
        proc (p/process cmd)]
    ;; Wait for /health endpoint
    (wait-for-health! (str "http://127.0.0.1:" port "/health") 5000)
    {:port port :process proc}))
```

#### C. Tool Registry Integration

Experts could register their capabilities dynamically:

```clojure
;; In ai-orchestrator
(registry/register-tool!
  {:name "delegate_to_expert"
   :description "Delegate task to a domain expert"
   :inputSchema {:type "object"
                 :properties {:expert-type {:type "string"
                                            :enum (list-active-expert-types)}
                              :task {:type "string"}}}})

;; Handler
(defmethod handle-tool-call "delegate_to_expert" [params]
  (let [expert (find-or-create-expert! (:expert-type params))]
    (send-to-expert! expert (:task params))))
```

### 6. Critical Startup Sequence

**IMPORTANT:** MCP server MUST be started before spawning Claude instance.

#### Why This Order Matters

1. **Connection Dependency** - Claude CLI needs MCP server endpoint to be reachable at startup
2. **Health Verification** - Can verify MCP server is healthy before committing to Claude spawn
3. **Clean Error Handling** - If MCP fails, we know before spawning Claude
4. **No Race Conditions** - Server is guaranteed ready when Claude connects

#### Correct Sequence

```
Step 1: Analyze task → Identify required expert domain
        ↓
Step 2: Look up expert definition (capabilities, curriculum, tools)
        ↓
Step 3: ALLOCATE PORT (from port registry)
        ↓
Step 4: START dedicated MCP server (on allocated port)
        ↓
Step 5: WAIT for health check (ensure server responding)
        ↓
Step 6: Generate MCP config JSON (pointing to healthy server)
        ↓
Step 7: Spawn Claude instance (with --strict-mcp-config)
        ↓
Step 8: Load curriculum into Claude (system prompt)
        ↓
Step 9: Expert ready to receive tasks
```

#### Implementation

```clojure
(defn start-expert! [expert-id]
  (let [expert-def (get-expert-definition expert-id)
        domain (get-in expert-def [:mcp-server :domain])]

    ;; Step 3: Allocate port FIRST
    (let [port (ports/allocate-port!
                 {:domain domain
                  :service-type :mcp-server
                  :expert-id (generate-instance-id expert-id)})]

      (if-not port
        {:error true :message "No ports available in range"}

        (try
          ;; Step 4: Start MCP server
          (log/log! {:level :info
                     :msg "Starting dedicated MCP server"
                     :data {:expert expert-id :domain domain :port port}})

          (let [mcp-server (start-dedicated-mcp-server! domain port)

                ;; Step 5: Health check - CRITICAL!
                _ (wait-for-mcp-health! (:url mcp-server) 5000)

                ;; Update port registry with PID
                _ (ports/update-port-info! port
                    {:pid (:pid mcp-server)
                     :url (:url mcp-server)
                     :status :healthy})

                ;; Step 6: Generate config
                mcp-config (generate-mcp-config (:url mcp-server))

                ;; Step 7: NOW spawn Claude
                claude-proc (spawn-claude-with-mcp!
                              {:model (:model expert-def)
                               :system-prompt (load-essential-curriculum expert-def)
                               :mcp-config mcp-config
                               :strict true})]  ; --strict-mcp-config

            ;; Step 8-9: Register expert
            {:id (generate-instance-id expert-id)
             :expert-type expert-id
             :mcp-port port
             :mcp-server mcp-server
             :claude-process claude-proc
             :status :ready})

          (catch Exception e
            ;; CLEANUP on failure
            (log/log! {:level :error
                       :msg "Expert startup failed"
                       :data {:expert expert-id :error (str e)}})
            (ports/release-port! port)
            {:error true :message (str e)}))))))

(defn wait-for-mcp-health! [url timeout-ms]
  "Wait for MCP server to become healthy. Throws on timeout."
  (let [health-url (str/replace url #"/mcp$" "/health")
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        (throw (ex-info "MCP server health check timeout"
                        {:url url :timeout-ms timeout-ms}))
        (try
          (let [resp (http/get health-url {:socket-timeout 1000})]
            (if (= 200 (:status resp))
              (log/log! {:level :info
                         :msg "MCP server healthy"
                         :data {:url url}})
              (do (Thread/sleep 100)
                  (recur))))
          (catch Exception e
            (Thread/sleep 100)
            (recur)))))))
```

#### Shutdown Sequence (Reverse Order)

```
Step 1: Stop Claude instance (kill process)
        ↓
Step 2: Stop MCP server (graceful shutdown)
        ↓
Step 3: Release port (mark available in registry)
        ↓
Step 4: Cleanup PID files
        ↓
Step 5: Update expert status to :stopped
```

### 7. Port Management System

**Problem:** With 10+ concurrent experts, each with dedicated MCP server, we need structured port management.

**Solution:** Central port registry with allocation, discovery, and cleanup.

See [Port Management Architecture](port-management-architecture.md) for full design.

#### Key Features

1. **Port Allocation** - Assign unique ports from domain-specific ranges
2. **Port Discovery** - Find services by domain ("where is clojure-tools?")
3. **Port Tracking** - Know what's running on each port
4. **Health Monitoring** - Periodic checks, auto-cleanup dead services
5. **Persistence** - Survive bb-mcp-server restarts

#### Port Ranges Strategy

```clojure
{:port-ranges
 {:main-server [3000 3000]       ; Fixed port
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

#### Discovery Examples

```clojure
;; Find existing server for domain
(if-let [port (ports/discover-by-domain :clojure-tools)]
  ;; Reuse existing
  (let [info (ports/get-port-info port)]
    (if (= :healthy (:status info))
      (connect-to-existing-server (:url info))
      (restart-unhealthy-server port)))
  ;; Start new
  (start-new-mcp-server :clojure-tools))

;; Find port for specific expert instance
(ports/discover-by-expert :clojure-coder-1)
;; => 19880

;; List all active services
(ports/list-active-ports)
;; => {19880 {:domain :clojure-tools :status :healthy ...}
;;     19890 {:domain :aws-tools :status :healthy ...}}
```

#### Implementation: File-Based Registry (MVP)

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
         :status :healthy}}

 :domain-index
 {:clojure-tools 19880}

 :port-ranges {...}}
```

**API:**

```clojure
(ports/allocate-port! {:domain :clojure-tools
                       :service-type :mcp-server
                       :expert-id :clojure-coder-1})
;; => 19880

(ports/release-port! 19880)

(ports/discover-by-domain :clojure-tools)
;; => 19880

(ports/check-port-health! 19880)
;; => :healthy
```

---

## Phased Implementation Proposal

### Phase 13E: File-Based Expert Registry (MVP)

**Duration:** 3-5 days

**Goals:**
- Prove the concept with minimal infrastructure
- Get experts working end-to-end
- Validate curriculum loading approach

**Tasks:**
1. Create `modules/domain-experts/` structure
2. Implement file-based registry
   - Load manifests from `curricula/*/manifest.edn`
   - In-memory storage
   - Simple filtering queries
3. Create 2 expert definitions:
   - `clojure-coder` (use existing CLOJURE_EXPERT_CONTEXT.md)
   - `aws-deployment` (use aws-serverless MCP context)
4. Implement curriculum loading:
   - Essential docs → system prompt
   - Reference docs → MCP tool
5. Public API:
   - `list-expert-types`
   - `start-expert!`
   - `ask-expert`
   - `stop-expert!`
6. MCP tool exposure:
   - `expert_list_types`
   - `expert_start`
   - `expert_ask`
7. Integration tests

**Success criteria:**
- Start clojure-coder expert
- Ask it to review code
- Verify it uses curriculum knowledge
- Expert stops cleanly

---

### Phase 13F: Message Bus & Teams

**Duration:** 3-5 days

**Goals:**
- Enable multi-expert collaboration
- Implement team-based coordination
- Support complex workflows

**Tasks:**
1. Implement core.async message bus
   - Pub/sub topics
   - Message routing
   - Team channels
2. Team management:
   - Create/destroy teams
   - Member registration
   - Coordinator election
3. Communication patterns:
   - Broadcast to team
   - Direct peer-to-peer
   - Request/response correlation
4. Test multi-expert workflows:
   - Code review → deployment
   - Analysis → implementation → documentation

**Success criteria:**
- Create team with 2 experts
- Experts communicate via bus
- Complex task executes end-to-end

---

### Phase 13G: Dynamic Expert Orchestration

**Duration:** 5-7 days

**Goals:**
- Automatic expert selection
- On-demand expert creation
- Task analysis and decomposition

**Tasks:**
1. Task analyzer (using AI):
   - Parse user request
   - Identify required capabilities
   - Generate subtask plan
2. Expert matching:
   - Map subtasks to experts
   - Handle missing experts (error or create generic)
3. Execution coordinator:
   - Create team
   - Distribute subtasks
   - Monitor progress
   - Aggregate results
4. Lifecycle management:
   - Start experts on-demand
   - Keep-alive vs terminate
   - Resource limits (max experts)
5. MCP tools:
   - `task_execute` - Run complex task with auto-orchestration

**Success criteria:**
- User asks: "Deploy Clojure app to AWS"
- System creates team, executes, returns results
- Experts cleanup automatically

---

### Phase 14: Datalevin Migration (Optional)

**Duration:** 5-10 days

**Goals:**
- Migrate to queryable database
- Enable semantic search
- Support relationships & history

**Tasks:**
1. Add Datalevin dependency
2. Define schema
3. Migration script (files → DB)
4. Dual-mode registry (backward compat)
5. Implement Datalog queries:
   - Complex capability matching
   - Curriculum search
   - Relationship traversal
6. Vector embeddings for semantic search
7. History/versioning queries

**Success criteria:**
- All file-based queries work via Datalog
- New semantic search features
- Performance acceptable (<100ms queries)
- Can roll back to files if needed

---

### Phase 15: Learning & Evolution

**Duration:** Ongoing

**Goals:**
- Experts improve over time
- Curriculum evolves based on usage
- User feedback integration

**Tasks:**
1. Interaction logging
   - Store user questions
   - Store expert responses
   - Track satisfaction (thumbs up/down)
2. Curriculum updates:
   - Identify knowledge gaps
   - Suggest new curriculum
   - Version curriculum
3. Expert performance metrics:
   - Success rate per expert
   - Response quality
   - Cost efficiency
4. Auto-suggest new experts:
   - Analyze common tasks
   - Identify missing capabilities

---

## Datalog Database: Upfront Decision

### Arguments FOR Building Datalevin Upfront

**1. Foundation for growth**
- Avoid painful migration later
- Schema forces clear thinking
- Relationships designed from start

**2. Querying is core to this system**
- "Who can do X?" is THE question
- Semantic search needed soon
- Complex queries likely (prerequisites, teams)

**3. Learning requires history**
- Can't track curriculum evolution without DB
- Interaction history enables improvement
- Performance metrics need storage

**4. Not that complex**
- Datalevin simpler than SQL
- Embedded (no server)
- Datalog is expressive

**5. Future features depend on it**
- Team formation (query for compatible experts)
- Curriculum recommendations (similarity search)
- Expert evolution (track versions)

### Arguments AGAINST Building Datalevin Upfront

**1. Premature optimization**
- Don't know if we need these features yet
- File-based might be sufficient
- YAGNI principle

**2. Additional complexity**
- Learning curve for team
- Migration/backup concerns
- Debugging harder than files

**3. Can migrate later**
- Files → Datalevin is straightforward
- No data loss risk
- Allows validation of concept first

**4. Faster MVP**
- Files = days, Datalevin = weeks
- Get feedback sooner
- Iterate faster

**5. BB compatibility concerns**
- Datalevin works with bb, but less tested
- More moving parts
- Potential GraalVM/native-image issues

---

## Recommendation: Phased Approach with Early DB

**Compromise:** Build Datalevin in Phase 13F (not 13E, but before 14)

**Rationale:**
1. **Phase 13E (files)** - Validate concept, get experts working
2. **Phase 13F (migrate to DB)** - Before building message bus
3. **Phase 13G+** - Features that need DB are ready

**Why this works:**
- Experts work quickly (13E proves it)
- Migration happens before complex features
- DB in place before querying becomes critical
- Team learns Datalog while system is simple

**Migration strategy:**
```clojure
;; Phase 13E: Files
(defn get-expert [id]
  (load-manifest id))

;; Phase 13F: Transparent migration
(defn get-expert [id]
  (or (db/get-expert id)      ; Try DB first
      (load-manifest id)))     ; Fallback to file

;; Phase 13F: Background migration
(defn migrate-all! []
  (doseq [manifest (load-all-manifests)]
    (db/transact-expert! manifest)))

;; Phase 13G+: DB only
(defn get-expert [id]
  (db/get-expert id))
```

---

## Open Questions

### 1. Expert Lifecycle: Persistent vs Ephemeral?

**Option A: Persistent experts**
- Start once, keep running
- Maintain conversation history
- Lower startup cost (curriculum already loaded)

**Option B: Ephemeral experts (recommended)**
- Start on-demand for tasks
- Stop when task completes
- Lower resource usage
- Curriculum freshly loaded each time

**Decision:** Start with ephemeral, add persistence later if needed

### 2. Curriculum Loading: Eager vs Lazy?

**Option A: Eager (all curriculum in system prompt)**
- Simple implementation
- High context cost
- Works with current AI limits

**Option B: Lazy (via MCP tool)**
- Lower startup cost
- Expert must know to query
- More complex implementation

**Option C: Hybrid (recommended)**
- Essential in system prompt
- Reference via tool
- Best of both worlds

### 3. Team Size Limits?

How many experts in a team?
- Too few: Can't handle complex tasks
- Too many: Coordination overhead

**Recommendation:** Start with 2-5 experts per team, tune based on testing

### 4. Who Analyzes Tasks?

**Option A: Orchestrator AI**
- Single "meta" AI that analyzes and delegates
- Simple architecture
- Bottleneck risk

**Option B: Dedicated planner expert**
- Specialized in task decomposition
- Can be improved independently
- More experts to manage

**Option C: User-driven**
- User specifies experts/subtasks
- No AI overhead
- Less automation

**Recommendation:** Start with Option A, evolve to B

### 5. Expert Billing/Cost Tracking?

Track costs per expert?
- Budget limits per expert type?
- Cost reporting for teams?
- Alert on expensive queries?

**Recommendation:** Add in Phase 14+ when multiple providers exist

---

## Related Documents

- [ai-orchestrator-architecture.md](./ai-orchestrator-architecture.md) - Multi-provider AI orchestration
- [claude-subprocess-spawning-architecture.md](./claude-subprocess-spawning-architecture.md) - Claude subprocess patterns
- [IMPLEMENTATION_PLAN.md](../../IMPLEMENTATION_PLAN.md) - Overall project phases

---

*This is a living document. Update as design evolves and decisions are made.*
