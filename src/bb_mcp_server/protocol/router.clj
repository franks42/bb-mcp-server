(ns bb-mcp-server.protocol.router
    "JSON-RPC request router that dispatches methods to handlers."
    (:require [bb-mcp-server.protocol.message :as msg]
              [taoensso.trove :as log]))

;; Server state
(defonce ^:private server-state
         (atom {:initialized false
                :handlers {}}))

(defn register-handler!
  "Register a handler function for a JSON-RPC method.

  Args:
  - method-name: String name of the method (e.g., \"initialize\", \"tools/list\")
  - handler-fn: Function that takes a request map and returns a response map

  The handler function should:
  - Accept one argument: the parsed request map
  - Return either:
    - A success response map (use msg/create-response)
    - An error response map (use msg/create-error-response)"
  [method-name handler-fn]
  (log/log! {:level :info :msg "Registering handler" :data {:method method-name}})
  (swap! server-state assoc-in [:handlers method-name] handler-fn)
  (log/log! {:level :debug :msg "Handler registered successfully"
             :data {:method method-name
                    :total-handlers (count (:handlers @server-state))}}))

(defn route-request
  "Route a parsed JSON-RPC request to the appropriate handler.

  Args:
  - parsed-request: A map containing the parsed JSON-RPC request

  Returns: JSON-RPC response map (success or error)

  Logic:
  1. Check if method exists in handlers (error -32601 if not)
  2. Check if server is initialized (error -32003 if not, EXCEPT for initialize method)
  3. Call handler function with request
  4. Return handler result

  All responses include telemetry for method dispatch, duration, and success/failure."
  [parsed-request]
  (let [method (:method parsed-request)
        request-id (:id parsed-request)
        start-time (System/nanoTime)]

    (log/log! {:level :info :msg "Routing request"
               :data {:method method
                      :id request-id
                      :params (keys (:params parsed-request))}})

    (try
      ;; Get current state
     (let [state @server-state
           handler (get-in state [:handlers method])
           initialized? (:initialized state)]

       (cond
          ;; Check if method exists
         (nil? handler)
         (let [response (msg/create-error-response
                         request-id
                         (:method-not-found msg/error-codes)
                         "Method not found"
                         {:method method})]
           (log/log! {:level :warn :msg "Unknown method requested"
                      :data {:method method
                             :id request-id
                             :available-methods (keys (:handlers state))}})
           response)

          ;; Check if initialized (except for initialize method)
         (and (not initialized?)
              (not= method "initialize"))
         (let [response (msg/create-error-response
                         request-id
                         (:server-not-initialized msg/error-codes)
                         "Server not initialized"
                         {:method method
                          :hint "Call 'initialize' method first"})]
           (log/log! {:level :warn :msg "Method called before initialization"
                      :data {:method method
                             :id request-id}})
           response)

          ;; Call handler
         :else
         (let [response (handler parsed-request)
               duration-ms (/ (- (System/nanoTime) start-time) 1000000.0)
               success? (not (contains? response :error))]

           (if success?
             (log/log! {:level :info :msg "Request completed successfully"
                        :data {:method method
                               :id request-id
                               :duration-ms duration-ms}})
             (log/log! {:level :warn :msg "Request completed with error"
                        :data {:method method
                               :id request-id
                               :duration-ms duration-ms
                               :error-code (get-in response [:error :code])
                               :error-message (get-in response [:error :message])}}))

           response)))

     (catch Exception e
            (let [duration-ms (/ (- (System/nanoTime) start-time) 1000000.0)]
              (log/log! {:level :error :msg "Unhandled exception in router"
                         :error e
                         :data {:method method
                                :id request-id
                                :duration-ms duration-ms}})
              (msg/create-error-response
               request-id
               (:internal-error msg/error-codes)
               "Internal error"
               {:error (ex-message e)
                :type (str (type e))}))))))

(defn set-initialized!
  "Set the server initialized state.

  Args:
  - initialized? (boolean): New state"
  [initialized?]
  (log/log! {:level :info :msg "Setting server initialized state" :data {:initialized initialized?}})
  (swap! server-state assoc :initialized initialized?))

(defn initialized?
  "Check if the server has been initialized.

  Returns: Boolean indicating initialization state"
  []
  (:initialized @server-state))

(defn reset-state!
  "Reset server state to default (uninitialized, no handlers).

  Used for testing."
  []
  (log/log! {:level :warn :msg "Resetting server state"})
  (reset! server-state {:initialized false
                        :handlers {}}))

(defn get-handlers
  "Get the current handler registry.

  Returns: Map of {method-name handler-fn}

  Primarily used for testing and debugging."
  []
  (:handlers @server-state))
