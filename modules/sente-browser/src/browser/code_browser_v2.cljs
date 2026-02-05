(ns code-browser-v2
    "Browser-side Code Browser v2 — Widget Architecture.

   Every widget is a view over a URI property. Widgets are independent,
   each bound to its own URI. Selecting something in one widget can
   spawn a new widget for the resulting URI.

   Widget model:
   - !widgets atom: {widget-id {:id :type :uri :data :loading? :error :filter}}
   - Each widget fetches data via :code-browser-v2/fetch event
   - Server responds with data, widget manager stores in :data
   - Clicking an item opens a new widget for the child URI

   Also maintains backwards-compatible shared state via atom-sync.

   Usage (load via nREPL or button):
     (require '[code-browser-v2 :as cb])
     (cb/mount!)"
    (:require [reagent.core :as r]
              [clojure.string :as str]
              [sente-lite.client-scittle :as client]
              [code-browser.bootstrap :as bootstrap]
              [code-browser.uri :as uri]))

;; =============================================================================
;; Server State Access (backwards compatibility)
;; =============================================================================

(defn get-server-state
  "Get the synced atom for code-browser-v2 server data."
  []
  (bootstrap/get-synced-atom :code-browser-v2))

;; =============================================================================
;; Event Dispatch
;; =============================================================================

(defn- send-event!
  "Send event to server via sente-lite."
  [event-id data]
  (when-let [client-id @bootstrap/!client-id]
            (client/send! client-id [event-id data])))

;; Legacy event senders (for backwards compat with shared state)

(defn load-projects!
  "Request project list reload via shared state."
  [] (send-event! :code-browser-v2/load-projects {}))

(defn select-project!
  "Select project via shared state (legacy)."
  [uri] (send-event! :code-browser-v2/select-project {:uri uri}))

(defn select-namespace!
  "Select namespace via shared state (legacy)."
  [uri] (send-event! :code-browser-v2/select-namespace {:uri uri}))

(defn select-symbol!
  "Select symbol via shared state (legacy)."
  [uri] (send-event! :code-browser-v2/select-symbol {:uri uri}))

(defn toggle-sort-mode!
  "Toggle sort mode via shared state (legacy)."
  [] (send-event! :code-browser-v2/toggle-sort-mode {}))

;; =============================================================================
;; Widget Manager
;; =============================================================================

(defonce ^{:doc "All open widgets. {widget-id {:id :type :uri :data :loading? :error :filter}}"}
 !widgets (r/atom {}))

(defonce ^{:doc "Counter for generating unique widget IDs"}
 !widget-counter (atom 0))

(defonce ^{:doc "The focused widget ID (for hash routing)"}
 !focused-widget (r/atom nil))

(defn- next-widget-id
  "Generate a unique widget ID."
  []
  (keyword (str "w" (swap! !widget-counter inc))))

(defn- type->view-name
  "Convert a widget type keyword to a view name string for URI query params."
  [widget-type]
  (when widget-type (name widget-type)))

(defn- view-name->type
  "Convert a view name string from URI query params to a widget type keyword."
  [view-name]
  (when view-name (keyword view-name)))

(defn- fetch-widget-data!
  "Send a fetch request to the server for a widget.
   Uses the full URI (with ?view= query) for the server to derive the property.
   Falls back to explicit :property for widgets without a view in URI."
  [widget-id widget-type uri]
  (let [parsed (when uri (uri/parse uri))
        has-view? (get-in parsed [:uri/query "view"])
        property (when-not has-view?
                   (case widget-type
                     :project-list :project-list
                     :ns-list :ns-list
                     :symbol-list :symbol-list
                     :source :source
                     :doc :doc
                     :aliases :aliases
                     :refers :refers
                     :deps :deps
                     :callers :callers
                     nil))]
    (swap! !widgets assoc-in [widget-id :loading?] true)
    (send-event! :code-browser-v2/fetch
                 {:uri uri :property property :widget-id widget-id})))

(defn open-widget!
  "Open a new widget for the given URI.
   Accepts {:uri \"dir://...?view=source\"} where type is derived from query,
   or legacy {:type :source :uri \"dir://...\"} for backwards compat.
   Returns the widget ID."
  [{:keys [type uri]}]
  (let [parsed (when uri (uri/parse uri))
        query-view (get-in parsed [:uri/query "view"])
        ;; Derive type: query param > explicit :type > default from URI level
        effective-type (or (view-name->type query-view)
                           type
                           (cond
                             (nil? parsed)           :project-list
                             (:uri/symbol parsed)    :source
                             (:uri/namespace parsed) :symbol-list
                             :else                   :ns-list))
        ;; Ensure URI has ?view= query param
        full-uri (if (and uri (nil? query-view))
                   (uri/with-query (uri/base-uri uri)
                                   {"view" (type->view-name effective-type)})
                   uri)
        wid (next-widget-id)]
    (swap! !widgets assoc wid {:id wid
                               :type effective-type
                               :uri full-uri
                               :data nil
                               :loading? true
                               :error nil
                               :filter ""})
    (reset! !focused-widget wid)
    ;; Update hash to reflect focused widget
    (when full-uri
      (set! (.-hash js/window.location) full-uri))
    (fetch-widget-data! wid effective-type full-uri)
    wid))

(defn close-widget!
  "Close a widget by ID."
  [widget-id]
  (swap! !widgets dissoc widget-id)
  (when (= @!focused-widget widget-id)
    ;; Focus the last remaining widget, if any
    (let [remaining (keys @!widgets)]
      (reset! !focused-widget (last remaining))
      (if-let [focused (get @!widgets (last remaining))]
              (when-let [u (:uri focused)]
                        (set! (.-hash js/window.location) u))
              (set! (.-hash js/window.location) "")))))

(defn refresh-widget!
  "Refresh data for a widget."
  [widget-id]
  (when-let [w (get @!widgets widget-id)]
            (fetch-widget-data! widget-id (:type w) (:uri w))))

(defn- handle-fetch-response!
  "Handle a fetch response from the server, updating the widget's data."
  [data]
  (let [{:keys [widget-id success error]} data
        wid (keyword (str (name widget-id)))]
    (if success
      (swap! !widgets update wid assoc
             :data (:data data)
             :loading? false
             :error nil)
      (swap! !widgets update wid assoc
             :loading? false
             :error (or error "Unknown error")))))

;; Register event handler for fetch responses
(bootstrap/register-event-handler!
 :code-browser-v2
 (fn [[event-id data]]
   (case event-id
     :code-browser-v2/fetch-response
     (handle-fetch-response! data)
     ;; Ignore other events (handled by atom-sync)
     nil)))

;; =============================================================================
;; Filtering Helper
;; =============================================================================

(defn- matches-filter?
  "Case-insensitive substring match."
  [text pattern]
  (if (str/blank? pattern)
    true
    (str/includes? (str/lower-case (str text))
                   (str/lower-case pattern))))

;; =============================================================================
;; Widget Header Component
;; =============================================================================

(defn- uri-breadcrumb
  "Render a breadcrumb from a URI string (query params stripped for display)."
  [uri-string]
  (when uri-string
    (let [parsed (uri/parse (uri/base-uri uri-string))]
      [:div.widget-breadcrumb
       (when (:uri/project parsed)
         [:span.breadcrumb-segment (:uri/project parsed)])
       (when (:uri/namespace parsed)
         [:<>
          [:span.breadcrumb-sep " / "]
          [:span.breadcrumb-segment (:uri/namespace parsed)]])
       (when (:uri/symbol parsed)
         [:<>
          [:span.breadcrumb-sep " / "]
          [:span.breadcrumb-segment (:uri/symbol parsed)]])])))

(defn- widget-header
  "Widget header with title, breadcrumb, refresh and close buttons."
  [widget-id widget]
  (let [type-labels {:project-list "Projects"
                     :ns-list "Namespaces"
                     :symbol-list "Symbols"
                     :source "Source"
                     :doc "Doc"
                     :aliases "Aliases"
                     :refers "Refers"
                     :deps "Deps"
                     :callers "Callers"}]
    [:div.widget-header
     [:div.widget-title-row
      [:h3 (get type-labels (:type widget) "Widget")]
      [:div.widget-actions
       [:button.widget-btn {:on-click #(refresh-widget! widget-id)
                            :title "Refresh"} "R"]
       [:button.widget-btn.close-btn {:on-click #(close-widget! widget-id)
                                      :title "Close"} "x"]]]
     [uri-breadcrumb (:uri widget)]]))

;; =============================================================================
;; Widget Filter Component
;; =============================================================================

(defn- widget-filter-input
  "Filter input for a widget (stores filter in widget local state)."
  [widget-id placeholder]
  [:input.filter-input
   {:type "text"
    :placeholder placeholder
    :value (or (get-in @!widgets [widget-id :filter]) "")
    :on-change #(swap! !widgets assoc-in [widget-id :filter]
                       (-> % .-target .-value))}])

;; =============================================================================
;; Widget Components — Project List
;; =============================================================================

(defn- project-list-widget
  "Widget: list of all projects."
  [widget-id widget]
  (let [projects (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (filter #(matches-filter? (or (:uri/project %) (:uri/string %))
                                           filter-text)
                         projects)]
    [:div.widget.project-list-widget
     [widget-header widget-id widget]
     [widget-filter-input widget-id "Filter projects..."]
     [:div.list-container
      (doall
       (for [project filtered]
            ^{:key (:uri/string project)}
            [:div.list-item
             {:on-click (fn []
                          (open-widget! {:uri (uri/with-query (:uri/string project)
                                                              {"view" "ns-list"})}))}
             [:span.project-name (or (:uri/project project) (:uri/string project))]]))]
     [:div.widget-footer
      [:span (str (count filtered) " projects")]]]))

;; =============================================================================
;; Widget Components — Namespace List
;; =============================================================================

(defn- ns-list-widget
  "Widget: namespaces for a project URI."
  [widget-id widget]
  (let [namespaces (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (filter #(matches-filter? (:ns/name %) filter-text) namespaces)]
    [:div.widget.ns-list-widget
     [widget-header widget-id widget]
     [widget-filter-input widget-id "Filter namespaces..."]
     [:div.list-container
      (doall
       (for [ns-entity filtered]
            ^{:key (:uri/string ns-entity)}
            [:div.list-item
             {:on-click (fn []
                          (open-widget! {:uri (uri/with-query (:uri/string ns-entity)
                                                              {"view" "symbol-list"})}))}
             [:span.ns-name (:ns/name ns-entity)]
             (when (> (count (or (:ns/files ns-entity) [])) 1)
               [:span.file-count-badge
                (str "(" (count (:ns/files ns-entity)) " files)")])]))]
     [:div.widget-footer
      [:span (str (count filtered) " namespaces")]]]))

;; =============================================================================
;; Widget Components — Symbol List
;; =============================================================================

(defn- symbol-list-widget
  "Widget: symbols for a namespace URI."
  [widget-id widget]
  (let [symbols (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (->> symbols
                      (filter #(matches-filter? (:symbol/name %) filter-text))
                      (sort-by (juxt :symbol/type :symbol/name)))]
    [:div.widget.symbol-list-widget
     [widget-header widget-id widget]
     [widget-filter-input widget-id "Filter symbols..."]
     [:div.list-container
      (doall
       (for [sym filtered]
            ^{:key (:uri/string sym)}
            [:div.list-item
             {:class (when-let [kind (:symbol/type sym)] (name kind))
              :on-click (fn []
                          (open-widget! {:uri (uri/with-query (:uri/string sym)
                                                              {"view" "source"})}))}
             [:span.symbol-name (:symbol/name sym)]
             (when-let [kind (:symbol/type sym)]
                       [:span.symbol-kind (name kind)])]))]
     [:div.widget-footer
      [:span (str (count filtered) " symbols")]]]))

;; =============================================================================
;; Widget Components — Source View
;; =============================================================================

(defn- source-widget-component
  "Widget: source code for a symbol URI."
  [widget-id widget]
  (let [source (:data widget)]
    [:div.widget.source-widget
     [widget-header widget-id widget]
     (if source
       [:div.source-view
        [:pre.source-code (:content source)]
        [:div.source-info
         [:span (str (:file source) " lines " (:start-line source) "-" (:end-line source))]]]
       [:div.empty-message "No source available"])]))

;; =============================================================================
;; Widget Components — Doc View
;; =============================================================================

(defn- doc-widget-component
  "Widget: documentation for a symbol URI."
  [widget-id widget]
  (let [sym-data (:data widget)]
    [:div.widget.doc-widget
     [widget-header widget-id widget]
     (if sym-data
       [:div.doc-view
        [:div.symbol-header
         [:span.symbol-name (:symbol/name sym-data)]
         (when-let [kind (:symbol/type sym-data)]
                   [:span.symbol-kind (name kind)])]
        (when-let [arglists (:symbol/arglists sym-data)]
                  [:div.arglists
                   [:strong "Args: "]
                   [:code (pr-str arglists)]])
        (if-let [doc (:symbol/doc sym-data)]
                [:div.docstring [:pre doc]]
                [:div.no-doc "No documentation available"])]
       [:div.empty-message "No documentation available"])]))

;; =============================================================================
;; Widget Components — Aliases View
;; =============================================================================

(defn- aliases-widget-component
  "Widget: aliases for a namespace URI."
  [widget-id widget]
  (let [aliases (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (if (str/blank? filter-text)
                   aliases
                   (filter #(or (matches-filter? (:alias/name %) filter-text)
                                (matches-filter? (:alias/to-ns %) filter-text))
                           aliases))]
    [:div.widget.aliases-widget
     [widget-header widget-id widget]
     [widget-filter-input widget-id "Filter aliases..."]
     [:div.list-container
      (if (seq filtered)
        (doall
         (for [a filtered]
              ^{:key (:uri/string a)}
              [:div.alias-item
               [:span.alias-name (:alias/name a)]
               [:span.alias-arrow " -> "]
               [:span.alias-target (:alias/to-ns a)]]))
        [:div.empty-hint "No aliases"])]
     [:div.widget-footer
      [:span (str (count filtered) " aliases")]]]))

;; =============================================================================
;; Widget Components — Refers View
;; =============================================================================

(defn- refers-widget-component
  "Widget: refers for a namespace URI."
  [widget-id widget]
  (let [refers (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (if (str/blank? filter-text)
                   refers
                   (filter #(or (matches-filter? (:refer/symbol %) filter-text)
                                (matches-filter? (:refer/from-ns-source %) filter-text))
                           refers))]
    [:div.widget.refers-widget
     [widget-header widget-id widget]
     [widget-filter-input widget-id "Filter refers..."]
     [:div.list-container
      (if (seq filtered)
        (doall
         (for [r filtered]
              ^{:key (:uri/string r)}
              [:div.refer-item
               [:span.refer-name (:refer/symbol r)]
               [:span.refer-from (str " (from " (:refer/from-ns-source r) ")")]]))
        [:div.empty-hint "No refers"])]
     [:div.widget-footer
      [:span (str (count filtered) " refers")]]]))

;; =============================================================================
;; Widget Components — Deps / Callers (placeholder)
;; =============================================================================

(defn- deps-widget-component
  "Widget: dependencies for a symbol URI."
  [widget-id widget]
  [:div.widget.deps-widget
   [widget-header widget-id widget]
   [:div.deps-view
    [:div.placeholder "Dependencies view coming in next iteration"]
    [:div.hint "This will show symbols that this symbol calls/uses"]]])

(defn- callers-widget-component
  "Widget: callers for a symbol URI."
  [widget-id widget]
  [:div.widget.callers-widget
   [widget-header widget-id widget]
   [:div.callers-view
    [:div.placeholder "Callers view coming in next iteration"]
    [:div.hint "This will show symbols that call/use this symbol"]]])

;; =============================================================================
;; Widget Renderer
;; =============================================================================

(defn- render-widget
  "Render a widget based on its type."
  [widget-id widget]
  (case (:type widget)
    :project-list [project-list-widget widget-id widget]
    :ns-list [ns-list-widget widget-id widget]
    :symbol-list [symbol-list-widget widget-id widget]
    :source [source-widget-component widget-id widget]
    :doc [doc-widget-component widget-id widget]
    :aliases [aliases-widget-component widget-id widget]
    :refers [refers-widget-component widget-id widget]
    :deps [deps-widget-component widget-id widget]
    :callers [callers-widget-component widget-id widget]
    [:div.widget "Unknown widget type: " (str (:type widget))]))

;; =============================================================================
;; Widget Toolbar
;; =============================================================================

(defn- widget-toolbar
  "Toolbar for opening new widgets."
  []
  (let [focused @!focused-widget
        focused-widget (get @!widgets focused)
        focused-uri (:uri focused-widget)
        parsed (when focused-uri (uri/parse focused-uri))
        ;; Determine what extra widgets can be opened based on focused URI level
        at-ns? (and parsed (:uri/namespace parsed) (nil? (:uri/symbol parsed)))
        at-sym? (and parsed (:uri/symbol parsed))]
    [:div.widget-toolbar
     [:button.toolbar-btn
      {:on-click #(open-widget! {:type :project-list :uri nil})}
      "+ Projects"]
     (when at-ns?
       (let [base (uri/base-uri focused-uri)]
         [:<>
          [:button.toolbar-btn
           {:on-click #(open-widget! {:uri (uri/with-query base {"view" "aliases"})})}
           "+ Aliases"]
          [:button.toolbar-btn
           {:on-click #(open-widget! {:uri (uri/with-query base {"view" "refers"})})}
           "+ Refers"]]))
     (when at-sym?
       (let [base (uri/base-uri focused-uri)]
         [:<>
          [:button.toolbar-btn
           {:on-click #(open-widget! {:uri (uri/with-query base {"view" "doc"})})}
           "+ Doc"]
          [:button.toolbar-btn
           {:on-click #(open-widget! {:uri (uri/with-query base {"view" "deps"})})}
           "+ Deps"]
          [:button.toolbar-btn
           {:on-click #(open-widget! {:uri (uri/with-query base {"view" "callers"})})}
           "+ Callers"]]))]))

;; =============================================================================
;; Widget Container (Dynamic Layout)
;; =============================================================================

(defn- widget-container
  "Container that renders all open widgets in a flex layout."
  []
  (let [widgets @!widgets
        focused @!focused-widget]
    [:div.widgets-container
     (if (seq widgets)
       (doall
        (for [[wid w] (sort-by key widgets)]
             ^{:key wid}
             [:div.widget-wrapper
              {:class (when (= wid focused) "focused")
               :on-click #(do (reset! !focused-widget wid)
                              (when-let [u (:uri w)]
                                        (set! (.-hash js/window.location) u)))
               :style {:min-width "250px"
                       :flex (if (= (:type w) :source) "2" "1")}}
              (if (:loading? w)
                [:div.widget
                 [widget-header wid w]
                 [:div.loading-overlay [:div.spinner]]]
                (if-let [err (:error w)]
                        [:div.widget
                         [widget-header wid w]
                         [:div.error-banner [:span err]]]
                        [render-widget wid w]))]))
       [:div.empty-message "No widgets open. Click '+ Projects' to start."])]))

;; =============================================================================
;; Hash Routing
;; =============================================================================

(defn- open-widget-chain-for-uri!
  "Given a full URI (possibly with ?view= query), open the appropriate widget chain.
   For a symbol URI: opens project-list, ns-list, symbol-list, and the view widget."
  [uri-string]
  (when-let [parsed (uri/parse uri-string)]
            (let [query-view (get-in parsed [:uri/query "view"])
                  base (uri/base-uri uri-string)]
      ;; Always open project list
              (open-widget! {:type :project-list :uri nil})
      ;; If we have a namespace, open ns-list for the project
              (when (:uri/namespace parsed)
                (let [proj-uri (uri/project-uri parsed)]
                  (open-widget! {:uri (uri/with-query proj-uri {"view" "ns-list"})})))
      ;; If we have a symbol, open symbol-list for the namespace
              (when (:uri/symbol parsed)
                (let [ns-uri (uri/namespace-uri parsed)]
                  (open-widget! {:uri (uri/with-query ns-uri {"view" "symbol-list"})})))
      ;; Open the deepest level with the requested view (or default)
              (cond
                (:uri/symbol parsed)
                (open-widget! {:uri (uri/with-query base {"view" (or query-view "source")})})
                (:uri/namespace parsed)
                (open-widget! {:uri (uri/with-query base {"view" (or query-view "symbol-list")})})
                :else
                (open-widget! {:uri (uri/with-query base {"view" (or query-view "ns-list")})})))))

(defn- setup-hash-routing!
  "Set up hash change listener for navigation."
  []
  (.addEventListener js/window "hashchange"
                     (fn [_e]
                       (let [hash (subs (.-hash js/window.location) 1)] ;; strip #
                         (when (and (not (str/blank? hash))
                                    (uri/valid? hash))
                           ;; Check if any existing widget matches this URI
                           (let [matching (->> @!widgets
                                               (filter (fn [[_k v]] (= (:uri v) hash)))
                                               first)]
                             (if matching
                               ;; Focus existing widget
                               (reset! !focused-widget (first matching))
                               ;; Open new widget chain
                               (do
                                (reset! !widgets {})
                                (open-widget-chain-for-uri! hash)))))))))

;; =============================================================================
;; Main Panel (Widget Architecture)
;; =============================================================================

(defn main-panel
  "Main code browser v2 component with widget architecture."
  []
  [:div.code-browser-v2
   [widget-toolbar]
   [widget-container]])

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn mount!
  "Mount code browser v2 to #code-browser-root element.
   Opens a project-list widget on startup, or restores from hash."
  []
  (setup-hash-routing!)
  (bootstrap/mount-root! [main-panel])
  (let [hash (subs (.-hash js/window.location) 1)]
    (if (and (not (str/blank? hash)) (uri/valid? hash))
      ;; Restore from hash
      (open-widget-chain-for-uri! hash)
      ;; Default: open project list
      (open-widget! {:type :project-list :uri nil})))
  (js/console.log "[code-browser-v2] Mounted (widget architecture)"))

(defn unmount!
  "Unmount code browser v2. Resets widget state."
  []
  (reset! !widgets {})
  (reset! !focused-widget nil)
  (js/console.log "[code-browser-v2] Unmounted"))
