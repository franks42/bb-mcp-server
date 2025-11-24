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
