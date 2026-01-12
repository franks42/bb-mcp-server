# atom-sync Module

One-way atom synchronization from server (Babashka) to browser (Scittle) over WebSocket.

## Overview

The atom-sync module enables server-side Clojure atoms to be automatically synchronized to browser-side Reagent atoms. When you update an atom on the server, all connected browsers receive the update in real-time.

**Current:** One-way sync (server → browser). Server owns state, browsers observe.

**Use Case:** Reactive UIs where server state changes should be reflected in the browser without manual refresh.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         bb-mcp-server                               │
│                                                                     │
│  (def !state (atom {:count 0}))                                     │
│  (register-synced-atom! :my-state !state)                           │
│                                                                     │
│  (swap! !state assoc :count 1)  ; triggers sync                     │
│              │                                                      │
│              ▼                                                      │
│  [:sync/op {:key :my-state :seq 1 :op :assoc-in                     │
│             :path [:count] :value 1}]                               │
│              │                                                      │
│              ▼ broadcast via sente-lite WebSocket                   │
└──────────────┬──────────────────────────────────────────────────────┘
               │
               ▼
┌──────────────┴──────────────────────────────────────────────────────┐
│                         Browser (Scittle)                           │
│                                                                     │
│  (def state (get-synced-atom :my-state))  ; => Reagent atom         │
│                                                                     │
│  ;; Reagent component auto-updates on sync                          │
│  (defn counter []                                                   │
│    [:div "Count: " (:count @state)])                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Module Structure

```
modules/atom-sync/
├── src/atom_sync/
│   ├── core.clj        # Transport-independent sync logic
│   └── server.clj      # sente-lite integration (thin wrapper)
├── test/
│   ├── atom_sync/
│   │   ├── core_test.clj
│   │   └── server_test.clj
│   ├── scittle_test.html
│   └── run_tests.clj
├── module.edn
└── README.md
```

Browser-side code is embedded in `modules/sente-browser/src/sente_browser/bootstrap.clj`.

## Server-Side Usage

### Basic Registration

```clojure
(require '[atom-sync.core :as sync])

;; Create your atom
(def !app-state (atom {:users []
                       :selected nil
                       :loading? false}))

;; Register for sync - any changes auto-broadcast to browsers
(sync/register-synced-atom! :app-state !app-state)

;; Update normally - browsers receive the change
(swap! !app-state assoc :loading? true)
(swap! !app-state assoc :users [{:id 1 :name "Alice"}])
(swap! !app-state assoc :loading? false)
```

### Unregistering

```clojure
;; Remove from sync (removes watcher, clears from registry)
(sync/unregister-synced-atom! :app-state)
```

### Debugging

```clojure
;; Check sync status on server
(sync/get-sync-status)
;; => {:app-state 5}  ; key -> current seq number

;; Get the atom ref
(sync/get-synced-atom :app-state)
;; => #object[clojure.lang.Atom ...]
```

## Browser-Side Usage

The browser-side API is available automatically in Scittle via `bootstrap.clj`.

### Getting Synced Atoms

```clojure
;; Get (or auto-create) a synced Reagent atom
(def state (get-synced-atom :app-state))

;; Use in component - auto re-renders on sync
(defn user-list []
  (let [{:keys [users loading?]} @state]
    [:div
     (if loading?
       [:p "Loading..."]
       [:ul
        (for [{:keys [id name]} users]
          ^{:key id}
          [:li name])])]))
```

### Debugging Sync Status

```clojure
;; Check browser-side sync status
(get-sync-status)
;; => {:atoms [:app-state]
;;     :state {:app-state {:seq 5}}}
```

## Protocol

Messages are sent as EDN over WebSocket:

```clojure
;; Server → Browser: sync operation
[:sync/op {:key   :app-state        ; atom identifier
           :seq   42                 ; monotonic sequence number
           :op    :assoc-in          ; :assoc-in or :dissoc-in
           :path  [:users 0 :name]   ; path into atom value ([] = full replace)
           :value "Bob"}]            ; new value

;; Browser → Server: request resync (on gap detection)
[:sync/resync-request {:key :app-state}]

;; Server → Browser: resync response
[:sync/resync-response {:key :app-state
                        :ops [[:sync/op {...}]]}]

;; Browser → Server: heartbeat check
[:sync/heartbeat {:key :app-state :seq 42}]

;; Server → Browser: heartbeat response
[:sync/heartbeat-response {:status :in-sync  ; or :behind
                           :server-seq 42
                           :resync-ops nil}]  ; ops if behind
```

## Sequence Numbers & Reliability

Each synced atom has a monotonic sequence number. When the browser receives an update:

| Situation | seq vs expected | Action |
|-----------|-----------------|--------|
| **Normal** | seq = expected | Apply update, increment local seq |
| **Stale** | seq < expected | Ignore (duplicate/old message) |
| **Gap** | seq > expected | Apply update, request full resync |

Gap detection ensures browsers recover from missed messages (e.g., temporary disconnect).

## Example: Code Browser State

```clojure
;; Server-side
(def !code-browser (atom {:namespaces []
                          :selected-ns nil
                          :symbols []
                          :source nil}))

(sync/register-synced-atom! :code-browser !code-browser)

;; On user action (e.g., selecting namespace)
(defn select-namespace [ns-name]
  (let [symbols (clojure-lsp/get-symbols ns-name)]
    (swap! !code-browser assoc
           :selected-ns ns-name
           :symbols symbols
           :source nil)))

;; Browser-side component
(defn code-browser []
  (let [{:keys [namespaces selected-ns symbols source]}
        @(get-synced-atom :code-browser)]
    [:div.code-browser
     [:div.ns-list
      (for [ns namespaces]
        ^{:key ns}
        [:div {:class (when (= ns selected-ns) "selected")
               :on-click #(send! [:code-browser/select-ns {:ns ns}])}
         ns])]
     [:div.symbols
      (for [{:keys [name type]} symbols]
        ^{:key name}
        [:div.symbol name " (" type ")"])]
     [:pre.source source]]))
```

## Testing

```bash
# Run all atom-sync tests
bb test:atom-sync

# Tests include:
# - deep-diff->ops (diffing logic)
# - apply-sync-op (applying ops to atoms)
# - register/unregister lifecycle
# - seq validation (applied/stale/gap)
# - heartbeat handling
# - subscriber notifications
```

**Coverage:** 29 tests, 130 assertions

## Integration

The atom-sync module is wired into `sente-browser.server`:

1. **Server startup:** `atom-sync.server/init!` called with broadcast/send functions
2. **Browser connects:** `atom-sync.server/on-browser-connected!` pushes all atoms
3. **Atom changes:** Watchers trigger sync ops via subscriber callbacks
4. **Browser messages:** `dispatch-event` handles resync/heartbeat requests

See `modules/sente-browser/src/sente_browser/server.clj` for integration points.

## Design Document

For detailed architecture decisions and future plans, see:
`docs/design/atom-sync-design.md`
