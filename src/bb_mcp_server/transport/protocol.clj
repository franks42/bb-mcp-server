(ns bb-mcp-server.transport.protocol
    "Transport protocol for MCP server.

  Defines the interface that all transports must implement.
  Transports handle I/O framing while sharing the same request processor.")

;; Note: Babashka doesn't support defprotocol, so we use a map-based approach
;; where each transport is a map with :start!, :stop!, and :running? functions.

(def transport-schema
     "Schema for transport implementations.

  Each transport is a map with:
  - :start!   (fn [config] -> transport) - Start the transport
  - :stop!    (fn [transport] -> nil) - Stop the transport
  - :running? (fn [transport] -> boolean) - Check if running
  - :type     keyword - Transport type identifier"
     {:type :keyword
      :start! :fn
      :stop! :fn
      :running? :fn})

(defn valid-transport?
  "Check if a value is a valid transport implementation."
  [transport]
  (and (map? transport)
       (keyword? (:type transport))
       (fn? (:start! transport))
       (fn? (:stop! transport))
       (fn? (:running? transport))))

(defn start-transport!
  "Start a transport with the given config.

  Args:
    transport - Transport implementation map
    config    - Configuration map (transport-specific)

  Returns: Started transport (may contain state)"
  [transport config]
  ((:start! transport) config))

(defn stop-transport!
  "Stop a running transport.

  Args:
    transport - Running transport

  Returns: nil"
  [transport]
  ((:stop! transport)))

(defn transport-running?
  "Check if a transport is currently running.

  Args:
    transport - Transport to check

  Returns: boolean"
  [transport]
  ((:running? transport)))
