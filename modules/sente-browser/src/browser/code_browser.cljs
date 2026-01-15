(ns code-browser
    "Browser-side code browser UI.

   Three-panel layout:
   - Left: Namespace list with filter
   - Middle: Vars list with filter
   - Right: Source viewer (CM6)

   Data flow (Phase 1.5-Pre - Synced Atoms):
   - Server maintains !code-browser-state atom
   - atom-sync pushes updates to browser via [:sync/op ...]
   - Browser reads from synced atom for server data
   - Local !ui-state for UI-only state (filters)
   - Legacy event handlers kept temporarily (become no-ops)

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

;; Synced atom from server - contains server data
;; Shape: {:namespaces [] :selected-ns nil :symbols [] :selected-symbol nil
;;         :source nil :loading? false :error nil}
(defn get-server-state
  "Get the synced atom for code browser server data.
   Returns a Reagent atom that auto-updates when server pushes changes."
  []
  (bootstrap/get-synced-atom :code-browser))

;; Local UI state - filters and UI-only concerns (not synced)
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !ui-state
         (r/atom {:ns-filter ""
                  :symbol-filter ""
                  :add-project-path ""
                  :aliases-expanded true}))  ;; Phase 1.5E.20: Aliases panel state

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

;; Legacy event handlers removed in Step 3 of Phase 1.5-Pre migration.
;; Server now pushes state via atom-sync, handled automatically by bootstrap.

;; =============================================================================
;; API Functions
;; =============================================================================

(defn request-namespaces!
  "Request namespace list from server.
   Server updates synced atom with namespaces."
  []
  (send-event! :code-browser/request-namespaces {}))

(defn request-symbols!
  "Request symbols for a namespace.
   Server updates synced atom with symbols list."
  [ns-name]
  (send-event! :code-browser/request-symbols {:ns ns-name}))

(defn request-source!
  "Request source code for a file region.
   Server updates synced atom with source."
  [file start-line end-line]
  (send-event! :code-browser/request-source
               {:file file :start-line start-line :end-line end-line}))

(defn request-var-source!
  "Request source for a specific var.
   kind is used to disambiguate when multiple symbols have same name.
   Server updates synced atom with source."
  [ns-name var-name kind]
  (send-event! :code-browser/request-var-source
               {:ns ns-name :var-name var-name :kind kind}))

(defn toggle-sort-mode!
  "Toggle symbol sort mode between :alpha and :file-order.
   Server re-sorts all cached symbols and pushes update."
  []
  (send-event! :code-browser/toggle-sort-mode {}))

(defn navigate-to-symbol!
  "Navigate to a symbol in any namespace.
   Phase 1.5E.10.7: Click deps/callers -> navigate.
   Server fetches symbols for ns, then fetches source for var."
  [ns-name var-name]
  (send-event! :code-browser/navigate-to-symbol {:ns ns-name :name var-name}))

(defn set-project-root!
  "Switch to a different project root.
   Phase 1.5E.3: Project directory selector.
   Server reinitializes LSP and refreshes all data."
  [path]
  (send-event! :code-browser/set-project-root {:path path}))

(defn add-project!
  "Add a new project to the available projects list.
   Phase 1.5E.15: Server validates directory and adds to list."
  [path]
  (send-event! :code-browser/add-project {:path path}))

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
  "Get namespaces matching current filter.
   Reads server data from synced atom.
   Takes ns-filter as parameter for proper Reagent reactivity."
  [ns-filter]
  (let [server-state @(get-server-state)
        namespaces (or (:namespaces server-state) [])]
    (filter #(matches-filter? % ns-filter) namespaces)))

(defn filtered-symbols
  "Get symbols matching current filter.
   Reads from accumulated symbols-by-ns map keyed by selected namespace.
   Top-level forms (Phase 1.5E.9) are hidden in :alpha mode.
   Takes symbol-filter as parameter for proper Reagent reactivity."
  [symbol-filter]
  (let [server-state @(get-server-state)
        selected-ns (:selected-ns server-state)
        sort-mode (or (:sort-mode server-state) :file-order)
        symbols (get-in server-state [:symbols-by-ns selected-ns] [])
        ;; Hide top-level forms in :alpha mode (Phase 1.5E.9)
        mode-filtered (if (= sort-mode :alpha)
                        (remove :top-level? symbols)
                        symbols)]
    (filter #(matches-filter? (:name %) symbol-filter) mode-filtered)))

;; =============================================================================
;; Components
;; =============================================================================

(defn filter-input
  "Filter text input component.
   Uses local !ui-state for filter values (not synced to server)."
  [value-key placeholder]
  [:input.filter-input
   {:type "text"
    :placeholder placeholder
    :value (get @!ui-state value-key)
    :on-change #(swap! !ui-state assoc value-key (-> % .-target .-value))}])

(defn namespace-item
  "Single namespace list item.
   Reads selected-ns from synced server state."
  [ns-name]
  (let [server-state @(get-server-state)
        selected? (= ns-name (:selected-ns server-state))]
    [:div.list-item
     {:class (when selected? "selected")
      :on-click #(request-symbols! ns-name)}
     ns-name]))

(defn namespace-panel
  "Left panel: namespace list.
   Dereferences !ui-state here for proper Reagent reactivity."
  []
  (let [layout @!layout
        ;; Dereference !ui-state HERE so Reagent tracks the dependency
        ns-filter (:ns-filter @!ui-state)
        nss (filtered-namespaces ns-filter)]
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
  "Single symbol list item.
   Reads selected state from synced server state."
  [{:keys [name kind]}]
  (let [server-state @(get-server-state)
        selected? (= name (:selected-symbol server-state))
        selected-ns (:selected-ns server-state)]
    [:div.list-item
     {:class [(when selected? "selected")
              (clojure.core/name (or kind :unknown))]
      :on-click #(request-var-source! selected-ns name kind)}
     [:span.symbol-name name]
     [:span.symbol-kind (clojure.core/name (or kind :unknown))]]))

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
  "Middle panel: symbols list.
   Reads selected-ns from synced server state.
   Dereferences !ui-state here for proper Reagent reactivity."
  []
  (let [layout @!layout
        server-state @(get-server-state)
        selected-ns (:selected-ns server-state)
        sort-mode (or (:sort-mode server-state) :file-order)
        ;; Dereference !ui-state HERE so Reagent tracks the dependency
        symbol-filter (:symbol-filter @!ui-state)
        syms (filtered-symbols symbol-filter)]
    [:div.panel.symbols-panel
     {:style {:width (:symbols-width layout)}}
     [:div.panel-header
      [:h3 (if selected-ns
             (str selected-ns " symbols")
             "Symbols")]
      [sort-mode-button]]
     [filter-input :symbol-filter "Filter symbols..."]
     [:div.list-container
      (if selected-ns
        (doall
         (for [sym syms]
           ;; Key must be unique - use name+line since same name can appear multiple times
              ^{:key (str (:name sym) "-" (:line sym))} [symbol-item sym]))
        [:div.empty-message "Select a namespace"])]
     [:div.panel-footer
      [:span (str (count syms) " symbols")]
      [:span.sort-mode-indicator
       (if (= sort-mode :alpha) " (A→Z)" " (file order)")]]]))

;; -----------------------------------------------------------------------------
;; Phase 1.5E.10: Symbol Inspector with Tabs
;; -----------------------------------------------------------------------------

;; Local state for selected tab (not synced to server)
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !source-tab (r/atom :source))

(defn- tab-button
  "Tab button component."
  [tab-id label selected-tab on-click]
  [:button.tab-btn
   {:class (when (= tab-id selected-tab) "active")
    :on-click #(on-click tab-id)}
   label])

(defn- source-view
  "Source code view with CM6 editor."
  [source highlight-line highlight-end-line]
  [:div.source-container
   [cm6/editor {:id "code-browser-source"
                :value (or (:code source) ";; Select a var to view source")
                :language :clojure
                :read-only true
                :highlight-line highlight-line
                :highlight-end-line highlight-end-line}]])

(defn- doc-view
  "Docstring view."
  [source]
  (let [doc (:doc source)]
    [:div.doc-container
     (if doc
       [:pre.docstring doc]
       [:div.empty-message "No docstring available"])]))

(defn- deps-view
  "Dependencies view - what this symbol calls.
   Phase 1.5E.10.7: Items are clickable for navigation.
   Phase 1.5E.19: For ns symbols, shows namespace-level deps (requires)."
  [source]
  (let [server-state @(get-server-state)
        selected-ns (:selected-ns server-state)
        selected-symbol (:selected-symbol server-state)
        ;; Check if current symbol is the namespace itself
        is-ns-symbol? (= selected-symbol selected-ns)
        ;; Get ns-usages for NS-level deps view
        ns-usages (when is-ns-symbol?
                    (get-in server-state [:ns-usages-by-ns selected-ns] []))
        ;; Regular var deps
        deps (:dependencies source)]
    [:div.deps-container
     (cond
       ;; NS-level deps for namespace symbol
       is-ns-symbol?
       (if (seq ns-usages)
         [:div.deps-list
          [:div.deps-header (str "Requires " (count ns-usages) " namespaces:")]
          (for [{:keys [to alias refer row]} ns-usages]
               ^{:key (str to "-" row)}
               [:div.ns-dep-item
                [:span.ns-dep-name to]
                (when alias
                  [:span.ns-dep-alias (str ":as " alias)])
                (when (seq refer)
                  [:span.ns-dep-refers
                   (str ":refer ["
                        (clojure.string/join " " (take 3 refer))
                        (when (> (count refer) 3) "...")
                        "]")])])]
         [:div.empty-message "No namespace dependencies (no requires)"])

       ;; Regular var dependencies
       (seq deps)
       [:div.deps-list
        [:div.deps-header (str "Calls " (count deps) " symbols:")]
        (for [{:keys [name ns line]} deps]
             ^{:key (str ns "/" name "-" line)}
             [:div.dep-item.clickable
              {:on-click #(navigate-to-symbol! ns name)}
              [:span.dep-name name]
              [:span.dep-ns ns]])]

       :else
       [:div.empty-message "No dependencies found"])]))

(defn- dependents-view
  "Dependents view - what calls this symbol.
   Phase 1.5E.10.7: Items are clickable for navigation."
  [source]
  (let [deps (:dependents source)]
    [:div.deps-container
     (if (seq deps)
       [:div.deps-list
        [:div.deps-header (str "Called by " (count deps) " symbols:")]
        (for [{:keys [name ns line]} deps]
             ^{:key (str ns "/" name "-" line)}
             [:div.dep-item.clickable
              {:on-click #(navigate-to-symbol! ns name)}
              [:span.dep-name name]
              [:span.dep-ns ns]])]
       [:div.empty-message "No dependents found (or not yet loaded)"])]))

(defn- impls-view
  "Implementations view - for protocols and multimethods.
   Phase 1.5E.8: Shows implementations with navigation.
   Also shows definition link for impls/methods to navigate back."
  [source]
  (let [impls (:implementations source)
        definition (:definition source)]
    [:div.impls-container
     ;; Definition link (for protocol-impl and defmethod)
     (when definition
       [:div.definition-section
        [:div.deps-header "Definition:"]
        [:div.dep-item.clickable
         {:on-click #(navigate-to-symbol! (:ns definition) (:name definition))}
         [:span.dep-name (:name definition)]
         [:span.dep-ns (:ns definition)]
         [:span.def-type (str "(" (name (:type definition)) ")")]]])
     ;; Implementations list (for protocols and defmulti)
     (when (seq impls)
       [:div.impls-list
        [:div.deps-header (str (count impls) " implementations:")]
        (for [{:keys [name ns line implementing-type dispatch-val]} impls]
             ^{:key (str ns "/" name "-" line)}
             [:div.dep-item.clickable
              {:on-click #(navigate-to-symbol! ns name)}
              [:span.dep-name name]
              [:span.dep-ns ns]
              (when implementing-type
                [:span.impl-type implementing-type])
              (when dispatch-val
                [:span.dispatch-val dispatch-val])])])
     ;; Empty message when neither definition nor impls
     (when (and (nil? definition) (empty? impls))
       [:div.empty-message "No implementations (load more namespaces to see impls)"])]))

;; -----------------------------------------------------------------------------
;; Phase 1.5E.20: Aliases Panel
;; -----------------------------------------------------------------------------

(defn- aliases-panel
  "Collapsible panel showing aliases and refers for current namespace.
   Phase 1.5E.20: Shows alias→namespace mappings and referred symbols.
   Refers derived from var-usages with :refer true flag."
  []
  (let [server-state @(get-server-state)
        selected-ns (:selected-ns server-state)
        ns-usages (get-in server-state [:ns-usages-by-ns selected-ns] [])
        ;; Extract aliases (usages with :alias)
        aliases (->> ns-usages
                     (filter :alias)
                     (map (fn [{:keys [alias to]}] {:short alias :full to}))
                     (sort-by :short))
        ;; Extract refers (usages with :refers list)
        ;; Each refer is now {:name "sym" :shadows-core? bool}
        refers (->> ns-usages
                    (filter :refers)
                    (mapcat (fn [{:keys [to refers]}]
                              (map (fn [{:keys [name shadows-core?]}]
                                     {:sym name :from to :shadows-core? shadows-core?})
                                   refers)))
                    (sort-by :sym))
        expanded? (:aliases-expanded @!ui-state)
        has-aliases? (seq aliases)
        has-refers? (seq refers)
        has-content? (or has-aliases? has-refers?)
        ;; Title reflects what's available
        title (cond
                (and has-aliases? has-refers?) "Aliases & Refers"
                has-refers? "Refers"
                :else "Aliases")]
    (when has-content?
      [:div.aliases-panel
       ;; Clickable header to toggle
       [:div.aliases-header
        {:on-click #(swap! !ui-state update :aliases-expanded not)}
        [:span.aliases-toggle (if expanded? "▼" "▶")]
        [:span.aliases-title title]
        [:span.aliases-count (str "(" (+ (count aliases) (count refers)) ")")]]
       ;; Collapsible content
       (when expanded?
         [:div.aliases-content
          ;; Alias mappings
          (when has-aliases?
            [:div.aliases-section
             (for [{:keys [short full]} aliases]
                  ^{:key (str "alias-" short "-" full)}
                  [:div.alias-item
                   [:span.alias-short short]
                   [:span.alias-arrow "→"]
                   [:span.alias-full full]])])
          ;; Referred symbols
          (when has-refers?
            [:div.refers-section
             (for [{:keys [sym from shadows-core?]} refers]
                  ^{:key (str "refer-" sym "-" from)}
                  [:div.refer-item
                   {:class (when shadows-core? "shadows-core")}
                   [:span.refer-sym sym]
                   (when shadows-core?
                     [:span.shadow-warning "⚠"])
                   [:span.refer-from (str "← " from)]])])])])))

(defn source-panel
  "Right panel: source code viewer with tabs.
   Uses stable editor ID to prevent flashing on source changes.
   Reads from accumulated source-by-var map keyed by ns/var-name.
   Supports :highlight-line/:highlight-end-line for protocol impl source (Phase 1.5E.12).
   Phase 1.5E.10: Adds tabs for Source, Doc, Deps, Callers."
  []
  (let [layout @!layout
        server-state @(get-server-state)
        selected-ns (:selected-ns server-state)
        selected-symbol (:selected-symbol server-state)
        var-key (when (and selected-ns selected-symbol)
                  (str selected-ns "/" selected-symbol))
        source (when var-key
                 (get-in server-state [:source-by-var var-key]))
        ;; Phase 1.5E.12: highlight lines from server (1-based, relative to extracted source)
        highlight-line (:highlight-line source)
        highlight-end-line (:highlight-end-line source)
        ;; Phase 1.5E.10: selected tab
        selected-tab @!source-tab]
    [:div.panel.source-panel
     {:style {:width (:source-width layout)}}
     [:div.panel-header
      [:h3 (if source
             ;; For ns symbols, just show ns name (not ns/ns)
             (if (= (:ns source) (:var-name source))
               (:ns source)
               (str (:ns source) "/" (:var-name source)))
             "Source")]
      ;; Tab bar (only show when source is loaded)
      (when source
        [:div.tab-bar
         [tab-button :source "Source" selected-tab #(reset! !source-tab %)]
         [tab-button :doc "Doc" selected-tab #(reset! !source-tab %)]
         [tab-button :deps "Deps" selected-tab #(reset! !source-tab %)]
         [tab-button :callers "Callers" selected-tab #(reset! !source-tab %)]
         ;; Phase 1.5E.8: Impls tab for protocols/multimethods
         [tab-button :impls "Impls" selected-tab #(reset! !source-tab %)]])]
     ;; Phase 1.5E.20: Aliases panel above source (only show in Source tab)
     (when (= selected-tab :source)
       [aliases-panel])
     ;; Tab content
     (case selected-tab
       :source [source-view source highlight-line highlight-end-line]
       :doc [doc-view source]
       :deps [deps-view source]
       :callers [dependents-view source]
       :impls [impls-view source]
       ;; Default to source
       [source-view source highlight-line highlight-end-line])
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

(defn- clear-error!
  "Send clear-error event to server."
  []
  (send-event! :code-browser/clear-error {}))

(defn error-display
  "Error message display.
   Reads error from synced server state."
  []
  (let [server-state @(get-server-state)
        error (:error server-state)]
    (when error
      [:div.error-banner
       [:span error]
       [:button {:on-click clear-error!} "Dismiss"]])))

(defn- project-basename
  "Extract project name from full path."
  [path]
  (when path
    (last (clojure.string/split path #"/"))))

(defn- project-selector
  "Dropdown selector for switching between projects.
   Phase 1.5E.3: Project directory selector UI."
  []
  (let [server-state @(get-server-state)
        projects (or (:projects server-state) [])
        current-project (:current-project server-state)]
    (when (seq projects)
      [:select.project-selector
       {:value (or current-project "")
        :on-change #(let [new-path (-> % .-target .-value)]
                      (when (and (not (clojure.string/blank? new-path))
                                 (not= new-path current-project))
                        (set-project-root! new-path)))
        :title "Switch project"}
       (for [proj projects]
            ^{:key proj}
            [:option {:value proj} (project-basename proj)])])))

(defn- add-project-input
  "Input field + button for adding a new project.
   Phase 1.5E.15: Simple form to add project directories."
  []
  (let [path (:add-project-path @!ui-state)]
    [:div.add-project-input
     [:input
      {:type "text"
       :placeholder "Add project path..."
       :value path
       :on-change #(swap! !ui-state assoc :add-project-path (-> % .-target .-value))
       :on-key-down #(when (= (.-key %) "Enter")
                       (when (not (clojure.string/blank? path))
                         (add-project! path)
                         (swap! !ui-state assoc :add-project-path "")))}]
     [:button
      {:on-click #(when (not (clojure.string/blank? path))
                    (add-project! path)
                    (swap! !ui-state assoc :add-project-path ""))
       :title "Add project directory"}
      "+"]]))

(defn git-status-bar
  "Header bar showing project path, git branch, and dirty status.
   Reads from synced server state :git field.
   Phase 1.5E.3: Includes project selector dropdown.
   Phase 1.5E.15: Includes add project input."
  []
  (let [server-state @(get-server-state)
        git-info (:git server-state)
        projects (:projects server-state)
        current-project (:current-project server-state)]
    [:div.git-status-bar
     ;; Project selector (if multiple projects configured)
     (if (> (count projects) 1)
       [project-selector]
       ;; Single project - just show name
       (when-let [project-name (project-basename (or current-project
                                                     (:project-root git-info)))]
                 [:span.project-name {:title (or current-project (:project-root git-info))}
                  project-name]))
     ;; Add project input (Phase 1.5E.15)
     [add-project-input]
     ;; Git branch info
     (when-let [branch (:branch git-info)]
               [:span.branch-info
                [:span.branch-icon "\uD83C\uDF3F"] ;; 🌿
                [:span.branch-name branch]
                (when (:dirty? git-info) [:span.dirty-indicator "*"])])
     (when-let [upstream (:upstream git-info)]
               [:span.upstream-info {:title (str "tracking " upstream)} "↑"])]))

(defn main-panel
  "Main code browser component."
  []
  [:div.code-browser
   [git-status-bar]
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
   Uses bootstrap/mount-root! which wraps rdom/render with error boundary.
   Server state is synced via atom-sync (no legacy handler registration needed)."
  []
  (bootstrap/mount-root! [main-panel])
  (request-namespaces!)
  (js/console.log "[code-browser] Mounted"))

(defn unmount!
  "Unmount code browser.
   Clears local UI state. Server state is managed by synced atom."
  []
  ;; Clear local filter state (including Phase 1.5E.20 aliases panel state)
  (reset! !ui-state {:ns-filter "" :symbol-filter "" :add-project-path "" :aliases-expanded true})
  (js/console.log "[code-browser] UI state reset"))
