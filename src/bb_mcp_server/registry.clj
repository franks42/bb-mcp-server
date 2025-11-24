(ns bb-mcp-server.registry
    "Unified tool registry for MCP server.

  Provides thread-safe registration, lookup, and management of tools.
  Each tool consists of metadata (name, description, schema) and a handler function.

  Tool Naming:
    Tools are identified by a fully-qualified name: 'module.name'
    This prevents collisions when multiple modules define tools with the same short name.
    The :name field is the MCP-visible identifier (module.shortname).
    The :short-name field is preserved for REST API routes (/api/modules/:module/tools/:short-name).

  Usage:
    (register! {:name \"add\"                    ; short name
                :module \"math\"                 ; required!
                :description \"Add numbers\"
                :inputSchema {:type \"object\" :properties {:a {:type \"number\"}}}
                :handler (fn [{:keys [a b]}] (+ a b))})

    ;; Tool is registered as 'math.add'
    (get-tool \"math.add\")      ; => full tool record
    (get-handler \"math.add\")   ; => handler function
    (list-tools)                 ; => seq of tool definitions (no handlers)"
    (:require [clojure.set :as set]
              [clojure.string :as str]
              [malli.core :as m]
              [malli.error :as me]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Tool Naming Configuration
;; -----------------------------------------------------------------------------

(def module-tool-separator
     "Separator character used between module and tool names.

  This character is used to construct fully-qualified tool names like 'module.tool'.
  It is reserved and cannot appear in module names or tool short names.

  This value is exposed in the MCP initialize response for client introspection."
     ".")

;; -----------------------------------------------------------------------------
;; Schema Definitions
;; -----------------------------------------------------------------------------

(def JsonSchema
     "Schema for JSON Schema objects (simplified validation)"
     [:map
      [:type :string]
      [:properties {:optional true} [:map-of :keyword :map]]
      [:required {:optional true} [:vector :string]]])

;; Default transports when not specified - all transports enabled
(def default-transports
     "Default set of transports for tools that don't specify."
     #{:rest :mcp-http :mcp-stdio})

(def Transport
     "Valid transport identifiers"
     [:enum :rest :mcp-http :mcp-stdio])

(def ToolRecord
     "Schema for a complete tool registration.

  Required fields:
    :name        - Short tool name (e.g., 'add')
    :module      - Module name (required! e.g., 'math')
    :description - Human-readable description
    :inputSchema - JSON Schema for parameters
    :handler     - Function to call

  The full MCP tool name will be 'module.name' (e.g., 'math.add')"
     [:map {:closed false}  ; Allow extra keys for extensibility
      [:name :string]
      [:module :string]  ; Now required!
      [:description :string]
      [:inputSchema JsonSchema]
      [:handler fn?]
      [:transports {:optional true} [:set Transport]]])

(def ToolDefinition
     "Schema for tool definition (without handler) - returned by list-tools"
     [:map {:closed false}  ; Allow extra keys for extensibility
      [:name :string]
      [:module :string]  ; Now required!
      [:short-name {:optional true} :string]  ; Original name before prefixing
      [:description :string]
      [:inputSchema JsonSchema]
      [:transports {:optional true} [:set Transport]]])

;; -----------------------------------------------------------------------------
;; Registry State
;; -----------------------------------------------------------------------------

(defonce ^{:doc "Global tool registry. Map of tool-name -> tool-record"}
 registry
         (atom {}))

(defonce ^{:doc "Callback function called when tool list changes.
                Set via set-list-changed-callback! for notification support."}
 list-changed-callback
         (atom nil))

;; -----------------------------------------------------------------------------
;; List Changed Notification API
;; -----------------------------------------------------------------------------

(defn set-list-changed-callback!
  "Set callback to be called when tool list changes.

  The callback receives no arguments - it should trigger a
  'notifications/tools/list_changed' notification to clients.

  Args:
    callback-fn - Zero-arg function to call on changes, or nil to disable

  Example:
    (set-list-changed-callback!
      #(broadcast-notification! \"notifications/tools/list_changed\" {}))"
  [callback-fn]
  (reset! list-changed-callback callback-fn))

(defn- notify-list-changed!
  "Internal: trigger list changed callback if set."
  []
  (when-let [callback @list-changed-callback]
            (try
             (callback)
             (log/log! {:level :info
                        :id ::list-changed-notified
                        :msg "Tool list changed notification sent"})
             (catch Exception e
                    (log/log! {:level :warn
                               :id ::list-changed-error
                               :msg "Error sending list changed notification"
                               :data {:error (.getMessage e)}})))))

;; -----------------------------------------------------------------------------
;; Validation
;; -----------------------------------------------------------------------------

(defn- validate-tool-record!
  "Validate tool record against schema. Throws on failure."
  [tool-record]
  (when-not (m/validate ToolRecord tool-record)
    (let [explanation (m/explain ToolRecord tool-record)
          humanized (me/humanize explanation)]
      (log/log! {:level :error
                 :id ::validation-failed
                 :msg "Tool record validation failed"
                 :data {:tool-name (:name tool-record)
                        :errors humanized}})
      (throw (ex-info "Invalid tool record"
                      {:type :validation-error
                       :tool-name (:name tool-record)
                       :errors humanized})))))

;; -----------------------------------------------------------------------------
;; Tool Name Helpers
;; -----------------------------------------------------------------------------

(defn make-full-name
  "Create fully-qualified tool name from module and short name.

  Args:
    module-name - Module name string
    short-name  - Short tool name string

  Returns: String 'module<separator>shortname' using module-tool-separator"
  [module-name short-name]
  (str module-name module-tool-separator short-name))

(defn parse-full-name
  "Parse a fully-qualified tool name into module and short name.

  Args:
    full-name - String like 'module<separator>toolname'

  Returns: Map with :module and :short-name, or nil if invalid format"
  [full-name]
  (let [sep-idx (str/index-of full-name module-tool-separator)]
    (when (and sep-idx (pos? sep-idx) (< sep-idx (dec (count full-name))))
      {:module (subs full-name 0 sep-idx)
       :short-name (subs full-name (inc sep-idx))})))

;; -----------------------------------------------------------------------------
;; Registration API
;; -----------------------------------------------------------------------------

(defn register!
  "Register a tool in the registry.

  Args:
    tool-record - Map with :name, :module, :description, :inputSchema, :handler
                  :module is REQUIRED

  Returns: The registered tool record (with :name updated to full name)

  Throws: ex-info if:
    - validation fails
    - :module is missing
    - module name or tool name contains a dot (reserved separator)
    - tool with same full name already exists

  The tool's :name will be transformed to 'module.name' format.
  The original short name is preserved in :short-name."
  [tool-record]
  ;; Validate module is present
  (when-not (:module tool-record)
    (throw (ex-info "Tool registration requires :module field"
                    {:type :missing-module
                     :tool-name (:name tool-record)})))

  ;; Validate separator char not in module name or tool name (reserved)
  (let [module-name (:module tool-record)
        tool-name (:name tool-record)]
    (when (and module-name (str/includes? module-name module-tool-separator))
      (throw (ex-info (str "Module name cannot contain '" module-tool-separator "' (reserved separator)")
                      {:type :invalid-module-name
                       :module module-name
                       :separator module-tool-separator
                       :reason "separator-in-name"})))
    (when (and tool-name (str/includes? tool-name module-tool-separator))
      (throw (ex-info (str "Tool name cannot contain '" module-tool-separator "' (reserved separator)")
                      {:type :invalid-tool-name
                       :tool-name tool-name
                       :separator module-tool-separator
                       :reason "separator-in-name"}))))

  ;; Create full name
  (let [short-name (:name tool-record)
        module-name (:module tool-record)
        full-name (make-full-name module-name short-name)
        ;; Transform record: :name becomes full name, preserve :short-name
        transformed (assoc tool-record
                           :name full-name
                           :short-name short-name)]

    ;; Validate transformed record
    (validate-tool-record! transformed)

    ;; Check for collision
    (when (contains? @registry full-name)
      (let [existing (get @registry full-name)]
        (log/log! {:level :error
                   :id ::collision
                   :msg "Tool name collision detected"
                   :data {:full-name full-name
                          :existing-module (:module existing)
                          :new-module module-name}})
        (throw (ex-info (str "Tool '" full-name "' already registered")
                        {:type :name-collision
                         :full-name full-name
                         :existing-module (:module existing)
                         :new-module module-name}))))

    ;; Register
    (log/log! {:level :info
               :id ::register
               :msg "Registering tool"
               :data {:full-name full-name
                      :module module-name
                      :short-name short-name}})
    (swap! registry assoc full-name transformed)
    (log/log! {:level :info
               :id ::registered
               :msg "Tool registered"
               :data {:full-name full-name
                      :total-tools (count @registry)}})
    (notify-list-changed!)
    transformed))

(defn unregister!
  "Remove a tool from the registry.

  Args:
    full-name - Full tool name (module.shortname) to remove

  Returns: The removed tool record, or nil if not found"
  [full-name]
  (log/log! {:level :info
             :id ::unregister
             :msg "Unregistering tool"
             :data {:full-name full-name}})
  (let [removed (get @registry full-name)]
    (swap! registry dissoc full-name)
    (when removed
      (log/log! {:level :info
                 :id ::unregistered
                 :msg "Tool unregistered"
                 :data {:full-name full-name
                        :total-tools (count @registry)}})
      (notify-list-changed!))
    removed))

(defn register-all!
  "Register multiple tools at once.

  Args:
    tool-records - Sequence of tool record maps

  Returns: Number of tools registered

  Note: Stops on first validation failure."
  [tool-records]
  (log/log! {:level :info
             :id ::register-all
             :msg "Registering multiple tools"
             :data {:count (count tool-records)}})
  (doseq [tool tool-records]
         (register! tool))
  (count tool-records))

(defn clear!
  "Remove all tools from the registry.

  Primarily used for testing to ensure clean state.

  Returns: Number of tools removed"
  []
  (let [count-before (count @registry)]
    (log/log! {:level :info
               :id ::clear
               :msg "Clearing tool registry"
               :data {:tools-removed count-before}})
    (reset! registry {})
    count-before))

;; -----------------------------------------------------------------------------
;; Lookup API
;; -----------------------------------------------------------------------------

(defn get-tool
  "Look up a tool by name.

  Args:
    tool-name - String name of tool

  Returns: Full tool record map, or nil if not found"
  [tool-name]
  (get @registry tool-name))

(defn get-handler
  "Look up a tool's handler function by name.

  Args:
    tool-name - String name of tool

  Returns: Handler function, or nil if tool not found"
  [tool-name]
  (when-let [tool (get-tool tool-name)]
            (:handler tool)))

(defn tool-exists?
  "Check if a tool is registered.

  Args:
    tool-name - String name of tool

  Returns: Boolean"
  [tool-name]
  (contains? @registry tool-name))

(defn list-tools
  "Get all registered tool definitions (without handlers).

  Returns: Sequence of maps with :name, :description, :inputSchema, :module, :transports

  Note: Handler functions are excluded for serialization safety."
  []
  (->> @registry
       vals
       (map #(select-keys % [:name :description :inputSchema :module :transports]))
       (sort-by :name)))

(defn get-tool-transports
  "Get transports for a tool, defaulting to all if not specified.

  Args:
    tool - Tool record map

  Returns: Set of transport keywords"
  [tool]
  (or (:transports tool) default-transports))

(defn list-tools-for-transport
  "Get tool definitions available for a specific transport.

  Args:
    transport - Transport keyword (:rest, :mcp-http, or :mcp-stdio)

  Returns: Sequence of tool definitions that support the given transport

  Example:
    (list-tools-for-transport :rest)
    ;; => tools that can be called via REST API"
  [transport]
  (->> @registry
       vals
       (filter #(contains? (get-tool-transports %) transport))
       (map #(select-keys % [:name :description :inputSchema :module :transports]))
       (sort-by :name)))

(defn tool-supports-transport?
  "Check if a tool supports a specific transport.

  Args:
    tool-name - String name of tool
    transport - Transport keyword

  Returns: Boolean"
  [tool-name transport]
  (when-let [tool (get-tool tool-name)]
            (contains? (get-tool-transports tool) transport)))

(defn list-modules
  "Get list of unique module names from registered tools.

  Returns: Sorted sequence of module name strings (nil modules excluded)"
  []
  (->> @registry
       vals
       (map :module)
       (remove nil?)
       distinct
       sort))

(defn list-tools-for-module
  "Get tool definitions for a specific module.

  Args:
    module-name - String name of module

  Returns: Sequence of tool definitions belonging to the module"
  [module-name]
  (->> @registry
       vals
       (filter #(= module-name (:module %)))
       (map #(select-keys % [:name :description :inputSchema :module :transports]))
       (sort-by :name)))

(defn list-tools-for-module-and-transport
  "Get tool definitions for a module that support a specific transport.

  Args:
    module-name - String name of module
    transport   - Transport keyword

  Returns: Sequence of tool definitions"
  [module-name transport]
  (->> @registry
       vals
       (filter #(and (= module-name (:module %))
                     (contains? (get-tool-transports %) transport)))
       (map #(select-keys % [:name :description :inputSchema :module :transports]))
       (sort-by :name)))

(defn get-tool-in-module
  "Get a specific tool by module and short name.

  Args:
    module-name - String name of module
    short-name  - String short name of tool (not full name)

  Returns: Tool record if found, nil otherwise"
  [module-name short-name]
  (let [full-name (make-full-name module-name short-name)]
    (get-tool full-name)))

;; -----------------------------------------------------------------------------
;; Transport Validation API
;; -----------------------------------------------------------------------------

(defn validate-transports
  "Validate that registered tools have compatible transports available.

  Args:
    available-transports - Set of transport keywords this server provides
                          (e.g., #{:mcp-http :rest} for Streamable HTTP server)

  Returns:
    Map with:
      :valid?   - true if all tools have at least one available transport
      :warnings - Seq of warning maps for tools with no compatible transports
      :summary  - Human-readable summary string

  This is a startup validation helper - tools can still function if a transport
  becomes available later. The function logs warnings but does not throw."
  [available-transports]
  (let [all-tools (list-tools)
        warnings (->> all-tools
                      (map (fn [tool]
                             (let [tool-transports (get-tool-transports tool)
                                   compatible (set/intersection tool-transports available-transports)]
                               (when (empty? compatible)
                                 {:tool-name (:name tool)
                                  :module (:module tool)
                                  :tool-transports tool-transports
                                  :available-transports available-transports
                                  :message (str "Tool '" (:name tool) "' requires " tool-transports
                                                " but server provides " available-transports)}))))
                      (remove nil?))]
    (doseq [warn warnings]
           (log/log! {:level :warn
                      :id ::transport-mismatch
                      :msg "Tool has no compatible transport"
                      :data warn}))
    {:valid? (empty? warnings)
     :warnings (vec warnings)
     :summary (if (empty? warnings)
                (str "All " (count all-tools) " tools have compatible transports")
                (str (count warnings) " of " (count all-tools) " tools have no compatible transport"))}))

;; -----------------------------------------------------------------------------
;; Introspection API
;; -----------------------------------------------------------------------------

(defn tool-count
  "Get number of registered tools.

  Returns: Integer count"
  []
  (count @registry))

(defn tool-names
  "Get set of all registered tool names.

  Returns: Set of strings"
  []
  (set (keys @registry)))
