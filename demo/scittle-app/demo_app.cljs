;; Demo Scittle App — Multi-namespace app for code browser testing
;;
;; This file is loaded into a Scittle browser runtime via nREPL proxy.
;; It defines three namespaces with diverse var types for browsing:
;;   - demo.core    — Reagent counter app with atoms and components
;;   - demo.utils   — Pure utility functions and dynamic vars
;;   - demo.state   — State management with history tracking
;;
;; Load via: bb nrepl-direct load-local-file demo/scittle-app/demo_app.cljs -t demo-scittle/browser-1

;; === demo.utils ===

(ns demo.utils
    "Pure utility functions for the demo app.

   Provides formatting, clamping, and configuration.")

(def ^:dynamic *debug-mode*
  "When true, extra logging is enabled."
  false)

(def app-name
  "The application display name."
  "Scittle Demo App")

(def version
  "Current version of the demo app."
  "0.1.0")

(defn format-number
  "Format a number with optional prefix.
   Returns a string representation."
  ([n] (str n))
  ([n prefix] (str prefix n)))

(defn clamp
  "Clamp a value between min-val and max-val (inclusive)."
  [value min-val max-val]
  (max min-val (min max-val value)))

(defn log-debug
  "Log a debug message when *debug-mode* is true."
  [& args]
  (when *debug-mode*
    (apply println "[DEBUG]" args)))

;; === demo.state ===

(ns demo.state
    "State management with event history tracking.

   Provides a history atom and functions to record/clear events.")

(defonce !history
  "History of recorded events. Each entry is a map with :event, :timestamp, :data."
  (atom []))

(defonce !event-count
  "Total number of events ever recorded (monotonically increasing)."
  (atom 0))

(defn record-event!
  "Record an event in the history.
   event-type is a keyword, data is an optional map."
  ([event-type]
   (record-event! event-type nil))
  ([event-type data]
   (swap! !event-count inc)
   (swap! !history conj
          {:event event-type
           :timestamp (.toISOString (js/Date.))
           :data data
           :seq @!event-count})))

(defn clear-history!
  "Clear all recorded events."
  []
  (reset! !history []))

(defn history-summary
  "Return a summary of the current history."
  []
  {:total-events (count @!history)
   :event-types (distinct (map :event @!history))
   :total-ever @!event-count})

;; === demo.core ===

(ns demo.core
    "Main demo application — Reagent counter with state management.

   Provides a simple counter app that can be rendered in the browser.
   Uses demo.utils for formatting and demo.state for event tracking."
    (:require [demo.utils :as utils]
              [demo.state :as state]))

(defonce !app-state
  "Main application state atom. Contains the counter value and settings."
  (atom {:counter 0
         :title (str utils/app-name " v" utils/version)
         :theme :light}))

(defn increment!
  "Increment the counter by 1 (clamped to max 100)."
  []
  (swap! !app-state update :counter #(utils/clamp (inc %) 0 100))
  (state/record-event! :increment {:value (:counter @!app-state)}))

(defn decrement!
  "Decrement the counter by 1 (clamped to min 0)."
  []
  (swap! !app-state update :counter #(utils/clamp (dec %) 0 100))
  (state/record-event! :decrement {:value (:counter @!app-state)}))

(defn reset-counter!
  "Reset the counter to 0."
  []
  (swap! !app-state assoc :counter 0)
  (state/record-event! :reset))

(defn counter-display
  "Return a formatted string for the current counter value."
  []
  (utils/format-number (:counter @!app-state) "Count: "))

(defn toggle-theme!
  "Toggle between :light and :dark theme."
  []
  (swap! !app-state update :theme #(if (= % :light) :dark :light))
  (state/record-event! :toggle-theme {:theme (:theme @!app-state)}))

(defn app-component
  "Main Reagent component for the counter app.
   Returns hiccup-style markup."
  []
  [:div {:style {:padding "20px" :font-family "sans-serif"}}
   [:h1 (:title @!app-state)]
   [:div {:style {:font-size "48px" :margin "20px 0"}}
    (counter-display)]
   [:div {:style {:display "flex" :gap "10px"}}
    [:button {:on-click decrement!} "-"]
    [:button {:on-click reset-counter!} "Reset"]
    [:button {:on-click increment!} "+"]]
   [:div {:style {:margin-top "20px" :color "#666"}}
    (str "Events: " (count @demo.state/!history)
         " | Theme: " (name (:theme @!app-state)))]])
