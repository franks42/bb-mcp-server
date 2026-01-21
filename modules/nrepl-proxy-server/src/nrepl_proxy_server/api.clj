(ns nrepl-proxy-server.api
    "Shadow-cljs style API for browser REPL switching.

   Usage from nREPL client (Calva, terminal):

   user=> (browser/list)
   [{:id :browser-1 :nickname \"browser-1\" ...}
    {:id :browser-2 :nickname \"browser-2\" ...}]

   user=> (browser/repl :browser-2)
   To quit, type: :cljs/quit
   cljs.user=>

   cljs.user=> :cljs/quit
   nil
   user=>"
    (:require [mcp-nrepl.state.connection :as conn-state]
              [nrepl-proxy-server.session :as session]
              [taoensso.trove :as log]))

;; =============================================================================
;; Browser Query API
;; =============================================================================

(defn list-browsers
  "List all available browser connections.
   Returns vector of browser info maps with :id, :nickname, :connected-at, :capabilities."
  []
  (let [browsers (conn-state/get-validated-browser-connections)]
    (->> browsers
         (map (fn [[conn-id conn-data]]
                (let [nickname (conn-state/get-nickname-for-connection conn-id)]
                  {:id (keyword (or nickname conn-id))
                   :nickname nickname
                   :connection-id conn-id
                   :connected-at (:created-at conn-data)
                   :capabilities (:capabilities conn-data)
                   :status (:status conn-data)})))
         (sort-by :nickname)
         vec)))

(defn browser-connected?
  "Check if a specific browser is connected.
   browser-id can be keyword (:browser-1) or string (\"browser-1\")."
  [browser-id]
  (let [browser-name (name browser-id)
        browsers (list-browsers)]
    (some #(= browser-name (:nickname %)) browsers)))

(defn get-browser-connection-id
  "Get the full connection-id for a browser nickname.
   Returns nil if not found."
  [browser-id]
  (let [browser-name (name browser-id)]
    (conn-state/resolve-connection-id browser-name)))

;; =============================================================================
;; Session Control API (called during eval interception)
;; =============================================================================

(defn switch-to-browser!
  "Switch an nREPL session to target a browser.
   Called when user evals (browser/repl :browser-2).

   Returns result map for nREPL response."
  [nrepl-session-id browser-id]
  (let [browser-name (name browser-id)
        browsers (list-browsers)]
    (if-let [browser (some #(when (= browser-name (:nickname %)) %) browsers)]
            (do
             (session/switch-to-browser! nrepl-session-id (:connection-id browser))
             (log/log! {:level :info
                        :id ::switched-to-browser
                        :msg "Session switched to browser"
                        :data {:nrepl-session nrepl-session-id
                               :browser browser-name}})
             {:success true
              :message (str "Switched to " browser-name ". To quit, type: :cljs/quit")
              :browser browser-name
              :prompt "cljs.user"})
            (do
             (log/log! {:level :warn
                        :id ::browser-not-found
                        :msg "Browser not found for switch"
                        :data {:browser-id browser-id
                               :available (map :nickname browsers)}})
             {:success false
              :error (str "Browser not found: " browser-name)
              :available (map :nickname browsers)}))))

(defn switch-to-bb!
  "Switch an nREPL session back to bb context.
   Called when user evals :cljs/quit.

   Returns result map for nREPL response."
  [nrepl-session-id]
  (let [old-target (session/switch-to-bb! nrepl-session-id)]
    (log/log! {:level :info
               :id ::switched-to-bb
               :msg "Session switched back to bb"
               :data {:nrepl-session nrepl-session-id
                      :previous-target old-target}})
    {:success true
     :message "Back to bb REPL"
     :previous-target old-target
     :prompt "user"}))

;; =============================================================================
;; Code Generation for Eval
;; =============================================================================

(defn generate-list-code
  "Generate Clojure code string for browser/list result.
   This is evaled locally in bb when user calls (browser/list)."
  []
  (pr-str (list-browsers)))

(defn generate-repl-switch-code
  "Generate code to indicate successful switch.
   The actual switching is done server-side, this just returns the message."
  [result]
  (if (:success result)
    (pr-str (:message result))
    (str "(throw (ex-info " (pr-str (:error result))
         " {:available " (pr-str (:available result)) "}))")))

(defn generate-quit-code
  "Generate code for :cljs/quit result."
  []
  "nil")

;; =============================================================================
;; Detection Helpers (used by middleware)
;; =============================================================================

(defn browser-list-call?
  "Check if code is a call to browser/list."
  [code]
  (and (string? code)
       (re-find #"\(\s*browser/list\s*\)" code)))

(defn browser-repl-call?
  "Check if code is a call to browser/repl.
   Returns the browser-id if match, nil otherwise."
  [code]
  (when (string? code)
    (when-let [[_ browser-id] (re-find #"\(\s*browser/repl\s+:(\S+)\s*\)" code)]
              (keyword browser-id))))

(defn cljs-quit?
  "Check if code is :cljs/quit."
  [code]
  (and (string? code)
       (re-find #"^\s*:cljs/quit\s*$" code)))
