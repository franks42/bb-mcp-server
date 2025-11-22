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

(def ToolRecord
     "Schema for a complete tool registration"
     [:map {:closed true}
      [:name :string]
      [:description :string]
      [:inputSchema JsonSchema]
      [:handler fn?]])

(def ToolDefinition
     "Schema for tool definition (without handler) - returned by list-tools"
     [:map {:closed true}
      [:name :string]
      [:description :string]
      [:inputSchema JsonSchema]])

;; -----------------------------------------------------------------------------
;; Registry State
;; -----------------------------------------------------------------------------

(defonce ^{:doc "Global tool registry. Map of tool-name -> tool-record"}
 registry
         (atom {}))

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
                        :total-tools (count @registry)}}))
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

  Returns: Sequence of maps with :name, :description, :inputSchema

  Note: Handler functions are excluded for serialization safety."
  []
  (->> @registry
       vals
       (map #(select-keys % [:name :description :inputSchema]))
       (sort-by :name)))

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
