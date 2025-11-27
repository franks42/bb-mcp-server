(ns datalevin-pod.core
    "Datalevin pod module - database connection lifecycle and public API.

   This module provides:
   - Pod loading (huahaiy/datalevin)
   - Connection management (get-conn, close)
   - Public API for queries and transactions

   Access via local-eval or from other modules that depend on this."
    (:require [babashka.pods :as pods]
              [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private default-db-path "/var/db/datalevin/bb-mcp-server")
(def ^:private datalevin-version "0.9.27")

(defn- db-path
  "Get database path from env var or use default."
  []
  (or (System/getenv "BB_MCP_DATALEVIN_PATH")
      default-db-path))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private pod-loaded? (atom false))
(defonce ^:private conn (atom nil))
(defonce ^:private d-ns (atom nil))  ;; Holds the datalevin namespace after pod load

;; =============================================================================
;; Pod Loading
;; =============================================================================

(defn- load-pod!
  "Load the Datalevin pod if not already loaded."
  []
  (when-not @pod-loaded?
    (log/log! {:level :info
               :id ::loading-pod
               :msg "Loading Datalevin pod"
               :data {:version datalevin-version}})
    (pods/load-pod 'huahaiy/datalevin datalevin-version)
    (require '[pod.huahaiy.datalevin])
    (reset! d-ns (find-ns 'pod.huahaiy.datalevin))
    (reset! pod-loaded? true)
    (log/log! {:level :info
               :id ::pod-loaded
               :msg "Datalevin pod loaded successfully"
               :data {:version datalevin-version}})))

;; =============================================================================
;; Schema
;; =============================================================================

(def schema
     "Initial Datalevin schema for bb-mcp-server.

   Conversations, turns, and expert registry."
     {;; Conversations
      :conversation/id      {:db/unique :db.unique/identity}
      :conversation/created {:db/valueType :db.type/instant}
      :conversation/title   {}

   ;; Turns
      :turn/id              {:db/unique :db.unique/identity}
      :turn/conversation    {:db/valueType :db.type/ref}
      :turn/role            {}  ;; :user, :assistant, :system
      :turn/content         {}
      :turn/timestamp       {:db/valueType :db.type/instant}
      :turn/expert          {:db/valueType :db.type/ref}

   ;; Experts
      :expert/id            {:db/unique :db.unique/identity}
      :expert/name          {}
      :expert/provider      {}  ;; :anthropic-http, :openai-http, :claude-subprocess
      :expert/model         {}
      :expert/capabilities  {:db/cardinality :db.cardinality/many}})

;; =============================================================================
;; Connection Management
;; =============================================================================

(defn- d-fn
  "Get a function from the datalevin namespace."
  [fn-name]
  (when @d-ns
    (ns-resolve @d-ns (symbol fn-name))))

(defn init-conn!
  "Initialize database connection. Called by module start."
  []
  (load-pod!)
  (let [path (db-path)
        get-conn-fn (d-fn "get-conn")]
    (log/log! {:level :info
               :id ::init-conn
               :msg "Initializing Datalevin connection"
               :data {:path path}})
    (reset! conn (get-conn-fn path schema))
    (log/log! {:level :info
               :id ::conn-initialized
               :msg "Datalevin connection initialized"
               :data {:path path}})))

(defn close-conn!
  "Close database connection. Called by module stop."
  []
  (when @conn
    (let [close-fn (d-fn "close")]
      (log/log! {:level :info
                 :id ::closing-conn
                 :msg "Closing Datalevin connection"})
      (close-fn @conn)
      (reset! conn nil)
      (log/log! {:level :info
                 :id ::conn-closed
                 :msg "Datalevin connection closed"}))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn get-conn
  "Get the current database connection."
  []
  @conn)

(defn db
  "Get the current database value."
  []
  (when-let [db-fn (d-fn "db")]
            (db-fn @conn)))

(defn q
  "Execute a Datalog query.

   Example:
   (q '[:find ?name :where [?e :person/name ?name]])"
  [query & args]
  (when-let [q-fn (d-fn "q")]
            (apply q-fn query (db) args)))

(defn transact!
  "Execute a transaction.

   Example:
   (transact! [{:person/name \"Alice\"}])"
  [tx-data]
  (when-let [transact-fn (d-fn "transact!")]
            (log/log! {:level :debug
                       :id ::transact
                       :msg "Executing transaction"
                       :data {:tx-count (count tx-data)}})
            (transact-fn @conn tx-data)))

(defn pull
  "Pull an entity by ID or lookup ref.

   Example:
   (pull '[*] 123)
   (pull '[:person/name :person/age] [:person/id \"alice\"])"
  [pattern eid]
  (when-let [pull-fn (d-fn "pull")]
            (pull-fn (db) pattern eid)))

(defn get-schema
  "Get all user-defined attributes in the database."
  []
  (when-let [schema-fn (d-fn "schema")]
            (schema-fn @conn)))

(defn entity
  "Get an entity by ID."
  [eid]
  (when-let [entity-fn (d-fn "entity")]
            (entity-fn (db) eid)))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the datalevin-pod module. Loads pod and initializes connection."
  [_deps config]
  (log/log! {:level :info
             :id ::starting
             :msg "Starting datalevin-pod module"
             :data {:config config}})
  (init-conn!)
  (log/log! {:level :info
             :id ::started
             :msg "datalevin-pod module started"
             :data {:db-path (db-path)
                    :version datalevin-version}})
  {:db-path (db-path)
   :version datalevin-version})

(defn stop
  "Stop the datalevin-pod module. Closes connection."
  [_instance]
  (log/log! {:level :info
             :id ::stopping
             :msg "Stopping datalevin-pod module"})
  (close-conn!)
  nil)

(defn status
  "Get datalevin-pod module status."
  [_instance]
  {:status (if @conn :connected :disconnected)
   :pod-loaded @pod-loaded?
   :db-path (db-path)
   :version datalevin-version})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "Datalevin-pod module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
