(ns datalevin-mcp.core
    "Datalevin MCP tools module - schema, query, and transact tools.

   Provides MCP tools for AI interaction with Datalevin database:
   - schema: Get database schema
   - q: Execute Datalog queries
   - transact: Write data to database"
    (:require [bb-mcp-server.registry :as registry]
              [datalevin-pod.core :as dl]
              [clojure.edn :as edn]
              [taoensso.trove :as log]))

;; =============================================================================
;; EDN Parsing Helpers
;; =============================================================================

(defn- parse-edn
  "Safely parse EDN string with helpful error messages for AI.
   Returns {:ok value} or {:error message}."
  [s description]
  (try
   {:ok (edn/read-string s)}
   (catch Exception e
          {:error (str "Failed to parse " description ": " (.getMessage e)
                       "\nInput was: " (pr-str s)
                       "\nHint: Ensure valid EDN syntax. Common issues:"
                       "\n  - Use keywords :like/this not \"like/this\""
                       "\n  - Vectors use [], maps use {}"
                       "\n  - Strings must be double-quoted")})))

(defn- ensure-connected
  "Check if database is connected. Returns error map if not."
  []
  (if (dl/get-conn)
    nil
    {:error "Database not connected. Ensure datalevin-pod module is started."}))

;; =============================================================================
;; Tool Handlers
;; =============================================================================

(defn schema-handler
  "Handler for schema tool. Returns database schema."
  [_args]
  (log/log! {:level :debug
             :id ::schema-request
             :msg "Schema request"})
  (if-let [err (ensure-connected)]
          err
          (let [schema (dl/get-schema)]
            {:schema schema
             :attribute-count (count schema)})))

(defn q-handler
  "Handler for q (query) tool. Executes Datalog query."
  [{:keys [query args]}]
  (log/log! {:level :debug
             :id ::query-request
             :msg "Query request"
             :data {:query query}})
  (if-let [err (ensure-connected)]
          err
          (let [parsed (parse-edn query "query")]
            (if (:error parsed)
              parsed
              (try
               (let [q-form (:ok parsed)
                     q-args (when args
                              (let [args-parsed (parse-edn args "args")]
                                (if (:error args-parsed)
                                  (throw (ex-info "Args parse error" args-parsed))
                                  (:ok args-parsed))))
                     result (if q-args
                              (apply dl/q q-form q-args)
                              (dl/q q-form))]
                 {:result (vec result)
                  :count (count result)})
               (catch Exception e
                      {:error (str "Query execution failed: " (.getMessage e))
                       :hint "Check query syntax. Example: [:find ?name :where [?e :person/name ?name]]"}))))))

(defn transact-handler
  "Handler for transact tool. Executes database transaction."
  [{:keys [tx-data]}]
  (log/log! {:level :info
             :id ::transact-request
             :msg "Transact request"
             :data {:tx-data tx-data}})
  (if-let [err (ensure-connected)]
          err
          (let [parsed (parse-edn tx-data "tx-data")]
            (if (:error parsed)
              parsed
              (try
               (let [data (:ok parsed)
                     _ (when-not (vector? data)
                         (throw (ex-info "tx-data must be a vector" {:got (type data)})))
                     result (dl/transact! data)]
                 {:success true
                  :tx-data-count (count data)
                  :datoms-added (count (:tx-data result))})
               (catch Exception e
                      {:error (str "Transaction failed: " (.getMessage e))
                       :hint "tx-data must be a vector of maps. Example: [{:person/name \"Alice\"}]"}))))))

;; =============================================================================
;; Tool Definitions
;; =============================================================================

(def schema-tool
     "Schema tool definition."
     {:name "schema"
      :description "Get the database schema. Returns all defined attributes and their configuration."
      :module "datalevin-mcp"
      :inputSchema {:type "object"
                    :properties {}
                    :required []}
      :handler schema-handler})

(def q-tool
     "Query tool definition."
     {:name "q"
      :description "Execute a Datalog query against the database.

Example queries:
- Find all names: [:find ?name :where [?e :person/name ?name]]
- Find by attribute: [:find ?e ?name :where [?e :person/name ?name] [?e :person/email ?email]]
- With inputs: [:find ?name :in $ ?email :where [?e :person/email ?email] [?e :person/name ?name]]"
      :module "datalevin-mcp"
      :inputSchema {:type "object"
                    :properties {:query {:type "string"
                                         :description "Datalog query as EDN string, e.g. '[:find ?name :where [?e :person/name ?name]]'"}
                                 :args {:type "string"
                                        :description "Optional query arguments as EDN vector, e.g. '[\"alice@example.com\"]'"}}
                    :required ["query"]}
      :handler q-handler})

(def transact-tool
     "Transact tool definition."
     {:name "transact"
      :description "Execute a database transaction to add or update data.

Example transactions:
- Add entity: [{:person/name \"Alice\" :person/email \"alice@example.com\"}]
- Update by lookup: [{:person/id \"alice\" :person/name \"Alice Smith\"}]
- Multiple entities: [{:person/name \"Alice\"} {:person/name \"Bob\"}]"
      :module "datalevin-mcp"
      :inputSchema {:type "object"
                    :properties {:tx-data {:type "string"
                                           :description "Transaction data as EDN vector of maps, e.g. '[{:person/name \"Alice\"}]'"}}
                    :required ["tx-data"]}
      :handler transact-handler})

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the datalevin-mcp module. Registers MCP tools."
  [_deps config]
  (log/log! {:level :info
             :id ::starting
             :msg "Starting datalevin-mcp module"
             :data {:config config}})
  (registry/register! schema-tool)
  (registry/register! q-tool)
  (registry/register! transact-tool)
  (log/log! {:level :info
             :id ::started
             :msg "datalevin-mcp module started"
             :data {:tools ["schema" "q" "transact"]}})
  {:registered-tools ["schema" "q" "transact"]})

(defn stop
  "Stop the datalevin-mcp module. Unregisters MCP tools."
  [_instance]
  (log/log! {:level :info
             :id ::stopping
             :msg "Stopping datalevin-mcp module"})
  (registry/unregister! "datalevin-mcp.schema")
  (registry/unregister! "datalevin-mcp.q")
  (registry/unregister! "datalevin-mcp.transact")
  nil)

(defn status
  "Get datalevin-mcp module status."
  [_instance]
  {:status :ok
   :registered-tools ["schema" "q" "transact"]
   :db-connected (boolean (dl/get-conn))})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "Datalevin-mcp module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
