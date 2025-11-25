# Message Bus Design for AI Expert Framework

**Status:** Design Phase
**Phase:** 13F (upcoming)
**Date:** 2025-11-25
**Related:** ai-experts-framework.md, IMPLEMENTATION_PLAN.md

---

## Executive Summary

This document evaluates message bus implementation options for the AI Expert Framework. The message bus enables communication between AI expert instances, the orchestrator, and team coordination.

**Recommendation:** Start with **Option 3 (Atoms + Promises)** for MVP, with a clear migration path to **core.async** if complexity grows.

---

## Requirements

### Functional Requirements

1. **Pub/Sub messaging** - Experts subscribe to topics, receive relevant messages
2. **Request/Response** - Ask an expert, get a response (with timeout)
3. **Team channels** - Isolated communication within teams
4. **Broadcast** - Send message to all team members
5. **Message routing** - Direct messages to specific experts

### Non-Functional Requirements

1. **Babashka compatible** - Must run in bb, not just JVM Clojure
2. **Debuggable** - Easy to inspect state, trace messages
3. **Simple** - Minimal learning curve for contributors
4. **Reliable** - No lost messages, proper error handling
5. **Performant** - Low latency for expert-to-expert communication

---

## Babashka Concurrency Primitives (Verified Available)

All of these work in Babashka:

| Primitive | Status | Use Case |
|-----------|--------|----------|
| `atom` | ✅ Built-in | State management |
| `add-watch` / `remove-watch` | ✅ Built-in | Event notification |
| `agent` / `send` | ✅ Built-in | Async state updates |
| `future` | ✅ Built-in | Background tasks |
| `promise` / `deliver` | ✅ Built-in | One-shot async values |
| `delay` | ✅ Built-in | Lazy computation |
| `core.async` | ✅ Built-in | Channels, go blocks, pub/sub |

**NOT available in Babashka:**
- Manifold (JVM-only, requires Java interop)
- Pulsar/Quasar (JVM-only, bytecode instrumentation)
- Meltdown/Reactor (JVM-only)

---

## Option 1: core.async

### Overview

Use `clojure.core.async` channels with built-in pub/sub.

```clojure
(require '[clojure.core.async :as async])

;; Create bus with pub/sub
(def bus-chan (async/chan 100))
(def pub (async/pub bus-chan :topic))

;; Subscribe expert to topic
(defn subscribe! [topic]
  (let [ch (async/chan 10)]
    (async/sub pub topic ch)
    ch))

;; Publish message
(defn publish! [topic msg]
  (async/>!! bus-chan {:topic topic :msg msg}))

;; Expert listener loop
(defn start-listener! [expert-id topics handler]
  (let [ch (async/chan 10)]
    (doseq [topic topics]
      (async/sub pub topic ch))
    (async/go-loop []
      (when-let [msg (async/<! ch)]
        (handler msg)
        (recur)))))
```

### Request/Response Pattern

```clojure
(defn ask-expert [expert-id message timeout-ms]
  (let [response-ch (async/chan 1)
        request-id (random-uuid)]
    ;; Subscribe to response topic
    (async/sub pub request-id response-ch)
    ;; Send request
    (publish! expert-id {:request-id request-id
                         :content message
                         :reply-to request-id})
    ;; Wait for response with timeout
    (let [[result _] (async/alts!! [response-ch
                                     (async/timeout timeout-ms)])]
      (async/unsub pub request-id response-ch)
      (async/close! response-ch)
      (or result :timeout))))
```

### Pros

| Benefit | Description |
|---------|-------------|
| Built-in pub/sub | `pub`/`sub` provides topic routing |
| Backpressure | Bounded channels prevent memory issues |
| Timeouts | `async/timeout` and `async/alts!` built-in |
| Composition | `mult`, `merge`, `pipe` for complex flows |
| Battle-tested | Widely used in Clojure ecosystem |
| bb compatible | ✅ Works in Babashka |

### Cons

| Drawback | Description |
|----------|-------------|
| Learning curve | Channels, parking vs blocking, go macros |
| Debugging | Channels are opaque, hard to inspect |
| Error handling | Errors in go blocks can be swallowed |
| `<!` quirk | Must use `async/<!` in Babashka (not bare `<!`) |
| Overhead | More machinery than simple atoms |

### When to Use

- High message volume (thousands/sec)
- Need backpressure
- Complex routing (mult, merge, mix)
- Team already knows core.async

---

## Option 2: Agents

### Overview

Use Clojure agents for async message dispatch.

```clojure
;; Message bus as agent
(def message-bus (agent {:subscribers {}  ; topic -> #{handler-fns}
                         :messages []}))  ; message log

(defn subscribe! [topic handler-fn]
  (send message-bus
        (fn [state]
          (update-in state [:subscribers topic]
                     (fnil conj #{}) handler-fn))))

(defn publish! [topic msg]
  (send message-bus
        (fn [state]
          ;; Log message
          (let [state' (update state :messages conj {:topic topic :msg msg :ts (System/currentTimeMillis)})]
            ;; Dispatch to subscribers (in separate futures)
            (doseq [handler (get-in state' [:subscribers topic])]
              (future
                (try
                  (handler msg)
                  (catch Exception e
                    (println "Handler error:" e)))))
            state'))))

;; Inspect state easily
(defn get-subscribers [] (:subscribers @message-bus))
(defn get-message-log [] (:messages @message-bus))
```

### Request/Response Pattern

```clojure
(defn ask-expert [expert-id message timeout-ms]
  (let [response (promise)
        request-id (random-uuid)]
    ;; Temporary subscription for response
    (subscribe! request-id
                (fn [msg]
                  (deliver response msg)
                  (unsubscribe! request-id)))
    ;; Send request
    (publish! expert-id {:request-id request-id
                         :content message
                         :reply-to request-id})
    ;; Wait with timeout
    (deref response timeout-ms :timeout)))
```

### Pros

| Benefit | Description |
|---------|-------------|
| Simple model | Just functions updating state |
| Inspectable | `@message-bus` shows all state |
| Error isolation | Agent errors don't crash system |
| Async dispatch | `send` is non-blocking |
| Message log | Easy to add logging/history |
| bb compatible | ✅ Works in Babashka |

### Cons

| Drawback | Description |
|----------|-------------|
| No backpressure | Unbounded message queue |
| Single bottleneck | All messages through one agent |
| Thread pool | `future` uses limited thread pool |
| Manual cleanup | Must manage subscriptions |
| No composition | No built-in mult/merge |

### When to Use

- Simple pub/sub needs
- Want message logging/history
- Debugging is priority
- Low-medium message volume

---

## Option 3: Atoms + Promises (Recommended for MVP)

### Overview

Simple pub/sub with atoms for state, promises for request/response.

```clojure
;; Subscriber registry
(defonce subscribers (atom {}))  ; {topic -> #{handler-fns}}

;; Message history (optional, for debugging)
(defonce message-log (atom []))

(defn subscribe!
  "Subscribe handler-fn to topic. Returns unsubscribe fn."
  [topic handler-fn]
  (swap! subscribers update topic (fnil conj #{}) handler-fn)
  ;; Return unsubscribe function
  (fn [] (swap! subscribers update topic disj handler-fn)))

(defn publish!
  "Publish message to topic. Dispatches to all subscribers async."
  [topic msg]
  (let [msg-with-meta (assoc msg :topic topic :ts (System/currentTimeMillis))]
    ;; Optional: log message
    (swap! message-log conj msg-with-meta)
    ;; Dispatch to subscribers
    (doseq [handler (get @subscribers topic #{})]
      (future
        (try
          (handler msg-with-meta)
          (catch Exception e
            (println "Handler error on" topic ":" (.getMessage e))))))))

(defn list-subscribers
  "List all topics and subscriber counts."
  []
  (into {} (map (fn [[k v]] [k (count v)]) @subscribers)))
```

### Request/Response Pattern

```clojure
(defn ask
  "Send request to topic, wait for response with timeout."
  [topic message & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [response (promise)
        request-id (str (random-uuid))
        reply-topic (keyword (str "reply-" request-id))

        ;; Subscribe to reply topic
        unsub (subscribe! reply-topic
                          (fn [msg]
                            (deliver response (:content msg))))]
    (try
      ;; Send request
      (publish! topic {:request-id request-id
                       :content message
                       :reply-to reply-topic})
      ;; Wait for response
      (let [result (deref response timeout-ms :timeout)]
        (if (= result :timeout)
          {:error :timeout :topic topic}
          {:success true :content result}))
      (finally
        ;; Cleanup subscription
        (unsub)))))
```

### Team Channels

```clojure
(defn create-team
  "Create isolated team with own pub/sub namespace."
  [team-id member-ids]
  (let [team-prefix (str "team:" (name team-id) ":")]
    {:id team-id
     :members (set member-ids)
     :prefix team-prefix
     :broadcast-topic (keyword (str team-prefix "broadcast"))
     :channels (atom {})}))

(defn team-publish!
  "Publish to team-scoped topic."
  [team topic msg]
  (publish! (keyword (str (:prefix team) (name topic))) msg))

(defn team-broadcast!
  "Broadcast to all team members."
  [team msg]
  (publish! (:broadcast-topic team) msg))

(defn team-subscribe!
  "Subscribe to team-scoped topic."
  [team topic handler-fn]
  (subscribe! (keyword (str (:prefix team) (name topic))) handler-fn))
```

### Full Implementation

```clojure
(ns bb-mcp-server.message-bus
  "Simple message bus for AI expert communication."
  (:require [taoensso.trove :as log]))

;;; State

(defonce ^:private subscribers (atom {}))
(defonce ^:private message-log (atom (clojure.lang.PersistentQueue/EMPTY)))
(def ^:private max-log-size 1000)

;;; Core API

(defn subscribe!
  "Subscribe handler-fn to topic. Returns unsubscribe fn.

   Arguments:
     topic      - Keyword identifying the topic
     handler-fn - (fn [msg] ...) called for each message

   Returns:
     Function to call to unsubscribe"
  [topic handler-fn]
  (log/log! {:level :debug
             :id ::subscribe
             :msg "Subscribing to topic"
             :data {:topic topic}})
  (swap! subscribers update topic (fnil conj #{}) handler-fn)
  (fn []
    (log/log! {:level :debug
               :id ::unsubscribe
               :msg "Unsubscribing from topic"
               :data {:topic topic}})
    (swap! subscribers update topic disj handler-fn)))

(defn publish!
  "Publish message to topic asynchronously.

   Arguments:
     topic - Keyword identifying the topic
     msg   - Message map to publish

   Returns:
     nil (fire-and-forget)"
  [topic msg]
  (let [enriched (assoc msg
                        :topic topic
                        :ts (System/currentTimeMillis)
                        :msg-id (str (random-uuid)))]
    ;; Log message (bounded queue)
    (swap! message-log
           (fn [q]
             (let [q' (conj q enriched)]
               (if (> (count q') max-log-size)
                 (pop q')
                 q'))))

    ;; Dispatch to subscribers
    (let [handlers (get @subscribers topic #{})]
      (log/log! {:level :trace
                 :id ::publish
                 :msg "Publishing message"
                 :data {:topic topic
                        :handler-count (count handlers)
                        :msg-id (:msg-id enriched)}})
      (doseq [handler handlers]
        (future
          (try
            (handler enriched)
            (catch Exception e
              (log/log! {:level :error
                         :id ::handler-error
                         :msg "Handler threw exception"
                         :data {:topic topic
                                :error (.getMessage e)}}))))))))

(defn ask
  "Send request and wait for response.

   Arguments:
     topic   - Topic to send request to
     message - Request content

   Options:
     :timeout-ms - Timeout in milliseconds (default: 30000)

   Returns:
     {:success true :content ...} or {:error :timeout}"
  [topic message & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [response (promise)
        request-id (str (random-uuid))
        reply-topic (keyword (str "reply:" request-id))
        start-time (System/currentTimeMillis)

        unsub (subscribe! reply-topic
                          (fn [msg] (deliver response (:content msg))))]
    (try
      (publish! topic {:request-id request-id
                       :content message
                       :reply-to reply-topic})

      (let [result (deref response timeout-ms ::timeout)]
        (if (= result ::timeout)
          (do
            (log/log! {:level :warn
                       :id ::ask-timeout
                       :msg "Request timed out"
                       :data {:topic topic :timeout-ms timeout-ms}})
            {:error :timeout :topic topic})
          {:success true
           :content result
           :duration-ms (- (System/currentTimeMillis) start-time)}))
      (finally
        (unsub)))))

;;; Introspection API

(defn list-topics
  "List all topics with subscriber counts."
  []
  (into {} (map (fn [[k v]] [k (count v)]) @subscribers)))

(defn get-recent-messages
  "Get recent messages from log.

   Arguments:
     n - Number of messages (default: 10)"
  ([] (get-recent-messages 10))
  ([n] (take-last n (seq @message-log))))

(defn clear-log!
  "Clear message log."
  []
  (reset! message-log (clojure.lang.PersistentQueue/EMPTY)))

;;; Team API

(defn create-team
  "Create a team with isolated namespace.

   Arguments:
     team-id    - Keyword identifying the team
     member-ids - Set of member identifiers"
  [team-id member-ids]
  {:id team-id
   :members (set member-ids)
   :prefix (str "team:" (name team-id) ":")
   :created-at (System/currentTimeMillis)})

(defn team-topic
  "Get namespaced topic for team."
  [team topic]
  (keyword (str (:prefix team) (name topic))))

(defn team-publish!
  "Publish to team-scoped topic."
  [team topic msg]
  (publish! (team-topic team topic) msg))

(defn team-subscribe!
  "Subscribe to team-scoped topic."
  [team topic handler-fn]
  (subscribe! (team-topic team topic) handler-fn))

(defn team-broadcast!
  "Broadcast to all team members."
  [team msg]
  (doseq [member (:members team)]
    (publish! (team-topic team member) msg)))
```

### Pros

| Benefit | Description |
|---------|-------------|
| **Very simple** | ~100 lines, easy to understand |
| **Fully inspectable** | `@subscribers`, `get-recent-messages` |
| **Easy debugging** | Message log, clear state |
| **Standard primitives** | atoms, futures, promises |
| **Good error handling** | try/catch in handlers |
| **bb compatible** | ✅ Fully works in Babashka |
| **Team support** | Built-in namespacing |

### Cons

| Drawback | Description |
|----------|-------------|
| No backpressure | Unbounded dispatch |
| Thread pool limits | `future` uses cached thread pool |
| Manual cleanup | Must call unsub function |
| No composition | No mult/merge/pipe |

### When to Use

- MVP / Phase 13F
- Team of 2-5 experts (low message volume)
- Debugging is priority
- Want simple, understandable code

---

## Option 4: Hybrid (Atoms + core.async)

### Overview

Use atoms for state/registry, core.async for message flow.

```clojure
(require '[clojure.core.async :as async])

;; State in atoms (inspectable)
(defonce teams (atom {}))
(defonce experts (atom {}))

;; Message flow via core.async (backpressure)
(defn create-bus []
  (let [ch (async/chan 100)]  ; bounded
    {:channel ch
     :pub (async/pub ch :topic)}))

(defonce main-bus (create-bus))

;; Subscribe returns a channel
(defn subscribe! [topic]
  (let [ch (async/chan 10)]
    (async/sub (:pub main-bus) topic ch)
    ch))

;; Publish (non-blocking)
(defn publish! [topic msg]
  (async/put! (:channel main-bus)
              {:topic topic :msg msg :ts (System/currentTimeMillis)}))

;; Request/response still uses promises (simpler)
(defn ask [topic message timeout-ms]
  (let [response (promise)
        reply-topic (keyword (str "reply:" (random-uuid)))
        reply-ch (subscribe! reply-topic)]
    ;; Background listener
    (async/go
      (when-let [msg (async/<! reply-ch)]
        (deliver response (:msg msg))))
    ;; Send request
    (publish! topic {:content message :reply-to reply-topic})
    ;; Wait
    (let [result (deref response timeout-ms :timeout)]
      (async/close! reply-ch)
      result)))
```

### Pros

| Benefit | Description |
|---------|-------------|
| Inspectable state | atoms for registry |
| Backpressure | bounded channels |
| Simple req/resp | promises, not channels |
| Best of both | atoms + core.async |
| bb compatible | ✅ Works in Babashka |

### Cons

| Drawback | Description |
|----------|-------------|
| Two paradigms | Mix of atoms and channels |
| More complex | Need to understand both |
| Channel debugging | Still hard to inspect channels |

### When to Use

- Need backpressure
- Already familiar with core.async
- Migrating from Option 3

---

## Comparison Matrix

| Feature | core.async | Agents | Atoms+Promises | Hybrid |
|---------|------------|--------|----------------|--------|
| **Simplicity** | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Debugging** | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Backpressure** | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ |
| **Performance** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **Error handling** | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Composition** | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ |
| **Babashka** | ✅ | ✅ | ✅ | ✅ |
| **Learning curve** | Steep | Gentle | Minimal | Moderate |

---

## Recommendation

### Phase 13F (MVP): Option 3 - Atoms + Promises

**Rationale:**

1. **Simplest to implement** - ~100 lines of code
2. **Easiest to debug** - Full state inspection
3. **Sufficient for 2-5 experts** - Not handling high volume
4. **Fast to iterate** - Simple code, easy to modify
5. **Clear migration path** - Can swap internals later

### Future (if needed): Option 4 - Hybrid

**Migrate to Hybrid when:**

- Message volume exceeds comfortable limits
- Backpressure becomes necessary
- Need complex routing (mult, merge)
- Team comfortable with core.async

### Migration Path

```
Phase 13F: Atoms + Promises (MVP)
    |
    | (if backpressure needed)
    v
Phase 14+: Hybrid (Atoms + core.async)
    |
    | (if high performance needed)
    v
Future: Full core.async
```

---

## Implementation Plan for Phase 13F

### Files to Create

```
modules/message-bus/
├── module.edn
├── src/message_bus/
│   ├── core.clj          # Main API (subscribe!, publish!, ask)
│   └── teams.clj         # Team management
└── test/message_bus/
    └── core_test.clj
```

### API Surface

```clojure
;; Core
(subscribe! topic handler-fn) ; -> unsubscribe-fn
(publish! topic msg)          ; -> nil
(ask topic msg :timeout-ms n) ; -> {:success true :content ...}

;; Introspection
(list-topics)                 ; -> {topic count, ...}
(get-recent-messages n)       ; -> [msg, ...]

;; Teams
(create-team team-id members) ; -> team
(team-publish! team topic msg)
(team-subscribe! team topic handler-fn)
(team-broadcast! team msg)
```

### Success Criteria

1. ✅ Experts can subscribe to topics
2. ✅ Messages delivered to all subscribers
3. ✅ Request/response with timeout works
4. ✅ Teams have isolated namespaces
5. ✅ State fully inspectable for debugging
6. ✅ All tests pass in Babashka

---

## References

### Clojure Documentation

- [core.async Pub/Sub Wiki](https://github.com/clojure/core.async/wiki/Pub-Sub)
- [add-watch - ClojureDocs](https://clojuredocs.org/clojure.core/add-watch)
- [Clojure Agents](https://clojure.org/reference/agents)

### Articles

- [Building an Event-Driven Architecture in Clojure](https://blog.janetacarr.com/building-an-event-driven-architecture-in-clojure-part-1/)
- [Comparison of Manifold and core.async](https://andreyor.st/posts/2023-01-09-comparison-of-manifold-and-clojurecoreasync/)
- [Manifold is your friend](https://monkey-projects.be/blog/posts/2024-04-03-manifold/)

### Libraries (JVM-only, NOT Babashka compatible)

- [Manifold](https://github.com/clj-commons/manifold) - Deferreds, streams, event bus
- [nijohando/event](https://github.com/nijohando/event) - core.async event bus
- [Pulsar](https://github.com/puniverse/pulsar) - Actor model (requires Quasar)
- [Meltdown](https://github.com/clojurewerkz/meltdown) - Reactor wrapper

### Babashka Resources

- [Babashka Book](https://book.babashka.org/)
- [Babashka Toolbox](https://babashka.org/toolbox/)

---

*Last Updated: 2025-11-25*
