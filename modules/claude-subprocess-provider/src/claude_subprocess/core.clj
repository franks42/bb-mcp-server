(ns claude-subprocess.core
    "Claude subprocess provider - implements ai-orchestrator protocol.

   This module implements the :claude-subprocess provider type,
   which spawns Claude CLI as a subprocess with JSONL stdio protocol."
    (:require [ai-orchestrator.protocol :as proto]
              [claude-subprocess.process :as process]
              [taoensso.trove :as log]))

;; =============================================================================
;; Module Lifecycle (defined at end after all functions)
;; =============================================================================
;; See bottom of file for module lifecycle implementation

;; =============================================================================
;; Message Dispatcher
;; =============================================================================

(defn- make-dispatcher
  "Create a message dispatcher for a Claude instance.

   The dispatcher handles incoming messages from the reader loop:
   - system/init: Store session ID
   - assistant: Accumulate response content
   - result: Complete pending request with accumulated response
   - error: Complete pending request with error"
  [instance]
  (fn [msg-type msg]
    (case msg-type
      :system
      (when (= "init" (:subtype msg))
        (when-let [sid (:session-id instance)]
                  (reset! sid (:session_id msg))
                  (log/log! {:level :info
                             :id    ::session-id-set
                             :msg   "Session ID set"
                             :data  {:name (:name instance) :session-id (:session_id msg)}})))

      :assistant
      (when-let [resp-buf (:response-buffer instance)]
                (swap! resp-buf conj msg))

      :result
      (let [current-id-atom (:current-request-id instance)
            request-id (when current-id-atom @current-id-atom)
            resp-buf (:response-buffer instance)
            responses (when resp-buf @resp-buf)
            ;; Extract content from assistant messages
            content (apply str (map #(get-in % [:message :content] "") responses))
            result {:content content
                    :cost_usd (:cost_usd msg 0)
                    :duration_ms (:duration_ms msg 0)
                    :subtype (:subtype msg)}]
        (when request-id
          ;; Deliver to promise
          (let [pending-reqs (:pending-requests instance)
                p (get @pending-reqs request-id)]
            (when p
              (deliver p result)
              (swap! pending-reqs dissoc request-id)
              (when current-id-atom
                (reset! current-id-atom nil))))))

      :error
      (let [current-id-atom (:current-request-id instance)
            request-id (when current-id-atom @current-id-atom)
            error-result {:error true
                          :message (get-in msg [:error :message] "Unknown error")}]
        (when request-id
          (let [pending-reqs (:pending-requests instance)
                p (get @pending-reqs request-id)]
            (when p
              (deliver p error-result)
              (swap! pending-reqs dissoc request-id)
              (when current-id-atom
                (reset! current-id-atom nil))))))

      :parse-error
      (log/log! {:level :warn
                 :id    ::parse-error-received
                 :msg   "Parse error in reader loop"
                 :data  msg})

      ;; Default: log unknown message types
      (log/log! {:level :debug
                 :id    ::unknown-message-type
                 :msg   "Unknown message type"
                 :data  {:type msg-type :msg msg}}))))

;; =============================================================================
;; Protocol Implementation
;; =============================================================================

(defmethod proto/create-instance :claude-subprocess
           [{:keys [name cmd model args] :as _opts}]
           (log/log! {:level :info
                      :id    ::creating-instance
                      :msg   "Creating Claude subprocess instance"
                      :data  {:name name :cmd cmd :model model}})

           (try
    ;; Build command vector with args
            (let [base-args (or args ["-p" "--verbose"
                                      "--input-format" "stream-json"
                                      "--output-format" "stream-json"
                                      "--permission-mode" "bypassPermissions"])
                  cmd-vec (vec (concat [cmd] base-args))
                  proc-map (process/spawn-process! cmd-vec)
          ;; Create instance structure
                  instance {:name name
                            :provider-type :claude-subprocess
                            :protocol :jsonl-stream
                            :model (or model "claude-3-5-haiku-20241022")
                            :capabilities #{:chat :tools :streaming}
                            :transport {:proc-map proc-map}
                            :session-id (atom nil)
                            :pending-requests (atom {})
                            :current-request-id (atom nil)
                            :response-buffer (atom [])
                            :created-at (System/currentTimeMillis)
                            :reader-future (atom nil)}
                  dispatcher (make-dispatcher instance)
                  reader-future (process/start-reader-loop! proc-map dispatcher)]

      ;; Store reader future
              (reset! (:reader-future instance) reader-future)

              (log/log! {:level :info
                         :id    ::instance-created
                         :msg   "Claude subprocess instance created"
                         :data  {:name name}})
              instance)

            (catch Exception e
                   (log/log! {:level :error
                              :id    ::create-failed
                              :msg   "Failed to create Claude subprocess instance"
                              :error e
                              :data  {:name name :cmd cmd}})
                   {:error (str "Failed to create instance: " (ex-message e))})))

(defmethod proto/send-message :claude-subprocess
           [instance message]
           (let [proc-map (get-in instance [:transport :proc-map])
                 request-id (:active-request-id instance)
                 msg {:type "user"
                      :message {:role "user" :content message}}]
             ;; Set current-request-id so dispatcher knows which request to complete
             (when-let [current-id-atom (:current-request-id instance)]
               (reset! current-id-atom request-id))
             (process/write-message! proc-map msg)))

(defmethod proto/stop-instance :claude-subprocess
           [instance]
           (log/log! {:level :info
                      :id    ::stopping-instance
                      :msg   "Stopping Claude subprocess instance"
                      :data  {:name (:name instance)}})

           (try
    ;; Kill process
            (when-let [proc-map (get-in instance [:transport :proc-map])]
                      (process/kill-process! proc-map))

    ;; Cancel reader future
            (when-let [f @(:reader-future instance)]
                      (future-cancel f))

            (log/log! {:level :info
                       :id    ::instance-stopped
                       :msg   "Claude subprocess instance stopped"
                       :data  {:name (:name instance)}})
            true

            (catch Exception e
                   (log/log! {:level :error
                              :id    ::stop-failed
                              :msg   "Error stopping Claude subprocess instance"
                              :error e
                              :data  {:name (:name instance)}})
                   false)))

(defmethod proto/get-capabilities :claude-subprocess
           [_provider-type]
           #{:chat :tools :streaming})

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn module-start
  "Start the Claude subprocess provider module."
  [_deps _config]
  (log/log! {:level :info
             :id    ::module-start
             :msg   "Claude subprocess provider module started"})
  {:status :started})

(defn module-stop
  "Stop the Claude subprocess provider module."
  [_instance]
  (log/log! {:level :info
             :id    ::module-stop
             :msg   "Claude subprocess provider module stopped"})
  nil)

(defn module-status
  "Get Claude subprocess provider module status."
  [_instance]
  {:status :ok})

(def module
     "Module lifecycle implementation for bb-mcp-server module system."
     {:start module-start
      :stop module-stop
      :status module-status})
