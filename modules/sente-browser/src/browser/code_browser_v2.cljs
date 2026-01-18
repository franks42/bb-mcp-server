(ns code-browser-v2
    "Browser-side Code Browser v2.

   URI-centric architecture with atom-sync integration.

   State model:
   - Server state: synced via atom-sync (:code-browser-v2 key)
   - Local UI state: filters, expanded panels, selection highlights

   Data flow:
   - Server pushes state updates via [:sync/op ...]
   - Browser reads from synced atom for server data
   - Browser sends events for selections and actions

   Usage (load via nREPL):
     (require '[code-browser-v2 :as cb])
     (cb/mount!)"
    (:require [reagent.core :as r]
              [clojure.string :as str]
              [sente-lite.client-scittle :as client]
              [code-browser.bootstrap :as bootstrap]))

;; =============================================================================
;; Server State Access
;; =============================================================================

(defn get-server-state
  "Get the synced atom for code-browser-v2 server data.
   Returns a Reagent atom that auto-updates when server pushes changes.

   Shape:
   {:projects          []       ;; List of project maps
    :selected-project  nil      ;; URI string of selected project
    :namespaces        []       ;; Namespaces for selected project
    :selected-ns       nil      ;; URI string of selected namespace
    :symbols           []       ;; Symbols for selected namespace
    :selected-symbol   nil      ;; URI string of selected symbol
    :source            nil      ;; {:content ... :file ... :start-line ...}
    :loading?          false
    :error             nil}"
  []
  (bootstrap/get-synced-atom :code-browser-v2))

;; =============================================================================
;; Local UI State
;; =============================================================================

(defonce ^{:doc "Local UI state - not synced to server"}
 !ui-state
         (r/atom {:ns-filter ""
                  :symbol-filter ""
                  :project-filter ""
                  :alias-filter ""
                  :inspector-tab :source}))  ;; :source | :doc | :aliases | :deps | :callers

(defonce ^{:doc "Layout configuration"}
 !layout
         (r/atom {:projects-width "20%"
                  :ns-width "20%"
                  :symbols-width "25%"
                  :source-width "35%"}))

;; =============================================================================
;; Event Dispatch
;; =============================================================================

(defn- send-event!
  "Send event to server via sente-lite.
   Uses the client-id from code-browser.bootstrap."
  [event-id data]
  (when-let [client-id @bootstrap/!client-id]
            (client/send! client-id [event-id data])))

(defn load-projects!
  "Request project list reload from server."
  []
  (send-event! :code-browser-v2/load-projects {}))

(defn select-project!
  "Select a project by URI and load its namespaces."
  [project-uri]
  (send-event! :code-browser-v2/select-project {:uri project-uri}))

(defn select-namespace!
  "Select a namespace by URI and load its symbols."
  [ns-uri]
  (send-event! :code-browser-v2/select-namespace {:uri ns-uri}))

(defn select-symbol!
  "Select a symbol by URI and load its source."
  [symbol-uri]
  (send-event! :code-browser-v2/select-symbol {:uri symbol-uri}))

(defn toggle-sort-mode!
  "Toggle symbol sort mode between file-order and alpha."
  []
  (send-event! :code-browser-v2/toggle-sort-mode {}))

;; =============================================================================
;; Filtering
;; =============================================================================

(defn- matches-filter?
  "Check if text matches filter pattern (case-insensitive substring)."
  [text pattern]
  (if (str/blank? pattern)
    true
    (str/includes?
     (str/lower-case (str text))
     (str/lower-case pattern))))

(defn filtered-projects
  "Get projects matching current filter."
  [filter-text]
  (let [server-state @(get-server-state)
        projects (or (:projects server-state) [])]
    (filter #(matches-filter? (or (:uri/project %) (:uri/string %)) filter-text)
            projects)))

(defn filtered-namespaces
  "Get namespaces matching current filter."
  [filter-text]
  (let [server-state @(get-server-state)
        namespaces (or (:namespaces server-state) [])]
    (filter #(matches-filter? (:ns/name %) filter-text)
            namespaces)))

(defn filtered-symbols
  "Get symbols matching current filter.
   Sorts by file-order (file + line) or alpha (type + name) based on sort-mode."
  [filter-text]
  (let [server-state @(get-server-state)
        symbols (or (:symbols server-state) [])
        sort-mode (or (:sort-mode server-state) :file-order)
        filtered (filter #(matches-filter? (:symbol/name %) filter-text) symbols)]
    (if (= sort-mode :alpha)
      (sort-by (juxt :symbol/type :symbol/name) filtered)
      ;; file-order: sort by file then line number
      (sort-by (juxt :symbol/file :symbol/line) filtered))))

;; =============================================================================
;; Inspector Tab Components
;; =============================================================================

(defn- inspector-tab-bar
  "Tab bar for symbol inspector."
  []
  (let [current-tab (:inspector-tab @!ui-state)]
    [:div.inspector-tabs
     (for [[tab-key label] [[:source "Source"]
                            [:doc "Doc"]
                            [:aliases "Aliases"]
                            [:deps "Deps"]
                            [:callers "Callers"]]]
          ^{:key tab-key}
          [:button.tab-btn
           {:class (when (= tab-key current-tab) "active")
            :on-click #(swap! !ui-state assoc :inspector-tab tab-key)}
           label])]))

(defn- source-view
  "Source code view for selected symbol."
  []
  (let [server-state @(get-server-state)
        source (:source server-state)]
    (if source
      [:div.source-view
       [:pre.source-code (:content source)]
       [:div.source-info
        [:span (str (:file source) " lines " (:start-line source) "-" (:end-line source))]]]
      [:div.empty-message "Select a symbol to view source"])))

(defn- doc-view
  "Documentation view for selected symbol."
  []
  (let [server-state @(get-server-state)
        selected-uri (:selected-symbol server-state)
        symbols (:symbols server-state)
        ;; Find the selected symbol in the list
        selected-sym (->> symbols
                          (filter #(= (:uri/string %) selected-uri))
                          first)]
    (if selected-sym
      [:div.doc-view
       [:div.symbol-header
        [:span.symbol-name (:symbol/name selected-sym)]
        (when-let [kind (:symbol/type selected-sym)]
                  [:span.symbol-kind (name kind)])]
       (when-let [arglists (:symbol/arglists selected-sym)]
                 [:div.arglists
                  [:strong "Args: "]
                  [:code (pr-str arglists)]])
       (if-let [doc (:symbol/doc selected-sym)]
               [:div.docstring
                [:pre doc]]
               [:div.no-doc "No documentation available"])]
      [:div.empty-message "Select a symbol to view documentation"])))

(defn- deps-view
  "Dependencies view for selected symbol."
  []
  (let [server-state @(get-server-state)
        selected-uri (:selected-symbol server-state)]
    ;; TODO: R3.1 - Fetch deps from server when available
    (if selected-uri
      [:div.deps-view
       [:div.placeholder "Dependencies view coming in next iteration"]
       [:div.hint "This will show symbols that this symbol calls/uses"]]
      [:div.empty-message "Select a symbol to view dependencies"])))

(defn- callers-view
  "Callers view for selected symbol."
  []
  (let [server-state @(get-server-state)
        selected-uri (:selected-symbol server-state)]
    ;; TODO: R3.1 - Fetch callers from server when available
    (if selected-uri
      [:div.callers-view
       [:div.placeholder "Callers view coming in next iteration"]
       [:div.hint "This will show symbols that call/use this symbol"]]
      [:div.empty-message "Select a symbol to view callers"])))

;; =============================================================================
;; Components
;; =============================================================================

(defn filter-input
  "Reusable filter text input component."
  [value-key placeholder]
  [:input.filter-input
   {:type "text"
    :placeholder placeholder
    :value (get @!ui-state value-key "")
    :on-change #(swap! !ui-state assoc value-key (-> % .-target .-value))}])

(defn- alias-item
  "Single alias list item."
  [alias-entity]
  [:div.alias-item
   [:span.alias-name (:alias/name alias-entity)]
   [:span.alias-arrow " → "]
   [:span.alias-target (:alias/to-ns alias-entity)]])

(defn- refer-item
  "Single refer list item."
  [refer-entity]
  [:div.refer-item
   [:span.refer-name (:refer/symbol refer-entity)]
   [:span.refer-from (str " (from " (:refer/from-ns-source refer-entity) ")")]])

(defn- aliases-view
  "Aliases and refers view for selected namespace."
  []
  (let [server-state @(get-server-state)
        selected-ns (:selected-ns server-state)
        aliases (or (:aliases server-state) [])
        refers (or (:refers server-state) [])
        filter-text (:alias-filter @!ui-state)
        filtered-aliases (if (str/blank? filter-text)
                           aliases
                           (filter #(or (matches-filter? (:alias/name %) filter-text)
                                        (matches-filter? (:alias/to-ns %) filter-text))
                                   aliases))
        filtered-refers (if (str/blank? filter-text)
                          refers
                          (filter #(or (matches-filter? (:refer/symbol %) filter-text)
                                       (matches-filter? (:refer/from-ns-source %) filter-text))
                                  refers))]
    (if selected-ns
      [:div.aliases-view
       [filter-input :alias-filter "Filter aliases/refers..."]
       [:div.aliases-section
        [:h4 (str "Aliases (" (count filtered-aliases) ")")]
        (if (seq filtered-aliases)
          [:div.alias-list
           (for [a filtered-aliases]
                ^{:key (:uri/string a)}
                [alias-item a])]
          [:div.empty-hint "No aliases"])]
       [:div.refers-section
        [:h4 (str "Refers (" (count filtered-refers) ")")]
        (if (seq filtered-refers)
          [:div.refer-list
           (for [r filtered-refers]
                ^{:key (:uri/string r)}
                [refer-item r])]
          [:div.empty-hint "No refers"])]]
      [:div.empty-message "Select a namespace to view aliases"])))

(defn project-item
  "Single project list item."
  [project]
  (let [server-state @(get-server-state)
        uri (:uri/string project)
        selected? (= uri (:selected-project server-state))]
    [:div.list-item
     {:class (when selected? "selected")
      :on-click #(select-project! uri)}
     [:span.project-name (or (:uri/project project) uri)]]))

(defn projects-panel
  "Projects panel - list of available projects."
  []
  (let [layout @!layout
        filter-text (:project-filter @!ui-state)
        projects (filtered-projects filter-text)]
    [:div.panel.projects-panel
     {:style {:width (:projects-width layout)}}
     [:div.panel-header
      [:h3 "Projects"]
      [:button.refresh-btn {:on-click load-projects!} "Refresh"]]
     [filter-input :project-filter "Filter projects..."]
     [:div.list-container
      (for [project projects]
           ^{:key (:uri/string project)}
           [project-item project])]
     [:div.panel-footer
      [:span (str (count projects) " projects")]]]))

(defn namespace-item
  "Single namespace list item.
   Shows file count badge for multi-file namespaces."
  [ns-entity]
  (let [server-state @(get-server-state)
        uri (:uri/string ns-entity)
        selected? (= uri (:selected-ns server-state))
        files (:ns/files ns-entity)
        file-count (count files)]
    [:div.list-item
     {:class (when selected? "selected")
      :on-click #(select-namespace! uri)}
     [:span.ns-name (:ns/name ns-entity)]
     ;; Show file count badge for multi-file namespaces
     (when (> file-count 1)
       [:span.file-count-badge (str "(" file-count " files)")])]))

(defn namespaces-panel
  "Namespaces panel - list for selected project."
  []
  (let [layout @!layout
        server-state @(get-server-state)
        selected-project (:selected-project server-state)
        filter-text (:ns-filter @!ui-state)
        namespaces (filtered-namespaces filter-text)]
    [:div.panel.namespace-panel
     {:style {:width (:ns-width layout)}}
     [:div.panel-header
      [:h3 (if selected-project "Namespaces" "Select Project")]]
     (when selected-project
       [filter-input :ns-filter "Filter namespaces..."])
     [:div.list-container
      (if selected-project
        (for [ns-entity namespaces]
             ^{:key (:uri/string ns-entity)}
             [namespace-item ns-entity])
        [:div.empty-message "Select a project"])]
     [:div.panel-footer
      [:span (str (count namespaces) " namespaces")]]]))

(defn symbol-item
  "Single symbol list item.
   Optionally shows file badge in alpha mode for multi-file namespaces."
  [sym-entity show-file-badge?]
  (let [server-state @(get-server-state)
        uri (:uri/string sym-entity)
        selected? (= uri (:selected-symbol server-state))
        kind (:symbol/type sym-entity)
        filename (:symbol/file sym-entity)]
    [:div.list-item
     {:class [(when selected? "selected")
              (when kind (name kind))]
      :on-click #(select-symbol! uri)}
     [:span.symbol-name (:symbol/name sym-entity)]
     [:span.symbol-kind (when kind (name kind))]
     ;; Show filename badge in alpha mode for multi-file ns
     (when (and show-file-badge? filename)
       [:span.symbol-file-badge (str "[" (last (str/split filename #"/")) "]")])]))

(defn- file-divider
  "File divider component for multi-file namespace display.
   Visual separator between files in file-order view."
  [filename]
  [:div.file-divider
   [:span.file-divider-line]
   [:span.file-divider-name (or (last (str/split filename #"/")) filename)]
   [:span.file-divider-line]])

(defn- symbols-with-dividers
  "Insert file dividers between symbols from different files.
   Returns seq of [:divider filename] and [:symbol sym] items."
  [symbols]
  (loop [result []
         remaining symbols
         current-file nil]
        (if (empty? remaining)
          result
          (let [sym (first remaining)
                sym-file (:symbol/file sym)]
            (if (and sym-file (not= sym-file current-file))
              ;; New file - add divider then symbol
              (recur (conj result [:divider sym-file] [:symbol sym])
                     (rest remaining)
                     sym-file)
              ;; Same file - just add symbol
              (recur (conj result [:symbol sym])
                     (rest remaining)
                     current-file))))))

(defn sort-mode-button
  "Toggle button for symbol sort mode.
   Shows current mode and toggles on click."
  []
  (let [server-state @(get-server-state)
        sort-mode (or (:sort-mode server-state) :file-order)
        label (if (= sort-mode :alpha) "A→Z" "↓")]
    [:button.sort-mode-btn
     {:on-click toggle-sort-mode!
      :title (if (= sort-mode :alpha)
               "Sorted alphabetically. Click for file order."
               "Sorted by file order. Click for alphabetical.")}
     label]))

(defn symbols-panel
  "Symbols panel - list for selected namespace.
   Shows file dividers in file-order mode, file badges in alpha mode for multi-file ns."
  []
  (let [layout @!layout
        server-state @(get-server-state)
        selected-ns (:selected-ns server-state)
        sort-mode (or (:sort-mode server-state) :file-order)
        ;; Find the selected namespace entity to check file count
        namespaces (:namespaces server-state)
        selected-ns-entity (->> namespaces
                                (filter #(= (:uri/string %) selected-ns))
                                first)
        files (or (:ns/files selected-ns-entity) [])
        is-multi-file? (> (count files) 1)
        ;; In file-order mode with multi-file ns, add dividers
        show-dividers? (and is-multi-file? (= sort-mode :file-order))
        ;; In alpha mode with multi-file ns, show file badges
        show-file-badges? (and is-multi-file? (= sort-mode :alpha))
        filter-text (:symbol-filter @!ui-state)
        symbols (filtered-symbols filter-text)]
    [:div.panel.symbols-panel
     {:style {:width (:symbols-width layout)}}
     [:div.panel-header
      [:h3 (if selected-ns "Symbols" "Select Namespace")]
      (when selected-ns [sort-mode-button])]
     (when selected-ns
       [filter-input :symbol-filter "Filter symbols..."])
     [:div.list-container
      (if selected-ns
        (if show-dividers?
          ;; File-order with dividers
          (let [items (symbols-with-dividers symbols)]
            (doall
             (for [[idx [item-type item]] (map-indexed vector items)]
                  (case item-type
                    :divider ^{:key (str selected-ns "-divider-" item "-" idx)} [file-divider item]
                    :symbol ^{:key (str selected-ns "-" (:symbol/file item) "-" (:symbol/name item) "-" (:symbol/line item))}
                    [symbol-item item false]))))
          ;; Normal list (with optional file badges in alpha mode)
          (doall
           (for [sym symbols]
                ^{:key (str selected-ns "-" (:symbol/file sym) "-" (:symbol/name sym) "-" (:symbol/line sym))}
                [symbol-item sym show-file-badges?])))
        [:div.empty-message "Select a namespace"])]
     [:div.panel-footer
      [:span (str (count symbols) " symbols")
       (when is-multi-file?
         (str " in " (count files) " files"))]
      [:span.sort-mode-indicator
       (if (= sort-mode :alpha) " (A→Z)" " (file order)")]]]))

(defn source-panel
  "Symbol inspector panel - displays source, docs, aliases, deps, callers in tabs."
  []
  (let [layout @!layout
        server-state @(get-server-state)
        selected-symbol (:selected-symbol server-state)
        selected-ns (:selected-ns server-state)
        current-tab (:inspector-tab @!ui-state)]
    [:div.panel.source-panel
     {:style {:width (:source-width layout)}}
     [:div.panel-header
      [:h3 (cond selected-symbol "Inspector"
                 selected-ns "Namespace Info"
                 :else "Source")]]
     (when (or selected-symbol selected-ns)
       [inspector-tab-bar])
     [:div.inspector-content
      (case current-tab
        :source [source-view]
        :doc [doc-view]
        :aliases [aliases-view]
        :deps [deps-view]
        :callers [callers-view]
        [source-view])]]))

(defn loading-indicator
  "Loading indicator overlay."
  []
  (let [server-state @(get-server-state)
        loading? (:loading? server-state)]
    (when loading?
      [:div.loading-overlay
       [:div.spinner]])))

(defn error-display
  "Error message banner."
  []
  (let [server-state @(get-server-state)
        error (:error server-state)]
    (when error
      [:div.error-banner
       [:span error]
       [:button {:on-click #(send-event! :code-browser-v2/clear-error {})} "Dismiss"]])))

(defn main-panel
  "Main code browser v2 component."
  []
  [:div.code-browser-v2
   [error-display]
   [loading-indicator]
   [:div.panels-container
    [projects-panel]
    [namespaces-panel]
    [symbols-panel]
    [source-panel]]])

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn mount!
  "Mount code browser v2 to #code-browser-root element.
   Initializes by requesting projects from server."
  []
  (bootstrap/mount-root! [main-panel])
  (load-projects!)
  (js/console.log "[code-browser-v2] Mounted"))

(defn unmount!
  "Unmount code browser v2.
   Clears local UI state."
  []
  (reset! !ui-state {:ns-filter ""
                     :symbol-filter ""
                     :project-filter ""})
  (js/console.log "[code-browser-v2] UI state reset"))
