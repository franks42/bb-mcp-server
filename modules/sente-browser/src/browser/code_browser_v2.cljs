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
              [reagent.dom :as rdom]
              [clojure.string :as str]
              [sente-lite.client-scittle :as client]
              [code-browser.bootstrap :as bootstrap]
              [code-browser.uri :as uri]
              [scittle-cm6 :as cm6]
              [taoensso.trove :as log]))

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

(defonce ^{:doc "Set of widget-ids with expanded breadcrumbs"}
 !breadcrumb-expanded (r/atom #{}))

(defonce ^{:doc "WinBox JS instances. {widget-id WinBox-instance}"}
 !winbox-instances (atom {}))

(defonce ^{:doc "Cascade offset for positioning new windows"}
 !cascade-offset (atom 0))

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

(def ^:private type-labels
     "Display labels for widget types."
     {:project-list "Projects"
      :ns-list "Namespaces"
      :symbol-list "Symbols"
      :source "Source"
      :doc "Doc"
      :aliases "Aliases"
      :refers "Refers"
      :deps "Deps"
      :callers "Callers"})

(def ^:private project-list-color
     "Title bar color for the project-list hub widget."
     "#37474f")

(def ^:private project-colors
     "Color palette for per-project WinBox title bars."
     ["#1e88e5"
      "#43a047"
      "#e53935"
      "#8e24aa"
      "#f4511e"
      "#00acc1"
      "#6d4c41"
      "#546e7a"])

(defonce ^{:doc "Map of project name to assigned color"}
 !project-color-map (atom {}))

(defn- project-color
  "Get a consistent color for a project name."
  [project-name]
  (if-let [color (get @!project-color-map project-name)]
          color
          (let [idx (count @!project-color-map)
                color (nth project-colors (mod idx (count project-colors)))]
            (swap! !project-color-map assoc project-name color)
            color)))

(defn- winbox-defaults
  "Return default WinBox dimensions and position based on widget type."
  [widget-type]
  (let [offset (* @!cascade-offset 30)
        base (case widget-type
               :project-list {:width 280 :height 400 :x 20 :y 20}
               :ns-list {:width 300 :height 450 :x 60 :y 30}
               :symbol-list {:width 320 :height 450 :x 100 :y 40}
               :source {:width 600 :height 500 :x 150 :y 50}
               ;; default for doc, aliases, refers, deps, callers
               {:width 350 :height 400 :x 140 :y 60})]
    (swap! !cascade-offset #(mod (inc %) 10))
    (-> base
        (update :x + offset)
        (update :y + offset))))

(defn- type->title
  "Build a WinBox window title from widget type and URI."
  [widget-type uri]
  (let [label (get type-labels widget-type "Widget")]
    (if uri
      (let [parsed (uri/parse (uri/base-uri uri))
            detail (or (:uri/symbol parsed)
                       (:uri/namespace parsed)
                       (:uri/project parsed))]
        (if detail
          (str label " \u2014 " detail)
          label))
      label)))

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
    (log/log! {:level :info :id ::fetch-requested
               :msg "Fetch requested"
               :data {:widget-id widget-id :type widget-type :uri uri
                      :property property}})
    (send-event! :code-browser-v2/fetch
                 {:uri uri :property property :widget-id widget-id})))

(declare create-winbox!)
(declare focus-widget!)
(declare fit-to-content!)

(defn open-widget!
  "Open a new widget for the given URI.
   Accepts {:uri \"dir://...?view=source\"} where type is derived from query,
   or legacy {:type :source :uri \"dir://...\"} for backwards compat.
   If a widget with the same URI already exists, focuses it instead.
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
        ;; Check for existing widget with same URI
        existing (when full-uri
                   (->> @!widgets
                        (some (fn [[wid w]] (when (= (:uri w) full-uri) wid)))))]
    (if existing
      ;; Focus existing widget instead of creating a duplicate
      (do (focus-widget! existing)
          (reset! !focused-widget existing)
          (when full-uri
            (set! (.-hash js/window.location) full-uri))
          (log/log! {:level :info :id ::widget-focused
                     :msg "Focused existing widget"
                     :data {:widget-id existing :uri full-uri}})
          existing)
      ;; Create new widget
      (let [wid (next-widget-id)]
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
        ;; Create WinBox floating window with Reagent content
        (create-winbox! wid)
        (fetch-widget-data! wid effective-type full-uri)
        (log/log! {:level :info :id ::widget-opened
                   :msg "Opened new widget"
                   :data {:widget-id wid :type effective-type :uri full-uri}})
        wid))))

(defn close-widget!
  "Close a widget by ID. Dissoc instance then close WinBox (DOM removal handles cleanup)."
  [widget-id]
  ;; Dissoc before .close to break onclose -> close-widget! loop.
  ;; try-catch guards against double-close when called from onclose callback
  ;; (WinBox is already closing itself, .close on it throws).
  (let [widget-info (get @!widgets widget-id)]
    (log/log! {:level :info :id ::widget-closed
               :msg "Closed widget"
               :data {:widget-id widget-id
                      :type (:type widget-info)
                      :uri (:uri widget-info)}}))
  (when-let [wb (get @!winbox-instances widget-id)]
            (swap! !winbox-instances dissoc widget-id)
            (try (.close wb) (catch js/Error _e nil)))
  (swap! !widgets dissoc widget-id)
  (swap! !breadcrumb-expanded disj widget-id)
  (when (= @!focused-widget widget-id)
    ;; Focus the last remaining widget, if any
    (let [remaining (keys @!widgets)]
      (reset! !focused-widget (last remaining))
      (if-let [focused (get @!widgets (last remaining))]
              (when-let [u (:uri focused)]
                        (set! (.-hash js/window.location) u))
              (set! (.-hash js/window.location) "")))))

(defn- focus-widget!
  "Bring an existing widget's WinBox to front."
  [widget-id]
  (when-let [wb (get @!winbox-instances widget-id)]
            (.focus wb)))

(defn refresh-widget!
  "Refresh data for a widget."
  [widget-id]
  (when-let [w (get @!widgets widget-id)]
            (log/log! {:level :info :id ::widget-refreshed
                       :msg "Widget refresh requested"
                       :data {:widget-id widget-id :type (:type w) :uri (:uri w)}})
            (fetch-widget-data! widget-id (:type w) (:uri w))))

(defn- handle-fetch-response!
  "Handle a fetch response from the server, updating the widget's data."
  [data]
  (let [{:keys [widget-id success error]} data
        wid (keyword (str (name widget-id)))]
    (if success
      (do
       (swap! !widgets update wid assoc
              :data (:data data)
              :loading? false
              :error nil)
       (log/log! {:level :info :id ::fetch-success
                  :msg "Fetch response received"
                  :data {:widget-id wid
                         :type (:type (get @!widgets wid))
                         :uri (:uri (get @!widgets wid))}})
        ;; Update WinBox title with contextual info
       (when-let [wb (get @!winbox-instances wid)]
                 (let [w (get @!widgets wid)]
                   (.setTitle wb (type->title (:type w) (:uri w)))))
        ;; Auto-fit to content after Reagent re-renders
       (js/setTimeout
        (fn []
          (when-let [wb (get @!winbox-instances wid)]
                    (fit-to-content! wid wb)))
        100))
      (do
       (swap! !widgets update wid assoc
              :loading? false
              :error (or error "Unknown error"))
       (log/log! {:level :error :id ::fetch-error
                  :msg "Fetch failed"
                  :data {:widget-id wid :error (or error "Unknown error")}})))))

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

(defn- build-breadcrumb-segments
  "Build breadcrumb segments from a parsed URI.
   Returns a vec of {:label :uri :level} maps.
   Parent segments have navigable :uri, the deepest has nil."
  [parsed]
  (let [project (:uri/project parsed)
        ns-name (:uri/namespace parsed)
        sym-name (:uri/symbol parsed)
        proj-uri (uri/with-query (uri/project-uri parsed) {"view" "ns-list"})
        ns-uri (when ns-name
                 (uri/with-query (uri/namespace-uri parsed) {"view" "symbol-list"}))]
    (cond-> []
            project  (conj {:label project
                            :uri   (when (or ns-name sym-name) proj-uri)
                            :level :project})
            ns-name  (conj {:label ns-name
                            :uri   (when sym-name ns-uri)
                            :level :namespace})
            sym-name (conj {:label sym-name
                            :uri   nil
                            :level :symbol}))))

(defn- uri-breadcrumb
  "Render a collapsible vertical breadcrumb from a URI string.
   Collapsed (default): shows chevron + deepest segment.
   Expanded (click): shows all segments vertically, parents clickable."
  [widget-id uri-string]
  (when uri-string
    (let [parsed (uri/parse (uri/base-uri uri-string))
          segments (build-breadcrumb-segments parsed)
          expanded? (contains? @!breadcrumb-expanded widget-id)
          toggle! #(swap! !breadcrumb-expanded
                          (fn [s] (if (contains? s widget-id)
                                    (disj s widget-id)
                                    (conj s widget-id))))
          deepest (last segments)]
      (when (seq segments)
        [:div.widget-breadcrumb {:class (when expanded? "expanded")}
         (if expanded?
           ;; Expanded: vertical list with indentation
           [:div.breadcrumb-vertical
            [:div.breadcrumb-row {:style {:cursor "pointer"}
                                  :on-click toggle!}
             [:span.breadcrumb-chevron "\u25BC"]]
            (doall
             (map-indexed
              (fn [idx {:keys [label uri]}]
                (let [current? (nil? uri)]
                  ^{:key idx}
                  [:div.breadcrumb-row
                   {:class (if current? "breadcrumb-current" "breadcrumb-parent")
                    :style {:padding-left (str (* idx 0.75) "rem")}
                    :on-click (when uri
                                (fn [e]
                                  (.stopPropagation e)
                                  (open-widget! {:uri uri})))}
                   [:span.breadcrumb-segment label]]))
              segments))]
           ;; Collapsed: chevron + deepest label
           [:div.breadcrumb-collapsed {:on-click toggle!}
            [:span.breadcrumb-chevron "\u25B6"]
            [:span.breadcrumb-segment (:label deepest)]])]))))

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
;; Content Components (body only — no header, used inside WinBox)
;; =============================================================================

(defn- project-list-content
  "Content body for project list widget (no header)."
  [widget-id widget]
  (let [projects (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (filter #(matches-filter? (or (:uri/project %) (:uri/string %))
                                           filter-text)
                         projects)]
    [:<>
     [widget-filter-input widget-id "Filter projects..."]
     [:div.list-container
      (doall
       (for [project filtered]
            ^{:key (:uri/string project)}
            [:div.list-item
             {:on-click (fn []
                          (log/log! {:level :info :id ::project-clicked
                                     :msg "Project selected"
                                     :data {:project (:uri/project project)
                                            :uri (:uri/string project)}})
                          (open-widget! {:uri (uri/with-query (:uri/string project)
                                                              {"view" "ns-list"})}))}
             [:span.project-name (or (:uri/project project) (:uri/string project))]]))]
     [:div.widget-footer
      [:span (str (count filtered) " projects")]]]))

(defn- ns-list-content
  "Content body for namespace list widget (no header)."
  [widget-id widget]
  (let [namespaces (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (filter #(matches-filter? (:ns/name %) filter-text) namespaces)]
    [:<>
     [widget-filter-input widget-id "Filter namespaces..."]
     [:div.list-container
      (doall
       (for [ns-entity filtered]
            ^{:key (:uri/string ns-entity)}
            [:div.list-item
             {:on-click (fn []
                          (log/log! {:level :info :id ::namespace-clicked
                                     :msg "Namespace selected"
                                     :data {:namespace (:ns/name ns-entity)
                                            :uri (:uri/string ns-entity)}})
                          (open-widget! {:uri (uri/with-query (:uri/string ns-entity)
                                                              {"view" "symbol-list"})}))}
             [:span.ns-name (:ns/name ns-entity)]
             (when (> (count (or (:ns/files ns-entity) [])) 1)
               [:span.file-count-badge
                (str "(" (count (:ns/files ns-entity)) " files)")])]))]
     [:div.widget-footer
      [:span (str (count filtered) " namespaces")]]]))

(defn- symbol-list-content
  "Content body for symbol list widget (no header)."
  [widget-id widget]
  (let [symbols (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (->> symbols
                      (filter #(matches-filter? (:symbol/name %) filter-text))
                      (sort-by (juxt :symbol/type :symbol/name)))]
    [:<>
     [widget-filter-input widget-id "Filter symbols..."]
     [:div.list-container
      (doall
       (for [sym filtered]
            ^{:key (:uri/string sym)}
            [:div.list-item
             {:class (when-let [kind (:symbol/type sym)] (name kind))
              :on-click (fn []
                          (log/log! {:level :info :id ::symbol-clicked
                                     :msg "Symbol selected"
                                     :data {:symbol (:symbol/name sym)
                                            :type (:symbol/type sym)
                                            :uri (:uri/string sym)}})
                          (open-widget! {:uri (uri/with-query (:uri/string sym)
                                                              {"view" "source"})}))}
             [:span.symbol-name (:symbol/name sym)]
             (when-let [kind (:symbol/type sym)]
                       [:span.symbol-kind (name kind)])]))]
     [:div.widget-footer
      [:span (str (count filtered) " symbols")]]]))

(defn- source-content
  "Content body for source view widget (no header)."
  [_widget-id widget]
  (let [source (:data widget)]
    (if source
      [:div.source-view
       [cm6/editor {:value (:content source)
                    :language :clojure
                    :read-only true}]
       [:div.source-info
        [:span (str (:file source) " lines " (:start-line source) "-" (:end-line source))]]]
      [:div.empty-message "No source available"])))

(defn- doc-content
  "Content body for doc view widget (no header)."
  [_widget-id widget]
  (let [sym-data (:data widget)]
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
      [:div.empty-message "No documentation available"])))

(defn- aliases-content
  "Content body for aliases view widget (no header)."
  [widget-id widget]
  (let [aliases (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (if (str/blank? filter-text)
                   aliases
                   (filter #(or (matches-filter? (:alias/name %) filter-text)
                                (matches-filter? (:alias/to-ns %) filter-text))
                           aliases))]
    [:<>
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

(defn- refers-content
  "Content body for refers view widget (no header)."
  [widget-id widget]
  (let [refers (or (:data widget) [])
        filter-text (or (:filter widget) "")
        filtered (if (str/blank? filter-text)
                   refers
                   (filter #(or (matches-filter? (:refer/symbol %) filter-text)
                                (matches-filter? (:refer/from-ns-source %) filter-text))
                           refers))]
    [:<>
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

(defn- deps-content
  "Content body for deps view widget (no header)."
  [_widget-id _widget]
  [:div.deps-view
   [:div.placeholder "Dependencies view coming in next iteration"]
   [:div.hint "This will show symbols that this symbol calls/uses"]])

(defn- callers-content
  "Content body for callers view widget (no header)."
  [_widget-id _widget]
  [:div.callers-view
   [:div.placeholder "Callers view coming in next iteration"]
   [:div.hint "This will show symbols that call/use this symbol"]])

;; =============================================================================
;; Widget Content Renderer
;; =============================================================================

(defn- render-widget-content
  "Render the content body for a widget based on its type (no header)."
  [widget-id widget]
  (case (:type widget)
    :project-list [project-list-content widget-id widget]
    :ns-list [ns-list-content widget-id widget]
    :symbol-list [symbol-list-content widget-id widget]
    :source [source-content widget-id widget]
    :doc [doc-content widget-id widget]
    :aliases [aliases-content widget-id widget]
    :refers [refers-content widget-id widget]
    :deps [deps-content widget-id widget]
    :callers [callers-content widget-id widget]
    [:div "Unknown widget type: " (str (:type widget))]))

;; =============================================================================
;; WinBox Host Component
;; =============================================================================

(defn- winbox-body-component
  "Reagent component rendered into a WinBox body via rdom/render.
   Reads widget state reactively so re-renders when data changes."
  [widget-id]
  (let [widget (get @!widgets widget-id)]
    [:div.winbox-widget-body
     [uri-breadcrumb widget-id (:uri widget)]
     (cond
       (:loading? widget)
       [:div.loading-overlay [:div.spinner]]

       (:error widget)
       [:div.error-banner [:span (:error widget)]]

       :else
       [render-widget-content widget-id widget])]))

(defn- measure-content-size
  "Measure ideal content size for a WinBox widget body.
   Returns {:w pixels :h pixels} representing ideal window dimensions.
   For lists: tall enough for all items. For CM6: fits all source lines."
  [wb]
  (let [body (.-body wb)
        header-h 35
        widget-body (.querySelector body ".winbox-widget-body")
        list-container (some-> widget-body (.querySelector ".list-container"))
        cm-editor (some-> widget-body (.querySelector ".cm-editor"))
        breadcrumb (.querySelector widget-body ".widget-breadcrumb")
        filter-input (.querySelector widget-body ".filter-input")
        footer (.querySelector widget-body ".widget-footer")
        ;; Measure non-content chrome heights
        bc-h (if breadcrumb (.-offsetHeight breadcrumb) 0)
        fi-h (if filter-input (+ (.-offsetHeight filter-input) 8) 0)
        ft-h (if footer (.-offsetHeight footer) 0)
        chrome-h (+ header-h bc-h fi-h ft-h 12)
        ;; Measure content dimensions
        [content-w content-h]
        (cond
          list-container
          ;; Lists: measure unwrapped width of widest item + scrollbar
          ;; Height: sum item heights (immune to container size feedback loop)
          (let [items (array-seq (.querySelectorAll list-container ".list-item"))
                _ (doseq [item items]
                         (set! (.-whiteSpace (.-style item)) "nowrap")
                         (set! (.-width (.-style item)) "max-content"))
                max-w (reduce (fn [m item] (max m (.-offsetWidth item)))
                              0 items)
                _ (doseq [item items]
                         (set! (.-whiteSpace (.-style item)) "")
                         (set! (.-width (.-style item)) ""))
                scrollbar-w (- (.-offsetWidth list-container)
                               (.-clientWidth list-container))
                h (reduce (fn [total item] (+ total (.-offsetHeight item)))
                          0 items)]
            [(+ max-w scrollbar-w) h])

          cm-editor
          ;; CM6: measure line widths with nowrap, scroller height with shrink
          (let [cm-scroller (.querySelector cm-editor ".cm-scroller")
                cm-content (.querySelector cm-editor ".cm-content")
                cm-gutters (.querySelector cm-editor ".cm-gutters")
                gutter-w (if cm-gutters (.-offsetWidth cm-gutters) 0)
                lines (array-seq (.querySelectorAll cm-content ".cm-line"))
                _ (doseq [ln lines]
                         (set! (.-whiteSpace (.-style ln)) "nowrap")
                         (set! (.-width (.-style ln)) "max-content")
                         (set! (.-display (.-style ln)) "inline-block"))
                max-line-w (reduce (fn [m ln] (max m (.-offsetWidth ln)))
                                   0 lines)
                _ (doseq [ln lines]
                         (set! (.-whiteSpace (.-style ln)) "")
                         (set! (.-width (.-style ln)) "")
                         (set! (.-display (.-style ln)) ""))
                saved-h (.-height (.-style cm-scroller))
                _ (set! (.-height (.-style cm-scroller)) "1px")
                scroller-h (.-scrollHeight cm-scroller)
                _ (set! (.-height (.-style cm-scroller)) saved-h)
                source-info (.querySelector widget-body ".source-info")
                si-h (if source-info (+ (.-offsetHeight source-info) 4) 0)]
            [(+ max-line-w gutter-w 20)
             (+ scroller-h si-h)])

          :else
          ;; Fallback: shrink container to measure true content height
          (let [saved-h (.-height (.-style widget-body))
                _ (set! (.-height (.-style widget-body)) "1px")
                w (.-scrollWidth widget-body)
                h (.-scrollHeight widget-body)
                _ (set! (.-height (.-style widget-body)) saved-h)]
            [w h]))
        ;; Total with chrome, capped at viewport
        ideal-w content-w
        ideal-h (+ content-h chrome-h)
        max-w (- (.-innerWidth js/window) 40)
        max-h (- (.-innerHeight js/window) 40)]
    {:w (min ideal-w max-w)
     :h (min ideal-h max-h)}))

(defn- fit-to-content!
  "Resize a WinBox widget to fit its content.
   Keeps the upper-left corner in place, shifting only if needed to stay in viewport."
  [_widget-id wb]
  (let [body (.-body wb)
        widget-body (.querySelector body ".winbox-widget-body")
        has-content? (and widget-body
                          (or (.querySelector widget-body ".list-container")
                              (.querySelector widget-body ".cm-editor")
                              (.querySelector widget-body ".doc-view")))]
    (when has-content?
      (let [{:keys [w h]} (measure-content-size wb)
            cur-x (.-x wb)
            cur-y (.-y wb)
            vw (.-innerWidth js/window)
            vh (.-innerHeight js/window)
            new-x (if (> (+ cur-x w) vw) (max 0 (- vw w)) cur-x)
            new-y (if (> (+ cur-y h) vh) (max 0 (- vh h)) cur-y)]
        (.resize wb w h)
        (when (or (not= new-x cur-x) (not= new-y cur-y))
          (.move wb new-x new-y))))))

(defn- create-winbox!
  "Create a WinBox floating window for a widget and render Reagent content
   into its body. Returns the WinBox instance."
  [widget-id]
  (let [!closing (atom false)
        widget (get @!widgets widget-id)
        wtype (:type widget)
        uri (:uri widget)
        defaults (winbox-defaults wtype)
        title (type->title wtype uri)
        project-list? (= wtype :project-list)
        project (when uri (:uri/project (uri/parse (uri/base-uri uri))))
        bg-color (cond
                   project-list? project-list-color
                   project (project-color project)
                   :else nil)
        wb-opts (cond-> {:title title
                         :width (:width defaults)
                         :height (:height defaults)
                         :x (:x defaults)
                         :y (:y defaults)
                         :class (if project-list?
                                  ["no-full" "no-close"]
                                  ["no-full"])
                         :onfocus (fn []
                                    (reset! !focused-widget widget-id)
                                    (when-let [u (:uri (get @!widgets widget-id))]
                                              (set! (.-hash js/window.location) u)))
                         :onclose (fn []
                                    (when-not @!closing
                                      (reset! !closing true)
                                      (close-widget! widget-id))
                                    js/undefined)}
                        bg-color (assoc :background bg-color))
        wb (js/WinBox. (clj->js wb-opts))]
    (swap! !winbox-instances assoc widget-id wb)
    (.focus wb)
    (log/log! {:level :info :id ::winbox-created
               :msg "WinBox window created"
               :data {:widget-id widget-id :type wtype :title title}})
    ;; Override maximize button: intercept click in capture phase
    ;; before WinBox's click handler runs, so we get fit-to-content instead.
    (when-let [max-btn (.querySelector (.-parentNode (.-body wb)) ".wb-max")]
              (.addEventListener max-btn "click"
                                 (fn [e]
                                   (.stopImmediatePropagation e)
                                   (.preventDefault e)
                                   (fit-to-content! widget-id wb))
                                 true))
    ;; Render Reagent component into WinBox body
    (let [body (.-body wb)]
      (rdom/render [winbox-body-component widget-id] body))
    wb))

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
     (when focused
       [:button.toolbar-btn
        {:on-click #(refresh-widget! focused)
         :title "Refresh focused widget"}
        "Refresh"])
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
  "Workspace background area. WinBox windows float above this.
   Shows empty message when no widgets are open."
  []
  (let [widgets @!widgets]
    [:div.widgets-container
     (when-not (seq widgets)
       [:div.empty-message
        {:style {:padding "2rem" :text-align "center"}}
        "No widgets open. Click '+ Projects' to start."])]))

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
              (log/log! {:level :info :id ::restoring-widget-chain
                         :msg "Restoring widget chain from URI"
                         :data {:uri uri-string}})
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
                           (log/log! {:level :info :id ::hash-navigation
                                      :msg "Hash navigation"
                                      :data {:uri hash}})
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
  (js/console.log "[code-browser-v2] Mounted (widget architecture)")
  (log/log! {:level :info :id ::mounted :msg "Code Browser v2 mounted"}))

(defn unmount!
  "Unmount code browser v2. Closes all WinBox windows and resets state."
  []
  ;; Close all WinBox instances
  (doseq [[_wid wb] @!winbox-instances]
         (.close wb))
  (reset! !winbox-instances {})
  (reset! !cascade-offset 0)
  (reset! !widgets {})
  (reset! !focused-widget nil)
  (reset! !breadcrumb-expanded #{})
  (reset! !project-color-map {})
  (js/console.log "[code-browser-v2] Unmounted")
  (log/log! {:level :info :id ::unmounted :msg "Code Browser v2 unmounted"}))
