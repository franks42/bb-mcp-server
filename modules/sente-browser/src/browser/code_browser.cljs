(ns code-browser
    "Browser-side code browser UI.

   Three-panel layout:
   - Left: Namespace list with filter
   - Middle: Vars list with filter
   - Right: Source viewer (CM6)

   Data flow:
   - Browser sends :code-browser/request-* events via sente
   - Server responds with :code-browser/* data events
   - Reagent atoms trigger re-render

   Usage (load via nREPL):
     (require '[code-browser :as cb])
     (cb/mount!)

   Live iteration:
     (swap! cb/!layout assoc :ns-width \"30%\")
     (cb/request-namespaces!)"
    (:require [reagent.core :as r]
              [clojure.string]
              [scittle-cm6 :as cm6]
              [sente-lite.client-scittle :as client]
              [code-browser.bootstrap :as bootstrap]))

;; =============================================================================
;; State
;; =============================================================================

#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !state
         (r/atom {:namespaces []
                  :selected-ns nil
                  :symbols []
                  :selected-symbol nil
                  :source nil
                  :ns-filter ""
                  :symbol-filter ""
                  :loading? false
                  :error nil}))

#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !layout
         (r/atom {:ns-width "25%"
                  :symbols-width "25%"
                  :source-width "50%"}))

;; =============================================================================
;; Server Communication
;; =============================================================================

(defn- send-event!
  "Send event to server via sente-lite.
   Uses the client-id from code-browser.bootstrap."
  [event-id data]
  (when-let [client-id @bootstrap/!client-id]
            (client/send! client-id [event-id data])))

(defn- on-server-event
  "Handle event from server. Called by sente adapter."
  [[event-id data]]
  (js/console.log (str "[code-browser] on-server-event called: " event-id))
  (case event-id
    :code-browser/namespaces
    (do
     (js/console.log (str "[code-browser] Received namespaces: " (count (:namespaces data))))
     (swap! !state assoc
            :namespaces (:namespaces data)
            :loading? false))

    :code-browser/symbols
    (do
     (js/console.log (str "[code-browser] Received symbols: " (count (:symbols data))))
     (swap! !state assoc
            :symbols (:symbols data)
            :loading? false))

    :code-browser/source
    (do
     (js/console.log "[code-browser] Received source")
     (swap! !state assoc
            :source data
            :loading? false))

    :code-browser/var-source
    (do
     (js/console.log "[code-browser] Received var-source")
     (swap! !state assoc
            :source data
            :loading? false))

    ;; Unknown event - ignore
    (js/console.log (str "[code-browser] Unknown event: " event-id))))

;; Register event handler (called on load)
(defn register-handler!
  "Register code-browser event handler with bootstrap dispatcher."
  []
  (js/console.log "[code-browser] Registering event handler...")
  (bootstrap/register-event-handler! :code-browser on-server-event)
  (js/console.log (str "[code-browser] Handlers after registration: " (pr-str (keys @bootstrap/!event-handlers)))))

;; =============================================================================
;; API Functions
;; =============================================================================

(defn request-namespaces!
  "Request namespace list from server."
  []
  (swap! !state assoc :loading? true)
  (send-event! :code-browser/request-namespaces {}))

(defn request-symbols!
  "Request symbols for a namespace."
  [ns-name]
  (swap! !state assoc :loading? true :selected-ns ns-name)
  (send-event! :code-browser/request-symbols {:ns ns-name}))

(defn request-source!
  "Request source code for a file region."
  [file start-line end-line]
  (swap! !state assoc :loading? true)
  (send-event! :code-browser/request-source
               {:file file :start-line start-line :end-line end-line}))

(defn request-var-source!
  "Request source for a specific var."
  [ns-name var-name]
  (swap! !state assoc :loading? true :selected-symbol var-name)
  (send-event! :code-browser/request-var-source
               {:ns ns-name :var-name var-name}))

;; =============================================================================
;; Filter Logic
;; =============================================================================

(defn- matches-filter?
  "Check if text matches filter pattern (case-insensitive substring)."
  [text pattern]
  (if (empty? pattern)
    true
    (clojure.string/includes?
     (clojure.string/lower-case text)
     (clojure.string/lower-case pattern))))

(defn filtered-namespaces
  "Get namespaces matching current filter."
  []
  (let [{:keys [namespaces ns-filter]} @!state]
    (filter #(matches-filter? % ns-filter) namespaces)))

(defn filtered-symbols
  "Get symbols matching current filter."
  []
  (let [{:keys [symbols symbol-filter]} @!state]
    (filter #(matches-filter? (:name %) symbol-filter) symbols)))

;; =============================================================================
;; Components
;; =============================================================================

(defn filter-input
  "Filter text input component."
  [value-key placeholder]
  [:input.filter-input
   {:type "text"
    :placeholder placeholder
    :value (get @!state value-key)
    :on-change #(swap! !state assoc value-key (-> % .-target .-value))}])

(defn namespace-item
  "Single namespace list item."
  [ns-name]
  (let [selected? (= ns-name (:selected-ns @!state))]
    [:div.list-item
     {:class (when selected? "selected")
      :on-click #(request-symbols! ns-name)}
     ns-name]))

(defn namespace-panel
  "Left panel: namespace list."
  []
  (let [layout @!layout
        nss (filtered-namespaces)]
    [:div.panel.namespace-panel
     {:style {:width (:ns-width layout)}}
     [:div.panel-header
      [:h3 "Namespaces"]
      [:button.refresh-btn {:on-click request-namespaces!} "Refresh"]]
     [filter-input :ns-filter "Filter namespaces..."]
     [:div.list-container
      (for [ns-name nss]
           ^{:key ns-name} [namespace-item ns-name])]
     [:div.panel-footer
      [:span (str (count nss) " namespaces")]]]))

(defn symbol-item
  "Single symbol list item."
  [{:keys [name kind]}]
  (let [selected? (= name (:selected-symbol @!state))
        selected-ns (:selected-ns @!state)]
    [:div.list-item
     {:class [(when selected? "selected")
              (clojure.core/name (or kind :unknown))]
      :on-click #(request-var-source! selected-ns name)}
     [:span.symbol-name name]
     [:span.symbol-kind (clojure.core/name (or kind :unknown))]]))

(defn symbols-panel
  "Middle panel: symbols list."
  []
  (let [layout @!layout
        syms (filtered-symbols)]
    [:div.panel.symbols-panel
     {:style {:width (:symbols-width layout)}}
     [:div.panel-header
      [:h3 (if-let [ns (:selected-ns @!state)]
                   (str ns " vars")
                   "Vars")]]
     [filter-input :symbol-filter "Filter vars..."]
     [:div.list-container
      (if (:selected-ns @!state)
        (for [sym syms]
             ^{:key (:name sym)} [symbol-item sym])
        [:div.empty-message "Select a namespace"])]
     [:div.panel-footer
      [:span (str (count syms) " vars")]]]))

(defn source-panel
  "Right panel: source code viewer.
   Uses stable editor ID to prevent flashing on source changes."
  []
  (let [layout @!layout
        source (:source @!state)]
    [:div.panel.source-panel
     {:style {:width (:source-width layout)}}
     [:div.panel-header
      [:h3 (if source
             (str (:ns source) "/" (:var-name source))
             "Source")]]
     [:div.source-container
      ;; Always mount editor with stable ID to prevent flash on source change
      [cm6/editor {:id "code-browser-source"
                   :value (or (:code source) ";; Select a var to view source")
                   :language :clojure
                   :read-only true}]]
     (when source
       [:div.panel-footer
        [:span (str (:file source) " lines " (:start-line source) "-" (:end-line source))]])]))

(defn loading-indicator
  "Subtle loading indicator - just a small spinner, no overlay.
   Returns nil to avoid full-page flash on quick operations."
  []
  ;; Removed full-page overlay - it caused flash on every selection
  ;; Could add delayed indicator for slow operations in future
  nil)

(defn error-display
  "Error message display."
  []
  (when-let [error (:error @!state)]
            [:div.error-banner
             [:span error]
             [:button {:on-click #(swap! !state dissoc :error)} "Dismiss"]]))

(defn main-panel
  "Main code browser component."
  []
  [:div.code-browser
   [error-display]
   [loading-indicator]
   [:div.panels-container
    [namespace-panel]
    [symbols-panel]
    [source-panel]]])

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn mount!
  "Mount code browser to #code-browser-root element.
   Uses bootstrap/mount-root! which wraps rdom/render with error boundary."
  []
  (register-handler!)
  (bootstrap/mount-root! [main-panel])
  (request-namespaces!)
  (js/console.log "[code-browser] Mounted"))

(defn unmount!
  "Unmount code browser."
  []
  ;; Note: unmount requires rdom directly, so we leave container mounted
  ;; and just clear state for now
  (reset! !state {:namespaces [] :selected-ns nil :symbols []
                  :selected-symbol nil :source nil :ns-filter ""
                  :symbol-filter "" :loading? false :error nil})
  (js/console.log "[code-browser] State reset"))
