(ns code-browser.sync
    "Atom-sync exports for Code Browser v2.

   Manages the synced state atom that's automatically pushed to connected
   browsers via atom-sync infrastructure.

   State structure:
   {:projects          []       ;; List of project maps
    :selected-project  nil      ;; URI string of selected project
    :namespaces        []       ;; Namespaces for selected project
    :selected-ns       nil      ;; URI string of selected namespace
    :symbols           []       ;; Symbols for selected namespace
    :aliases           []       ;; Aliases for selected namespace
    :refers            []       ;; Refers for selected namespace
    :selected-symbol   nil      ;; URI string of selected symbol
    :source            nil      ;; {:content ... :file ... :start-line ...}
    :loading?          false
    :error             nil}"
    (:require [atom-sync.core :as atom-sync]
              [atom-sync.server :as atom-sync-server]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; State Definition
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "The synced state atom for code-browser-v2.
   Registered with atom-sync when enabled."}
 !state
         (atom {:projects []
                :selected-project nil
                :namespaces []
                :selected-ns nil
                :symbols []
                :aliases []
                :refers []
                :selected-symbol nil
                :source nil
                :loading? false
                :error nil}))

;;; ---------------------------------------------------------------------------
;;; State Accessors
;;; ---------------------------------------------------------------------------

(defn get-state
  "Get current state value."
  []
  @!state)

(defn get-selected-project
  "Get the currently selected project URI."
  []
  (:selected-project @!state))

(defn get-selected-namespace
  "Get the currently selected namespace URI."
  []
  (:selected-ns @!state))

(defn get-selected-symbol
  "Get the currently selected symbol URI."
  []
  (:selected-symbol @!state))

;;; ---------------------------------------------------------------------------
;;; State Mutations (trigger atom-sync broadcast)
;;; ---------------------------------------------------------------------------

(defn set-loading!
  "Set loading state."
  [loading?]
  (swap! !state assoc :loading? loading?))

(defn set-error!
  "Set error message (clears loading)."
  [error-msg]
  (swap! !state assoc :error error-msg :loading? false))

(defn clear-error!
  "Clear error message."
  []
  (swap! !state assoc :error nil))

(defn set-projects!
  "Set the list of projects."
  [projects]
  (log/log! {:level :debug
             :id ::set-projects
             :msg "Setting projects"
             :data {:count (count projects)}})
  (swap! !state assoc :projects (vec projects)))

(defn select-project!
  "Select a project and update namespaces.
   Clears downstream selections (namespace, symbol, source, aliases, refers)."
  [project-uri namespaces]
  (log/log! {:level :debug
             :id ::select-project
             :msg "Selecting project"
             :data {:uri project-uri
                    :namespace-count (count namespaces)}})
  (swap! !state assoc
         :selected-project project-uri
         :namespaces (vec namespaces)
         :selected-ns nil
         :symbols []
         :aliases []
         :refers []
         :selected-symbol nil
         :source nil
         :loading? false
         :error nil))

(defn select-namespace!
  "Select a namespace and update symbols, aliases, and refers.
   Clears downstream selections (symbol, source).
   Can be called with 2 args (ns-uri, symbols) for backwards compatibility,
   or with a map of all data."
  ([ns-uri symbols]
   (select-namespace! ns-uri symbols [] []))
  ([ns-uri symbols aliases refers]
   (log/log! {:level :debug
              :id ::select-namespace
              :msg "Selecting namespace"
              :data {:uri ns-uri
                     :symbol-count (count symbols)
                     :alias-count (count aliases)
                     :refer-count (count refers)}})
   (swap! !state assoc
          :selected-ns ns-uri
          :symbols (vec symbols)
          :aliases (vec aliases)
          :refers (vec refers)
          :selected-symbol nil
          :source nil
          :loading? false
          :error nil)))

(defn select-symbol!
  "Select a symbol and update source."
  [symbol-uri source]
  (log/log! {:level :debug
             :id ::select-symbol
             :msg "Selecting symbol"
             :data {:uri symbol-uri
                    :has-source (some? source)}})
  (swap! !state assoc
         :selected-symbol symbol-uri
         :source source
         :loading? false
         :error nil))

(defn reset-state!
  "Reset state to initial values."
  []
  (log/log! {:level :info
             :id ::reset-state
             :msg "Resetting code-browser-v2 state"})
  (reset! !state {:projects []
                  :selected-project nil
                  :namespaces []
                  :selected-ns nil
                  :symbols []
                  :aliases []
                  :refers []
                  :selected-symbol nil
                  :source nil
                  :loading? false
                  :error nil}))

;;; ---------------------------------------------------------------------------
;;; Atom-Sync Registration
;;; ---------------------------------------------------------------------------

(defn register-sync!
  "Register state atom with atom-sync for browser synchronization."
  []
  (log/log! {:level :info
             :id ::register-sync
             :msg "Registering code-browser-v2 state with atom-sync"})
  (atom-sync/register-synced-atom! :code-browser-v2 !state))

(defn unregister-sync!
  "Unregister state atom from atom-sync."
  []
  (log/log! {:level :info
             :id ::unregister-sync
             :msg "Unregistering code-browser-v2 state from atom-sync"})
  (atom-sync/unregister-synced-atom! :code-browser-v2))

(defn register-on-connect!
  "Register a callback to run when browser connects.
   Used for just-in-time initialization."
  [callback]
  (atom-sync-server/register-on-connect! :code-browser-v2 callback))

(defn unregister-on-connect!
  "Unregister the on-connect callback."
  []
  (atom-sync-server/unregister-on-connect! :code-browser-v2))
