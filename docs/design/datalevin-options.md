# Datalevin Integration Options for bb-mcp-server

**Created:** 2025-11-26
**Status:** Planning / Research
**Related:** Phase 15 in IMPLEMENTATION_PLAN.md

---

## Executive Summary

Datalevin is a Datalog database built on LMDB that can serve as both persistence layer and potential message bus for bb-mcp-server's AI orchestration system. This document captures deployment options, architecture choices, and critical limitations discovered during research.

**Key Finding:** `d/listen!` is CLIENT-SIDE only - not suitable for cross-process pub/sub without additional infrastructure.

---

## 1. Datalevin Overview

### Version
- **Current:** v0.9.27 (November 2025)
- **Pod:** `huahaiy/datalevin "0.9.27"`

### Key Features
- Datalog query language (Datomic-compatible)
- LMDB backend (C library, fast)
- Cost-based query optimizer
- Full-text search
- Vector database capabilities
- Auto entity timestamps (`:db/created-at`, `:db/updated-at`)

### Sources
- [GitHub Repository](https://github.com/juji-io/datalevin)
- [Clojars](https://clojars.org/datalevin)
- [Server Documentation](https://github.com/juji-io/datalevin/blob/master/doc/server.md)
- [API Documentation](https://cljdoc.org/d/datalevin/datalevin/0.9.12/api/datalevin.core)

---

## 2. Deployment Modes

### 2.1 Embedded Mode (Pod)

**How it works:**
```clojure
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.9.27")
(require '[pod.huahaiy.datalevin :as d])

(def conn (d/get-conn "/path/to/db" schema))
(d/transact! conn [{:person/name "Alice"}])
(d/q '[:find ?name :where [?e :person/name ?name]] (d/db conn))
```

**Pros:**
- Simple setup - just load pod
- Fast - in-process, no network overhead
- Works in Babashka
- Full API access including `d/listen!`

**Cons:**
- Single process only (LMDB locking)
- Cannot share DB across processes concurrently
- Pod must be loaded in each process

**Use case:** Single bb-mcp-server instance, all modules in one process

### 2.2 Client/Server Mode

**Server setup:**
```bash
# Native binary (faster startup)
dtlv serv -p 8898 -r /var/lib/datalevin

# JVM version (higher throughput, better for production)
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     -jar datalevin-0.9.22-standalone.jar serv -r /data/dtlv
```

**Client connection:**
```clojure
;; Connection URI format
;; dtlv://<username>:<password>@<hostname>:<port>/<db-name>?store=datalog|kv

(def conn (d/get-conn "dtlv://datalevin:datalevin@localhost:8898/mydb"))
```

**Pros:**
- Multiple processes can connect concurrently
- Built-in RBAC (role-based access control)
- Same API as embedded (transparent proxying)
- Work-stealing thread pool for high concurrency
- Consistent view across all clients (with clock sync)

**Cons:**
- Network overhead (milliseconds vs microseconds)
- Requires running server process
- `d/listen!` is still client-side only (see limitations)
- More operational complexity

**Use case:** Multiple bb-mcp-server instances, shared state across processes

### 2.3 Docker Deployment

```bash
docker run -d \
  --name datalevin \
  -p 8898:8898 \
  -v /data/datalevin:/var/lib/datalevin \
  huahaiy/datalevin
```

**Use case:** Containerized deployments, cloud environments

---

## 3. Critical Limitations

### 3.1 `d/listen!` is Client-Side Only

From the documentation:
> "Change listening is handled on the client side, which is the same as in the local embedded mode."

**What this means:**
- `d/listen!` registers a callback on the CLIENT
- Callback fires when THAT CLIENT's transactions complete
- **NOT** a server-push mechanism
- Other clients do NOT receive notifications of changes

**Implications for pub/sub:**
```clojure
;; Process A
(d/listen! conn :listener-a
  (fn [tx-report] (println "A saw change")))
(d/transact! conn [{:msg/content "hello"}])
;; => "A saw change" (fires)

;; Process B (separate process, same server)
(d/listen! conn :listener-b
  (fn [tx-report] (println "B saw change")))
;; Process B does NOT see Process A's transaction!
```

**Workarounds:**
1. Polling with timestamps
2. Separate notification channel (Redis pub/sub, NATS, etc.)
3. Application-level coordination

### 3.2 No True Stored Procedures/Triggers

Datalevin has `inter-fn` for server-side execution, but:
- Functions are sent to server and executed in SCI sandbox
- Used for filtering predicates and transaction functions
- **NOT automatic triggers** on data changes
- No way to register "on insert, call this function"

```clojure
;; inter-fn example - manual invocation, not automatic trigger
(require '[datalevin.interpret :as di])

(di/definterfn my-tx-fn [db data]
  [{:entity/value (str "processed: " data)}])

;; Must be explicitly called - not triggered automatically
(d/transact! conn [[:db.fn/call my-tx-fn "input"]])
```

### 3.3 LMDB Single-Writer Constraint

- LMDB allows only ONE writer at a time
- Multiple readers are fine
- In client/server mode, server serializes writes
- In embedded mode, only one process can open DB

---

## 4. Architecture Options for bb-mcp-server

### Option A: Embedded + Separate Message Bus (Recommended for Phase 15)

```
┌─────────────────────────────────────────┐
│           bb-mcp-server                 │
│                                         │
│  ┌─────────────┐    ┌────────────────┐  │
│  │ Datalevin   │    │  Message Bus   │  │
│  │ (embedded)  │    │ (atoms+promise)│  │
│  │             │    │                │  │
│  │ Persistence │    │ Real-time      │  │
│  │ Queries     │    │ Pub/Sub        │  │
│  └─────────────┘    └────────────────┘  │
│                                         │
└─────────────────────────────────────────┘
```

**How it works:**
- Datalevin for persistence (conversation history, expert configs)
- Keep existing atoms+promises message-bus for real-time pub/sub
- Single process - no multi-process coordination needed

**Pros:**
- Simplest to implement
- No new infrastructure
- Datalevin `d/listen!` works for local notifications
- Existing message-bus patterns preserved

**Cons:**
- Single process limitation
- Can't scale horizontally

### Option B: Client/Server + Polling

```
┌──────────────┐     ┌──────────────┐
│ bb-mcp-server│     │ bb-mcp-server│
│  (process 1) │     │  (process 2) │
└──────┬───────┘     └──────┬───────┘
       │                    │
       │   dtlv://...       │
       └────────┬───────────┘
                │
        ┌───────▼───────┐
        │ Datalevin     │
        │ Server        │
        │ (dtlv serv)   │
        └───────────────┘
```

**How it works:**
- Run `dtlv serv` as background process
- Multiple bb-mcp-server instances connect as clients
- Poll for changes using timestamps or sequence numbers
- Each client maintains its own `d/listen!` for local awareness

**Polling pattern:**
```clojure
(def last-seen-tx (atom 0))

(defn poll-for-changes! []
  (let [new-msgs (d/q '[:find ?e ?content ?ts
                        :in $ ?since
                        :where [?e :msg/content ?content]
                               [?e :msg/timestamp ?ts]
                               [(> ?ts ?since)]]
                      (d/db conn) @last-seen-tx)]
    (when (seq new-msgs)
      (reset! last-seen-tx (apply max (map #(nth % 2) new-msgs)))
      (process-new-messages new-msgs))))

;; Run every 100ms
(future (while true (poll-for-changes!) (Thread/sleep 100)))
```

**Pros:**
- Multiple processes can share state
- Survives process restarts
- Built-in RBAC

**Cons:**
- Polling latency (100ms-1s depending on interval)
- More complex
- Requires running server process

### Option C: Client/Server + External Pub/Sub

```
┌──────────────┐     ┌──────────────┐
│ bb-mcp-server│     │ bb-mcp-server│
│  (process 1) │     │  (process 2) │
└──────┬───────┘     └──────┬───────┘
       │                    │
       ├────dtlv://─────────┤
       │                    │
       ├────Redis pub/sub───┤
       │                    │
┌──────▼───────┐     ┌──────▼───────┐
│ Datalevin    │     │ Redis        │
│ Server       │     │ (pub/sub)    │
└──────────────┘     └──────────────┘
```

**How it works:**
- Datalevin for persistence and queries
- Redis/NATS for real-time notifications
- On write: transact to Datalevin, publish to Redis
- Subscribers get instant notifications via Redis

**Pros:**
- True real-time pub/sub
- Scalable
- Battle-tested infrastructure

**Cons:**
- Two systems to manage
- Consistency between DB and pub/sub
- Overkill for single-laptop use case

### Option D: Hybrid - Embedded with File Watching

```clojure
;; Process A writes, touches marker file
(d/transact! conn data)
(spit "/tmp/datalevin-notify" (System/currentTimeMillis))

;; Process B watches marker file
(require '[babashka.fs :as fs])
(fs/watch "/tmp"
  (fn [event]
    (when (= (:path event) "datalevin-notify")
      (reload-and-process!))))
```

**Pros:**
- Simple
- No additional infrastructure

**Cons:**
- Hacky
- File system dependent
- Race conditions possible

---

## 5. Recommended Approach for Phase 15

### Phase 15A-B: Start with Embedded Mode

1. Create `modules/datalevin/` module
2. Load pod, manage connection lifecycle
3. Implement conversation persistence
4. Use `d/listen!` for local module coordination
5. Keep existing message-bus for cross-module pub/sub

### Phase 15C: Evaluate Migration Need

After 15A-B, evaluate if multi-process is actually needed:
- If single process sufficient → stay embedded
- If multi-process needed → migrate to client/server + polling

### Deferred: External Pub/Sub

Only add Redis/NATS if:
- Multiple machines need to share state
- Sub-100ms notification latency required
- Horizontal scaling is a real requirement

---

## 6. Validation Tests Before Implementation

### Test 1: Multi-Process Concurrent Access (Client/Server)

```bash
# Terminal 1: Start server
dtlv serv -p 8898 -r /tmp/test-datalevin

# Terminal 2: Client A - write
bb -e '
(require '"'"'[babashka.pods :as pods])
(pods/load-pod '"'"'huahaiy/datalevin "0.9.22")
(require '"'"'[pod.huahaiy.datalevin :as d])
(def conn (d/get-conn "dtlv://datalevin:datalevin@localhost:8898/test"))
(d/transact! conn [{:test/value "from-client-a" :test/ts (System/currentTimeMillis)}])
(println "Client A wrote")'

# Terminal 3: Client B - read
bb -e '
(require '"'"'[babashka.pods :as pods])
(pods/load-pod '"'"'huahaiy/datalevin "0.9.22")
(require '"'"'[pod.huahaiy.datalevin :as d])
(def conn (d/get-conn "dtlv://datalevin:datalevin@localhost:8898/test"))
(println "Client B sees:" (d/q '"'"'[:find ?v :where [_ :test/value ?v]] (d/db conn)))'
```

**Expected:** Client B sees Client A's data

### Test 2: Verify `d/listen!` Scope

```clojure
;; In same process - should work
(d/listen! conn :local (fn [tx] (println "Local listener fired")))
(d/transact! conn [{:test/value "local"}])
;; => "Local listener fired"

;; In different process - should NOT fire
;; (Run transaction from another process, verify listener doesn't fire)
```

**Expected:** Listener only fires for transactions from same client

### Test 3: `inter-fn` Server-Side Execution

```clojure
(require '[datalevin.interpret :as di])

(di/definterfn add-timestamp [db data]
  [{:entity/value data
    :entity/processed-at (System/currentTimeMillis)}])

(d/transact! conn [[:db.fn/call add-timestamp "test-data"]])

;; Query to verify timestamp was added server-side
(d/q '[:find ?v ?ts
       :where [?e :entity/value ?v]
              [?e :entity/processed-at ?ts]]
     (d/db conn))
```

**Expected:** Timestamp added by server, not client

---

## 7. Schema Design Considerations

### Conversation History Schema

```clojure
{:conversation/id      {:db/unique :db.unique/identity}
 :conversation/turns   {:db/cardinality :db.cardinality/many
                        :db/valueType :db.type/ref}

 :turn/id              {:db/unique :db.unique/identity}
 :turn/role            {}  ; :user, :assistant, :system
 :turn/content         {}
 :turn/timestamp       {}
 :turn/expert-id       {:db/valueType :db.type/ref}

 :expert/id            {:db/unique :db.unique/identity}
 :expert/name          {}
 :expert/provider      {}  ; :anthropic-http, :openai-http, :claude-subprocess
 :expert/capabilities  {:db/cardinality :db.cardinality/many}}
```

### Message Bus Schema (if migrating to Datalevin)

```clojure
{:msg/id        {:db/unique :db.unique/identity}
 :msg/topic     {}
 :msg/content   {}
 :msg/timestamp {}
 :msg/sender    {}
 :msg/request-id {}  ; For ask/reply correlation
 :msg/reply-to  {:db/valueType :db.type/ref}}
```

---

## 8. Existing Datalog MCP Servers (Reference)

### 8.1 theronic/datomic-mcp

**Source:** [github.com/theronic/datomic-mcp](https://github.com/theronic/datomic-mcp)

A Datomic MCP server built with the Modex library. Read-only access to Datomic databases.

**Tools Exposed (8 tools):**

| Tool | Description | Parameters |
|------|-------------|------------|
| `schema` | Query DB schema, returns seq of EDN maps | *none* |
| `entid` | Get entity ID from ident/keyword | `ident` (string, EDN) |
| `entity` | Get entity by ID | `eid` (number) |
| `touch` | Materialize all entity attributes | `eid` (number) |
| `pull` | Pull pattern on entity | `pattern` (string, EDN), `eid` (number) |
| `pull-many` | Pull pattern on multiple entities | `pattern` (string, EDN), `eids` (string, EDN) |
| `q` | Datalog query with pagination | `qry`, `args` (EDN strings), `offset`, `limit` |
| `q-with` | Query against speculative tx | `tx-data`, `qry`, `args` (EDN), `offset`, `limit` |
| `datoms` | Raw index access (eavt/aevt/avet/vaet) | `index`, `components`, `offset`, `limit` |

**Tool Definition Pattern (Modex):**
```clojure
(tools/tools
  (q
    "Query Datomic. Use the `schema` tool to learn valid attributes first."
    [{:keys [qry args offset limit]
      :type {qry :string, args :string, offset :number, limit :number}
      :or   {limit 100, offset 0}
      :doc  {qry    "EDN-encoded Datalog Query"
             args   "EDN-encoded vector of query arguments"
             offset "Pagination offset"
             limit  "Max results (default 100)"}}]
    (q-handler (d/db @!conn) {:qry qry :args args :offset offset :limit limit})))
```

**Key Design Decisions:**
- All complex data as EDN-encoded strings (MCP only supports `:string` and `:number`)
- Output also stringified with `(map pr-str results)`
- Built-in pagination (`offset`/`limit`) on all query tools
- `schema` tool helps AI learn DB structure before querying
- `q-with` enables speculative queries without actual writes
- **Read-only** - no `transact` tool exposed

### 8.2 Latacora MCP SDK

**Source:** [github.com/latacora/mcp-sdk](https://github.com/latacora/mcp-sdk)

A Clojure library for building MCP servers, used internally at Latacora for Datomic queries.

**Approach:**
- Uses Malli schemas for tool input/output validation
- Ring-compatible handlers
- Auto-generates JSON Schema from Malli for MCP

**Tool Definition Pattern:**
```clojure
(mcp/create-tool-specification
  {:name          "add"
   :title         "Add two numbers"
   :description   "Adds two numbers together"
   :input-schema  [:map [:a int?] [:b int?]]
   :output-schema [:map [:result int?]]
   :handler       (fn [_exchange {:keys [a b]}]
                    {:result (+ a b)})})
```

---

## 9. Proposed Datalevin MCP Tools

### 9.1 Design Principles

1. **EDN Native** - This is Clojure; EDN is the natural format for Datalog queries
2. **Semantic Tools** - Higher-level operations for common patterns
3. **Raw Access** - Keep Datalog query for power users
4. **Datalevin-Specific** - Leverage full-text search, vector search, KV store
5. **Safety First** - Read-only by default, explicit opt-in for writes

### 9.1.1 EDN Format Reference

EDN (Extensible Data Notation) is Clojure's native data format:

```clojure
;; Primitives
nil                          ; null
true false                   ; booleans
42 3.14 42N 3.14M            ; numbers (N=bigint, M=exact decimal)
"hello"                      ; strings
\c \newline \space           ; characters
:keyword :namespaced/keyword ; keywords (self-evaluating identifiers)
symbol namespaced/symbol     ; symbols (typically variable names)

;; Collections
(1 2 3)                      ; list (ordered)
[1 2 3]                      ; vector (indexed, random-access)
{:a 1 :b 2}                  ; map (key-value pairs)
#{:a :b :c}                  ; set (unique values)

;; Tagged literals
#inst "2025-11-26T10:30:00Z" ; instant (timestamp)
#uuid "550e8400-e29b-41d4-a716-446655440000" ; UUID

;; Datalog query example
[:find ?name ?age
 :in $ ?min-age
 :where
 [?e :person/name ?name]
 [?e :person/age ?age]
 [(>= ?age ?min-age)]]
```

AI models generate EDN directly - no JSON translation needed.

### 9.2 Tool Categories

#### Category A: Schema Discovery (Essential)

| Tool | Description | Why |
|------|-------------|-----|
| `schema` | List all attributes with types/cardinality | AI must understand DB structure first |
| `describe-entity` | Show all attributes for a specific entity | Explore data shape |

#### Category B: Query Tools (Core)

| Tool | Description | Parameters |
|------|-------------|------------|
| `query` | Raw Datalog query | `datalog` (EDN string), `args`, `limit`, `offset` |
| `pull` | Pull entity with pattern | `eid`, `pattern` (EDN or `[*]` default) |
| `pull-many` | Pull multiple entities | `eids`, `pattern` |
| `find-by` | Simple attribute lookup | `attribute`, `value`, `limit` |

#### Category C: Datalevin-Specific (Differentiators)

| Tool | Description | Parameters |
|------|-------------|------------|
| `search-text` | Full-text search | `query`, `attributes`, `limit` |
| `search-vector` | Vector similarity search | `vector`, `attribute`, `top-k` |
| `kv-get` | Key-value store access | `key` |
| `kv-range` | Key range scan | `start`, `end`, `limit` |

#### Category D: Write Tools (Optional, Guarded)

| Tool | Description | Parameters |
|------|-------------|------------|
| `transact` | Write data (if enabled) | `tx-data` (EDN) |

Note: All writes go through `transact`. The tx-data format supports:
- Entity maps: `{:person/name "Alice"}` - add/upsert entity
- Add facts: `[:db/add eid :attr value]` - add single fact
- Retract facts: `[:db/retract eid :attr value]` - remove single fact
- Retract entity: `[:db/retractEntity eid]` - remove entire entity

### 9.3 Detailed Tool Specifications

#### `schema`
```clojure
{:name        "schema"
 :description "Returns all user-defined attributes in the database with their types,
               cardinality, and constraints. Call this first to understand the data model."
 :input       {}
 :output      [{:attribute ":person/name"
                :type      ":db.type/string"
                :cardinality ":db.cardinality/one"
                :unique    nil
                :indexed   true}]}
```

#### `query`
```clojure
{:name        "query"
 :description "Execute a Datalog query. Use `schema` tool first to learn valid attributes.
               Returns results as a vector of tuples or maps depending on :find clause."
 :input       {:datalog "[:find ?name ?age :where [?e :person/name ?name] [?e :person/age ?age]]"
               :args    "[]"      ; Optional, defaults to []
               :limit   100       ; Optional, defaults to 100
               :offset  0}        ; Optional, defaults to 0
 :output      [["Alice" 30] ["Bob" 25]]}
```

#### `find-by` (Semantic Helper)
```clojure
{:name        "find-by"
 :description "Find entities by a single attribute value. Simpler than raw Datalog for
               common lookups. Returns full entities with all attributes."
 :input       {:attribute ":person/email"
               :value     "alice@example.com"
               :limit     10}
 :output      [{:db/id 123
                :person/name "Alice"
                :person/email "alice@example.com"
                :person/age 30}]}
```

#### `search-text` (Datalevin Feature)
```clojure
{:name        "search-text"
 :description "Full-text search across specified attributes. Datalevin indexes text
               attributes automatically. Returns matching entities with relevance scores."
 :input       {:query      "clojure developer"
               :attributes [":person/bio" ":person/skills"]  ; Optional, searches all text attrs
               :limit      20}
 :output      [{:entity {:db/id 123 :person/name "Alice" :person/bio "Senior Clojure developer..."}
                :score  0.95}]}
```

#### `search-vector` (Datalevin 0.9+ Feature)
```clojure
{:name        "search-vector"
 :description "Vector similarity search for semantic/embedding queries. Requires vectors
               to be stored in the database. Returns nearest neighbors by cosine similarity."
 :input       {:vector    [0.1, 0.2, 0.3, ...]  ; Query embedding
               :attribute ":document/embedding"
               :top-k     10}
 :output      [{:entity {:db/id 456 :document/title "Clojure Guide"}
                :distance 0.12}]}
```

#### `transact` (Guarded)
```clojure
{:name        "transact"
 :description "Write data to database. Only available if server started with --allow-writes.

               tx-data formats:
               - Entity map: {:person/name \"Alice\" :person/age 30}
               - Add fact: [:db/add eid :person/skills \"Clojure\"]
               - Retract fact: [:db/retract eid :person/skills \"Python\"]
               - Retract entity: [:db/retractEntity eid]

               Use tempids (negative numbers) for new entities:
               [{:db/id -1 :person/name \"Alice\"}
                {:db/id -2 :person/name \"Bob\" :person/manager -1}]"
 :input       {:tx-data "[{:person/name \"Charlie\" :person/age 28}]"}
 :output      {:tx-id 1001
               :tempids {-1 12350}}}
```

### 9.4 Comparison: Datomic MCP vs Proposed Datalevin MCP

| Aspect | Datomic MCP | Proposed Datalevin MCP |
|--------|-------------|------------------------|
| Schema discovery | `schema` | `schema`, `describe-entity` |
| Raw query | `q`, `q-with` | `query` |
| Entity access | `entity`, `touch`, `pull`, `pull-many` | `pull`, `pull-many` |
| Semantic helpers | None | `find-by` |
| Full-text search | None | `search-text` ✨ |
| Vector search | None | `search-vector` ✨ |
| KV store | None | `kv-get`, `kv-range` ✨ |
| Index access | `datoms`, `entid` | Deferred (rarely needed by AI) |
| Writes | None | `transact` (opt-in) |
| Format | EDN strings | EDN strings (native for Clojure) |

### 9.5 Module Architecture

Two separate modules:

```
┌─────────────────────────────────────┐
│         MCP Client (AI)             │
└──────────────┬──────────────────────┘
               │
      ┌────────┴────────┐
      │                 │
      ▼                 ▼
┌──────────┐    ┌──────────────┐
│ local-eval│    │ datalevin-mcp│
│ (raw clj) │    │ (schema/q/tx)│
└─────┬─────┘    └──────┬───────┘
      │                 │
      └────────┬────────┘
               │
               ▼
        ┌─────────────┐
        │datalevin-pod│
        │ (pod + conn)│
        └──────┬──────┘
               │
               ▼
        ┌─────────────┐
        │  Datalevin  │
        │   (LMDB)    │
        └─────────────┘
```

| Module | Purpose | AI Access |
|--------|---------|-----------|
| `datalevin-pod` | Pod lifecycle, connection management, expose API | Via `local-eval` (raw Clojure) |
| `datalevin-mcp` | MCP tools interface | Via MCP tools (`schema`/`q`/`transact`) |

**datalevin-pod** = infrastructure layer
**datalevin-mcp** = uses datalevin-pod, exposes structured MCP tools

### 9.6 Configuration

**Database Path:**
- Default: `/var/db/datalevin/bb-mcp-server`
- Override: `BB_MCP_DATALEVIN_PATH` environment variable

```clojure
(def default-db-path "/var/db/datalevin/bb-mcp-server")

(defn db-path []
  (or (System/getenv "BB_MCP_DATALEVIN_PATH")
      default-db-path))
```

### 9.7 Minimal Tool Interface

| Tool | Purpose |
|------|---------|
| `schema` | Discover data model (call first) |
| `q` | Read anything (Datalog) |
| `transact` | Write anything |

**3 tools total.** Everything else is convenience.

### 9.8 Implementation Priority

**Phase 1 (MVP):**
1. `datalevin-pod` module - pod loading, connection management
2. `datalevin-mcp` module - `schema`, `q`, `transact` tools

**Phase 2 (Convenience):**
3. `find-by` - Simple lookups without Datalog
4. `pull` - Entity retrieval by ID

**Phase 3 (Datalevin Features):**
5. `search-text` - Full-text search
6. `kv-get`, `kv-range` - KV store access

**Phase 4 (Advanced):**
7. `search-vector` - Vector similarity
8. Time travel (`:as-of` parameter)

---

## 10. Future Enhancements

Based on review feedback (see `datalevin-options-review.md`):

### 10.1 Time Travel Support
Add optional `:as-of` parameter to `q` and other read tools:
```clojure
;; Query as of a specific point in time
(q {:query "[:find ?name :where [?e :person/name ?name]]"
    :as-of #inst "2025-01-01T00:00:00Z"})

;; Or by transaction ID
(q {:query "..." :as-of 1000})
```
**Use case:** "What was the state before the last update?"

### 10.2 Safe EDN Reader
Wrap EDN parsing with helpful error messages:
```clojure
;; Bad: "Internal server error"
;; Good: {:error "Invalid EDN: Unmatched delimiter ']' at position 42"
;;        :hint "Check for missing opening bracket"}
```
Critical for AI self-correction when generating malformed EDN.

### 10.3 Additional Convenience Tools

**`describe-entity`** - Pull all attributes for an entity:
```clojure
{:name "describe-entity"
 :input {:eid 123}
 :output {:db/id 123 :person/name "Alice" :person/age 30}}
```

**`kv-get` / `kv-range`** - Direct LMDB key-value access:
```clojure
{:name "kv-get"
 :input {:key "config/settings"}
 :output {:theme "dark"}}

{:name "kv-range"
 :input {:start "log/2025-01" :end "log/2025-02" :limit 100}
 :output [["log/2025-01-01" {...}] ...]}
```

### 10.4 Transact Output
Keep output minimal to avoid context overflow:
```clojure
;; Return metadata, not full datoms
{:tx-id 1001
 :tempids {-1 12350 -2 12351}
 :datoms-added 5
 :datoms-retracted 2}
```

---

## 11. Open Questions

1. **Pod vs Native Client:** Can we use native Datalevin client in Babashka for client/server mode, or must we use pod?

2. **Connection Pooling:** Does pod handle connection pooling automatically for client/server mode?

3. **EDN is the right format:** This is a Clojure project - EDN is native. AI models can and should generate EDN directly for Datalog queries. No JSON translation layer needed.

3. **Transaction Isolation:** What isolation level does Datalevin provide for concurrent reads during writes?

4. **Backup/Restore:** What's the backup strategy for Datalevin data?

5. **Migration Path:** If we start embedded and need to migrate to client/server, what's the data migration story?

---

## 9. References

- [Datalevin GitHub](https://github.com/juji-io/datalevin)
- [Server Documentation](https://github.com/juji-io/datalevin/blob/master/doc/server.md)
- [datalevin.interpret API](https://cljdoc.org/d/datalevin/datalevin/0.8.25/api/datalevin.interpret)
- [Datalevin Server/Client](https://cljdoc.org/d/datalevin/datalevin/0.6.15/doc/datalevin-server-client)
- [Docker Image](https://github.com/huahaiy/docker-datalevin)
- Reference Project: `../scittle-nrepl-bb-dl-db/src/datalevin_service.clj`

---

*Document will be updated as validation tests are completed and architecture decisions are made.*
