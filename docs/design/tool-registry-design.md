# Tool Registry Design (Phase 2.1)

## Current State (Problems)

Two separate atoms:
- `tool-registry` (tools-list.clj) - stores tool definitions
- `tool-handlers` (tools-call.clj) - stores handler functions

**Issues:**
1. Must call two functions to register a tool (easy to forget one)
2. Registries can get out of sync
3. Tool lookup is O(n) via filter
4. No unregister functionality
5. No lookup-by-name function

---

## Proposed Design

### Unified Registry

Single atom storing complete tool records:

```clojure
;; Registry structure: {tool-name -> tool-record}
(defonce registry (atom {}))

;; Tool record structure
{:name        "tool-name"           ; String, unique identifier
 :description "What it does"        ; String, human-readable
 :inputSchema {...}                 ; JSON Schema map
 :handler     (fn [args] ...)}      ; Function: args -> result
```

### API

```clojure
(ns bb-mcp-server.registry)

;; Registration
(register! tool-record)       ; Register or replace tool
(unregister! tool-name)       ; Remove tool by name

;; Lookup
(get-tool tool-name)          ; Returns tool-record or nil
(get-handler tool-name)       ; Returns handler fn or nil
(list-tools)                  ; Returns seq of tool definitions (no handlers)
(tool-exists? tool-name)      ; Returns boolean

;; Bulk operations
(register-all! [tool-records])  ; Register multiple tools
(clear!)                        ; Remove all tools (testing)

;; Introspection
(tool-count)                    ; Number of registered tools
(tool-names)                    ; Set of registered names
```

### Malli Schema

```clojure
(def ToolRecord
  [:map {:closed true}
   [:name :string]
   [:description :string]
   [:inputSchema [:map]]
   [:handler fn?]])
```

### Thread Safety

- All operations use `swap!` or `reset!`
- `register!` is idempotent (re-registering replaces)
- Reads return immutable snapshots

### Example Usage

```clojure
(require '[bb-mcp-server.registry :as reg])

;; Register a tool
(reg/register!
 {:name "hello"
  :description "Say hello"
  :inputSchema {:type "object"
                :properties {:name {:type "string"}}
                :required ["name"]}
  :handler (fn [{:keys [name]}]
             (str "Hello, " name "!"))})

;; Use in handlers
(when-let [tool (reg/get-tool "hello")]
  ((:handler tool) {:name "World"}))

;; List for tools/list response
(reg/list-tools)
;; => [{:name "hello" :description "..." :inputSchema {...}}]
```

---

## Migration Plan

1. Create `bb-mcp-server.registry` namespace
2. Implement API with Malli validation
3. Update `tools-list.clj` to use `registry/list-tools`
4. Update `tools-call.clj` to use `registry/get-tool`
5. Move tool registration to central location (e.g., `tools/hello.clj`)
6. Delete old atoms from `tools-list.clj` and `tools-call.clj`

---

## File Structure

```
src/bb_mcp_server/
├── registry.clj           ; NEW - unified tool registry
├── tools/
│   └── hello.clj          ; NEW - hello tool definition
├── handlers/
│   ├── tools_list.clj     ; UPDATE - use registry
│   └── tools_call.clj     ; UPDATE - use registry
```

---

## Acceptance Criteria

- [ ] Single `register!` call registers both definition and handler
- [ ] `unregister!` removes tool completely
- [ ] `get-tool` is O(1) lookup
- [ ] Malli validates all tool records
- [ ] Thread-safe for concurrent access
- [ ] `list-tools` returns definitions without handlers
- [ ] All existing tests pass after migration

---

*Status: Design complete, ready for implementation (2.1.2)*
