(ns calculate.core
    "Calculate module - mathematical expression evaluation with SCI sandbox."
    (:require [bb-mcp-server.registry :as registry]
              [calculate.engine :as calc]
              [calculate.analytics :as analytics]
              [taoensso.trove :as log]))

;; =============================================================================
;; Base64 Utilities
;; =============================================================================

(defn- decode-base64
  "Decode base64 string to UTF-8 text."
  [b64-str]
  (String. (.decode (java.util.Base64/getDecoder) b64-str) "UTF-8"))

(defn- encode-base64
  "Encode UTF-8 text to base64 string."
  [text]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes text "UTF-8")))

;; =============================================================================
;; Tool Handler
;; =============================================================================

(defn calculate-handler
  "Evaluate mathematical expressions using Clojure prefix notation.
   Returns result in EDN format with type information.
   Supports input-base64 flag to avoid JSON escaping issues."
  [{:keys [expr input-base64 output-base64]}]
  (log/log! {:level :debug
             :id ::calculate-request
             :msg "Calculate request"
             :data {:expr-length (count (str expr))
                    :input-base64 input-base64}})
  (cond
    ;; Validation: expr is required
    (or (nil? expr) (empty? expr))
    {:error "No expression provided"
     :type "validation-error"}

    ;; Evaluate expression
    :else
    (let [start-time (System/currentTimeMillis)
          actual-expr (if input-base64
                        (try
                         (decode-base64 expr)
                         (catch Exception e
                                (analytics/log-calculation
                                 expr
                                 {:error (str "Base64 decode failed: " (.getMessage e))
                                  :type "decode-error"}
                                 0)
                                (throw (ex-info "Failed to decode base64 expression"
                                                {:error (.getMessage e) :expr expr}))))
                        expr)
          result (calc/calculate actual-expr)
          duration (- (System/currentTimeMillis) start-time)]
      ;; Log the calculation for analytics
      (analytics/log-calculation actual-expr result duration)
      ;; Format response with optional base64 encoding
      (if output-base64
        (-> result
            (update :result #(if (string? %) (encode-base64 %) %))
            (cond-> (:error result) (update :error encode-base64)))
        result))))

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def calculate-tool
     "Calculate tool definition with schema and handler."
     {:name "calculate"
      :description "Evaluate mathematical expressions using Clojure prefix notation with 100+ pre-loaded functions.

**Categories:**
- Arithmetic: + - * / mod pow sqrt exp
- Trigonometry: sin cos tan (radians), sind cosd tand (degrees)
- Statistics: sum mean median stdev variance
- Financial: percent-change roi compound-interest
- Crypto: wei->ether sats->btc token-convert portfolio-value
- DeFi: impermanent-loss staking-rewards liquidation-price
- Formatting: with-commas round-to scientific

**Constants:** pi e tau phi eth-decimals btc-decimals hash-decimals

**Examples:**
  (+ 2 3) => 5
  (sqrt 16) => 4.0
  (percent-change 100 125) => {:percent 25.0 :direction :increase}
  (wei->ether 1e18) => {:ether 1.0}
  (portfolio-value [[1000 :hash]] :usd [[/ [0.032 :usd] [1 :hash]]]) => [32.0 :usd]

**Base64:** Use input-base64/output-base64 for complex expressions with special characters."
      :inputSchema {:type "object"
                    :properties {:expr {:type "string"
                                        :description "Clojure expression in prefix notation"}
                                 :input-base64 {:type "boolean"
                                                :description "Interpret expr as base64-encoded (default: false)"}
                                 :output-base64 {:type "boolean"
                                                 :description "Return result as base64 (default: false)"}}
                    :required ["expr"]}
      :handler calculate-handler})

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the calculate module. Registers the calculate tool."
  [_deps config]
  (log/log! {:level :info
             :id ::calculate-starting
             :msg "Starting calculate module"
             :data {:config config}})
  (registry/register! calculate-tool)
  (log/log! {:level :info
             :id ::calculate-started
             :msg "Calculate module started"})
  {:registered-tools ["calculate"]})

(defn stop
  "Stop the calculate module. Unregisters the calculate tool."
  [_instance]
  (log/log! {:level :info
             :id ::calculate-stopping
             :msg "Stopping calculate module"})
  (registry/unregister! "calculate")
  nil)

(defn status
  "Get calculate module status."
  [_instance]
  {:status :ok
   :registered-tools ["calculate"]})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "Calculate module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
