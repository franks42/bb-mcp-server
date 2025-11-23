(ns bb-mcp-server.registry
    "Unified tool registry for MCP server.

  Provides thread-safe registration, lookup, and management of tools.
  Each tool consists of metadata (name, description, schema) and a handler function.

  Usage:
    (register! {:name \"hello\"
                :description \"Say hello\"
                :inputSchema {:type \"object\" :properties {:name {:type \"string\"}}}
                :handler (fn [{:keys [name]}] (str \"Hello, \" name \"!\"))})

    (get-tool \"hello\")      ; => full tool record
    (get-handler \"hello\")   ; => handler function
    (list-tools)              ; => seq of tool definitions (no handlers)"
    (:require [malli.core :as m]
              [malli.error :as me]
              [taoensso.trove :as log]))

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
     "Schema for a complete tool registration"
     [:map {:closed false}  ; Allow extra keys for extensibility
      [:name :string]
      [:description :string]
      [:inputSchema JsonSchema]
      [:handler fn?]
      [:module {:optional true} :string]
      [:transports {:optional true} [:set Transport]]])

(def ToolDefinition
     "Schema for tool definition (without handler) - returned by list-tools"
     [:map {:closed false}  ; Allow extra keys for extensibility
      [:name :string]
      [:description :string]
      [:inputSchema JsonSchema]
      [:module {:optional true} :string]
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
;; Registration API
;; -----------------------------------------------------------------------------

(defn register!
  "Register a tool in the registry.

  Args:
    tool-record - Map with :name, :description, :inputSchema, :handler

  Returns: The registered tool record

  Throws: ex-info if validation fails

  Note: Re-registering a tool with the same name replaces the previous one."
  [tool-record]
  (validate-tool-record! tool-record)
  (let [tool-name (:name tool-record)]
    (log/log! {:level :info
               :id ::register
               :msg "Registering tool"
               :data {:tool-name tool-name}})
    (swap! registry assoc tool-name tool-record)
    (log/log! {:level :info
               :id ::registered
               :msg "Tool registered"
               :data {:tool-name tool-name
                      :total-tools (count @registry)}})
    (notify-list-changed!)
    tool-record))

(defn unregister!
  "Remove a tool from the registry.

  Args:
    tool-name - String name of tool to remove

  Returns: The removed tool record, or nil if not found"
  [tool-name]
  (log/log! {:level :info
             :id ::unregister
             :msg "Unregistering tool"
             :data {:tool-name tool-name}})
  (let [removed (get @registry tool-name)]
    (swap! registry dissoc tool-name)
    (when removed
      (log/log! {:level :info
                 :id ::unregistered
                 :msg "Tool unregistered"
                 :data {:tool-name tool-name
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
  "Get a specific tool by module and name.

  Args:
    module-name - String name of module
    tool-name   - String name of tool

  Returns: Tool record if found and belongs to module, nil otherwise"
  [module-name tool-name]
  (when-let [tool (get-tool tool-name)]
    (when (= module-name (:module tool))
      tool)))

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
