# Nexus Pattern Reference

> Functional action-dispatch pattern for event handling.
> Based on [cjohansen/nexus](https://github.com/cjohansen/nexus) (~500 LOC, zero deps, .cljc).
> Cloned locally at `../nexus` for reference.

---

## The Core Idea

Separate **what should happen** (actions = data) from **how it happens** (effects = I/O) via a **pure logic layer** (action handlers).

```
User event
  -> Actions (data vectors describing intent)
  -> Expand (pure handlers transform actions -> more actions)
  -> Effects (impure handlers execute I/O)
  -> Return {:results [...] :errors [...]}
```

**Why this matters:** Action handlers are pure functions. They take immutable state and return data. No mocking needed, no test fixtures, no server running. The pain of testing side-effect-heavy event handlers disappears.

---

## Key Abstractions

### Actions = Data Vectors

```clj
[:task/set-status "task-1" :active]
[:code-browser/select-ns widget-id "my.namespace"]
[:effects/save [:path :to :thing] value]
```

Actions are just vectors. First element is a keyword (the action type), rest are arguments. They're printable, serializable, comparable, loggable.

### Action Handlers = Pure Functions

```clj
;; (fn [state & args] -> [[actions]])
(fn [state widget-id ns-name]
  [[:effects/update-widget widget-id {:current-ns ns-name}]
   (when (:auto-fetch? (get-in state [:widgets widget-id]))
     [:effects/fetch-code widget-id ns-name])])
```

Action handlers receive an **immutable state snapshot** and return new actions. They never touch atoms, never call I/O, never mutate anything. This is where business logic lives.

### Effect Handlers = I/O Functions

```clj
;; (fn [ctx system & args] -> side-effect)
(fn [{:keys [dispatch]} system widget-id updates]
  (swap! (:widgets system) update widget-id merge updates))
```

Effect handlers do the actual work: swap atoms, send WebSocket messages, call APIs. They receive a `ctx` map that includes a `dispatch` function for triggering new actions from async callbacks.

### Placeholders = Late-Bound Values

```clj
;; In action definition (resolved at dispatch time)
[:form/submit [:event.target/form-data]]

;; Nested composition via postwalk
[:validate [:fmt/number [:event.target/value]]]
```

Values only available at dispatch time (DOM events, timestamps, etc.) are represented as placeholder vectors and resolved before expansion.

### Interceptors = Cross-Cutting Concerns

Six hook points: `before-dispatch`, `after-dispatch`, `before-action`, `after-action`, `before-effect`, `after-effect`. Queue for before-phases (forward), stack for after-phases (reversed). Like Ring middleware but for event dispatch.

---

## How Our Codebase Already Uses This

### handlers.clj — Already Nexus-Like

Our `dispatch-event` in `code-browser.handlers` already follows this pattern:

```clj
(defn dispatch-event [event-id data]
  (case event-id
    :code-browser-v2/load-projects
    [:code-browser-v2/projects-loaded (handle-load-projects!)]

    :code-browser-v2/fetch
    (let [result (handle-fetch {:uri (:uri data) :property (:property data)})]
      [:code-browser-v2/fetch-response (assoc result :widget-id (:widget-id data))])
    ...))
```

This returns `[event-id response-data]` — it's data, not side effects. The caller (`server.clj`) decides what to do with the response. That separation is the core Nexus idea.

### Where We're Less Clean

- `core.clj` file watcher directly calls `broadcast-to-browsers!` (effect mixed with logic)
- `server.clj` on-browser-message handler does routing + state mutation in one function
- Browser `code_browser_v2.cljs` widget handlers mix DOM manipulation with state updates

These work fine today. Don't refactor them just for pattern purity.

---

## Guidelines for New Code

### When Writing New Event Handlers

**Prefer this (action-first):**

```clj
;; Pure handler — returns actions as data
(defn handle-widget-resize [state widget-id new-size]
  (let [widget (get-in state [:widgets widget-id])
        constrained (clamp-size new-size (:min-size widget) (:max-size widget))]
    [[:effects/update-widget widget-id {:size constrained}]
     (when (:auto-save? widget)
       [:effects/save-layout widget-id])]))

;; Effect — does the I/O
(defn effect-update-widget [_ctx system widget-id updates]
  (swap! (:widgets system) update widget-id merge updates))
```

**Over this (direct mutation):**

```clj
;; Mixed — logic + I/O tangled
(defn handle-widget-resize! [widgets-atom widget-id new-size]
  (let [widget (get @widgets-atom widget-id)
        constrained (clamp-size new-size (:min-size widget) (:max-size widget))]
    (swap! widgets-atom update widget-id assoc :size constrained)
    (when (:auto-save? widget)
      (save-layout! widget-id))))
```

### Testing Pure Handlers

```clj
(deftest handle-widget-resize-test
  (let [state {:widgets {"w1" {:size [100 100] :min-size [50 50] :max-size [500 500]}}}]

    (testing "constrains to max size"
      (is (= [[:effects/update-widget "w1" {:size [500 500]}]]
             (handle-widget-resize state "w1" [999 999]))))

    (testing "returns save action when auto-save enabled"
      (let [state (assoc-in state [:widgets "w1" :auto-save?] true)]
        (is (= 2 (count (handle-widget-resize state "w1" [200 200]))))))))
```

No atoms, no mocking, no server. Just data in, data out.

### When NOT to Use This Pattern

- **Simple CRUD** — if the handler is just `(swap! atom assoc k v)`, don't wrap it
- **Performance-critical paths** — extra indirection has (tiny) overhead
- **Existing working code** — don't retrofit, only apply to new code
- **One-off scripts** — the pattern shines in long-lived systems, not scripts

---

## Nexus Implementation Details Worth Knowing

### Error Handling

Nexus catches all errors and collects them as data — never re-throws. This means partial success: if effect A fails, effect B still runs. Optional `fail-fast` strategy stops on first error.

```clj
;; Return value from dispatch
{:results [{:effect [:effects/save ...] :result :ok}]
 :errors  [{:phase :effect :effect [:effects/broken ...] :err #error{...}}]}
```

### Batching

Mark effect handlers with `^:nexus/batch` metadata. Instead of being called once per action, they receive all matching actions at once:

```clj
^:nexus/batch
(fn [ctx system path-value-pairs]
  ;; Called once with [[path1 val1] [path2 val2] ...]
  (swap! (:store system) (fn [s] (reduce (fn [s [p v]] (assoc-in s p v)) s path-value-pairs))))
```

Useful for batching DOM updates, database writes, or WebSocket messages.

### Effects Can Dispatch

```clj
(fn [{:keys [dispatch]} system url {:keys [on-success on-failure]}]
  (-> (fetch url)
      (.then (fn [response] (dispatch [on-success {:data response}])))
      (.catch (fn [error] (dispatch [on-failure {:error error}])))))
```

The `dispatch` in ctx creates a new dispatch cycle — effects from async callbacks naturally chain into more actions.

---

## Reference

- **Source:** `../nexus/src/nexus/core.cljc` (186 lines — the entire dispatch engine)
- **Registry:** `../nexus/src/nexus/registry.cljc` (32 lines — optional global registry)
- **Tests:** `../nexus/test/` (500+ assertions, excellent examples)
- **Example app:** `../nexus/dev/counter/core.cljc` (counter with undo/redo)

---

*Added: 2026-02-08 — Reviewed as reference pattern for bb-mcp-server event handling.*
