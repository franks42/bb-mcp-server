(ns pane-browser
    "Pharo-style 4-pane code browser with inspector panel.

   Layout:
     Projects | Namespaces | Types | Symbols    (4 coordinated columns)
     =========================================
     [Source] [Doc] [Value] [Deps] [Aliases]    (tabbed inspector)

   Each browser instance is a single WinBox window containing the full layout.
   Selecting in one pane filters the next (cascade pattern).
   Multiple independent browser instances can coexist.

   Usage (loaded via ui_loader.cljs):
     (require '[pane-browser :as pb])
     (pb/mount!)"
    (:require [reagent.core :as r]
              [reagent.dom :as rdom]
              [clojure.string :as str]
              [sente-lite.client-scittle :as client]
              [code-browser.bootstrap :as bootstrap]
              [code-browser.uri :as uri]
              [scittle-cm6 :as cm6]
              [taoensso.trove :as log]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private default-pane-widths
     "Fractional column widths: [projects namespaces types symbols]"
     [0.18 0.22 0.15 0.45])

(def ^:private default-inspector-height
     "Fraction of total height for inspector panel."
     0.45)

(def ^:private min-pane-frac 0.05)
(def ^:private min-inspector-frac 0.1)
(def ^:private poll-interval-ms 3000)

(def ^:private project-colors
     ["#1e88e5" "#43a047" "#e53935" "#8e24aa"
      "#f4511e" "#00acc1" "#6d4c41" "#546e7a"])

(def ^:private tab-labels
     {:source "Source" :doc "Doc" :var-value "Value"
      :aliases "Aliases" :refers "Refers"
      :deps "Deps" :callers "Callers"})

;; =============================================================================
;; State
;; =============================================================================

(defonce ^{:doc "Map of browser-id -> r/atom of browser state."}
 !browsers (r/atom {}))

(defonce ^{:doc "Counter for generating browser IDs."}
 !browser-counter (atom 0))

(defonce ^{:doc "Map of browser-id -> WinBox JS instance."}
 !winbox-instances (atom {}))

(defonce ^{:doc "Currently focused browser ID."}
 !focused-browser (r/atom nil))

(defonce ^{:doc "Polling interval handle."}
 !poll-interval (atom nil))

(defonce ^{:doc "Project name -> color assignment."}
 !project-color-map (atom {}))

(defonce ^{:doc "Add-project input state."}
 !add-project-state (r/atom {:path "" :error nil :loading? false}))

(defonce ^{:doc "Whether global drop handler is installed."}
 !drop-handler-installed (atom false))

;; =============================================================================
;; Browser State Factory
;; =============================================================================

(defn- make-browser-state
  "Create initial state for a new browser instance."
  [id]
  {:id id
   :selections {:project nil :namespace nil :sym-type nil :symbol nil}
   :data {:projects [] :namespaces [] :symbols []
          :inspector {}}
   :pane-states {:projects :loading :namespaces :idle
                 :symbols :idle :inspector :idle}
   :filters {:projects "" :namespaces "" :sym-types "" :symbols ""}
   :active-tab :source
   :pane-widths default-pane-widths
   :inspector-height default-inspector-height})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- get-browser-atom
  "Get the r/atom for a browser instance."
  [browser-id]
  (get @!browsers browser-id))

(defn project-color
  "Get a consistent color for a project name."
  [project-name]
  (or (get @!project-color-map project-name)
      (let [idx (count @!project-color-map)
            color (nth project-colors (mod idx (count project-colors)))]
        (swap! !project-color-map assoc project-name color)
        color)))

(defn- matches-filter?
  "Case-insensitive substring match."
  [text pattern]
  (or (str/blank? pattern)
      (str/includes? (str/lower-case (str text))
                     (str/lower-case pattern))))

(defn- available-tabs
  "Compute which inspector tabs are available given current selections."
  [state]
  (let [{:keys [project namespace symbol]} (:selections state)
        is-nrepl? (and project (str/starts-with? project "nrepl://"))]
    (cond
      symbol
      (cond-> [:source :doc]
              is-nrepl? (conj :var-value)
              true (into [:aliases :refers :deps :callers]))

      namespace
      [:aliases :refers]

      :else [])))

(defn- ensure-valid-tab
  "If active-tab is not in the available set, switch to first available."
  [state]
  (let [tabs (available-tabs state)]
    (if (and (seq tabs)
             (not (some #{(:active-tab state)} tabs)))
      (assoc state :active-tab (first tabs))
      state)))

;; =============================================================================
;; Request ID Encoding
;; =============================================================================

(defn- make-request-id
  "Encode browser-id + target into a widget-id keyword."
  [browser-id target]
  (keyword (str (name browser-id) "--" (name target))))

(defn- parse-request-id
  "Decode widget-id back to {:browser-id :target}."
  [widget-id]
  (let [s (name widget-id)
        idx (str/index-of s "--")]
    (when idx
      {:browser-id (keyword (subs s 0 idx))
       :target (keyword (subs s (+ idx 2)))})))

;; =============================================================================
;; Event Dispatch
;; =============================================================================

(defn- send-event!
  "Send event to server via sente-lite."
  [event-id data]
  (when-let [client-id @bootstrap/!client-id]
            (client/send! client-id [event-id data])))

(defn- fetch-for-pane!
  "Send a fetch request for a specific target in a browser instance."
  [browser-id target uri property]
  (let [request-id (make-request-id browser-id target)]
    (send-event! :code-browser-v2/fetch
                 {:uri uri :property property :widget-id request-id})))

;; =============================================================================
;; Selection Cascade
;; =============================================================================

(declare fetch-inspector-tab!)

(defn- select-project!
  "Select a project. Clears namespace/type/symbol, fetches namespaces."
  [browser-id project-uri]
  (when-let [!b (get-browser-atom browser-id)]
            (swap! !b (fn [s]
                        (-> s
                            (assoc-in [:selections :project] project-uri)
                            (assoc-in [:selections :namespace] nil)
                            (assoc-in [:selections :sym-type] nil)
                            (assoc-in [:selections :symbol] nil)
                            (assoc-in [:data :namespaces] [])
                            (assoc-in [:data :symbols] [])
                            (assoc-in [:data :inspector] {})
                            (assoc-in [:pane-states :namespaces] :loading)
                            (assoc-in [:pane-states :symbols] :idle)
                            (assoc-in [:pane-states :inspector] :idle)
                            (assoc :active-tab :source)
                            ensure-valid-tab)))
            (fetch-for-pane! browser-id :namespaces project-uri :ns-list)
            ;; Update WinBox title and color
            (when-let [wb (get @!winbox-instances browser-id)]
                      (let [parsed (uri/parse project-uri)
                            pname (or (:uri/project parsed) "Browser")]
                        (.setTitle wb (str "Code Browser \u2014 " pname))
                        (.setBackground wb (project-color pname))))))

(defn- select-namespace!
  "Select a namespace. Clears type/symbol, fetches symbols."
  [browser-id ns-uri]
  (when-let [!b (get-browser-atom browser-id)]
            (swap! !b (fn [s]
                        (-> s
                            (assoc-in [:selections :namespace] ns-uri)
                            (assoc-in [:selections :sym-type] nil)
                            (assoc-in [:selections :symbol] nil)
                            (assoc-in [:data :symbols] [])
                            (assoc-in [:data :inspector] {})
                            (assoc-in [:pane-states :symbols] :loading)
                            (assoc-in [:pane-states :inspector] :idle)
                            ensure-valid-tab)))
            (fetch-for-pane! browser-id :symbols ns-uri :symbol-list)))

(defn- select-sym-type!
  "Select a symbol type filter. Client-side only, no fetch."
  [browser-id sym-type]
  (when-let [!b (get-browser-atom browser-id)]
            (swap! !b (fn [s]
                        (-> s
                            (assoc-in [:selections :sym-type] sym-type)
                            (assoc-in [:selections :symbol] nil)
                            (assoc-in [:data :inspector] {})
                            (assoc-in [:pane-states :inspector] :idle)
                            ensure-valid-tab)))))

(defn- select-symbol!
  "Select a symbol. Fetches inspector data for the active tab."
  [browser-id symbol-uri]
  (when-let [!b (get-browser-atom browser-id)]
            (swap! !b (fn [s]
                        (-> s
                            (assoc-in [:selections :symbol] symbol-uri)
                            (assoc-in [:data :inspector] {})
                            (assoc-in [:pane-states :inspector] :loading)
                            (assoc :active-tab :source)
                            ensure-valid-tab)))
            (fetch-inspector-tab! browser-id)))

(defn- fetch-inspector-tab!
  "Fetch data for the browser's currently active inspector tab."
  ([browser-id]
   (when-let [!b (get-browser-atom browser-id)]
             (let [state @!b
                   tab (:active-tab state)
                   cached (get-in state [:data :inspector tab])]
               (when-not cached
                 (let [sel (:selections state)
                       ;; Aliases/refers use namespace URI, others use symbol URI
                       target-uri (case tab
                                    (:aliases :refers)
                                    (or (:namespace sel) (:symbol sel))
                                    ;; default: symbol-level
                                    (:symbol sel))]
                   (when target-uri
                     (let [base (uri/base-uri target-uri)]
                       (swap! !b assoc-in [:pane-states :inspector] :loading)
                       (fetch-for-pane! browser-id
                                        (keyword (str "tab-" (name tab)))
                                        (uri/with-query base {"view" (name tab)})
                                        nil)))))))))

(defn- switch-tab!
  "Switch the active inspector tab. Fetches data if not cached."
  [browser-id tab-key]
  (when-let [!b (get-browser-atom browser-id)]
            (swap! !b assoc :active-tab tab-key)
            (let [cached (get-in @!b [:data :inspector tab-key])]
              (when-not cached
                (fetch-inspector-tab! browser-id)))))

;; =============================================================================
;; Derived Data
;; =============================================================================

(defn- derive-sym-types
  "Derive symbol type groups with counts from symbol data."
  [symbols]
  (->> symbols
       (keep :symbol/type)
       frequencies
       (sort-by (comp str key))
       (mapv (fn [[t cnt]] {:type t :count cnt}))))

(defn- filter-symbols
  "Filter symbols by type and text."
  [symbols sym-type text-filter]
  (->> symbols
       (filter (fn [s]
                 (and (or (nil? sym-type)
                          (= (:symbol/type s) sym-type))
                      (matches-filter? (:symbol/name s) text-filter))))
       (sort-by (juxt :symbol/type :symbol/name))
       vec))

;; =============================================================================
;; Event Response Handler
;; =============================================================================

(defn- handle-fetch-response!
  "Route a fetch response to the correct browser pane."
  [data]
  (let [{:keys [widget-id success error]} data
        parsed (parse-request-id (keyword (str (name widget-id))))]
    (when parsed
      (let [{:keys [browser-id target]} parsed]
        (when-let [!b (get-browser-atom browser-id)]
                  (if success
                    (let [target-name (name target)]
                      (cond
                        ;; Pane data fetches
                        (= target :projects)
                        (swap! !b (fn [s]
                                    (-> s
                                        (assoc-in [:data :projects] (:data data))
                                        (assoc-in [:pane-states :projects] :ready))))

                        (= target :namespaces)
                        (swap! !b (fn [s]
                                    (-> s
                                        (assoc-in [:data :namespaces] (:data data))
                                        (assoc-in [:pane-states :namespaces] :ready))))

                        (= target :symbols)
                        (swap! !b (fn [s]
                                    (-> s
                                        (assoc-in [:data :symbols] (:data data))
                                        (assoc-in [:pane-states :symbols] :ready))))

                        ;; Inspector tab fetches (target starts with "tab-")
                        (str/starts-with? target-name "tab-")
                        (let [tab-key (keyword (subs target-name 4))]
                          (swap! !b (fn [s]
                                      (-> s
                                          (assoc-in [:data :inspector tab-key]
                                                    (:data data))
                                          (assoc-in [:pane-states :inspector]
                                                    :ready)))))))
                    ;; Error
                    (log/log! {:level :error :id ::fetch-error
                               :msg "Pane fetch failed"
                               :data {:browser-id browser-id
                                      :target target
                                      :error error}})))))))

(defn- handle-ns-list-fingerprint-result!
  "Handle namespace list fingerprint change — refresh the namespaces pane."
  [data]
  (let [{:keys [widget-id changed?]} data
        parsed (parse-request-id (keyword (str (name widget-id))))]
    (when (and parsed changed?)
      (let [{:keys [browser-id]} parsed]
        (when-let [!b (get-browser-atom browser-id)]
                  (let [project-uri (get-in @!b [:selections :project])]
                    (when project-uri
                      (swap! !b assoc-in [:pane-states :namespaces] :loading)
                      (fetch-for-pane! browser-id :namespaces
                                       project-uri :ns-list))))))))

(defn- handle-symbol-list-fingerprint-result!
  "Handle symbol list fingerprint change — refresh the symbols pane."
  [data]
  (let [{:keys [widget-id changed?]} data
        parsed (parse-request-id (keyword (str (name widget-id))))]
    (when (and parsed changed?)
      (let [{:keys [browser-id]} parsed]
        (when-let [!b (get-browser-atom browser-id)]
                  (let [ns-uri (get-in @!b [:selections :namespace])]
                    (when ns-uri
                      (swap! !b assoc-in [:pane-states :symbols] :loading)
                      (fetch-for-pane! browser-id :symbols
                                       ns-uri :symbol-list))))))))

(defn- handle-var-fingerprint-result!
  "Handle var value fingerprint change — update inspector cache."
  [data]
  (let [{:keys [widget-id changed?]} data
        parsed (parse-request-id (keyword (str (name widget-id))))]
    (when (and parsed changed?)
      (let [{:keys [browser-id]} parsed]
        (when-let [!b (get-browser-atom browser-id)]
                  (if-let [new-data (:data data)]
                    ;; Server sent refreshed data — update cache directly
                          (swap! !b assoc-in [:data :inspector :var-value] new-data)
                    ;; No data — do full refresh
                          (do
                           (swap! !b update-in [:data :inspector] dissoc :var-value)
                           (when (= (:active-tab @!b) :var-value)
                             (fetch-inspector-tab! browser-id)))))))))

(defn- handle-invalidate!
  "Handle server-push invalidation — refresh affected browsers."
  [data]
  (let [{:keys [project]} data]
    (doseq [[browser-id !b] @!browsers]
           (let [state @!b
                 sel-project (get-in state [:selections :project])]
             (when sel-project
               (let [parsed (uri/parse sel-project)]
                 (when (= (:uri/project parsed) project)
                   ;; Refresh namespaces
                   (swap! !b assoc-in [:pane-states :namespaces] :loading)
                   (fetch-for-pane! browser-id :namespaces
                                    sel-project :ns-list))))
             ;; Also refresh project list
             (swap! !b assoc-in [:pane-states :projects] :loading)
             (fetch-for-pane! browser-id :projects nil :project-list)))))

(defn- handle-add-project-result!
  "Handle add-project response from server."
  [data]
  (if (:success data)
    (do
     (swap! !add-project-state assoc :path "" :loading? false :error nil)
     ;; Refresh all project lists
     (doseq [[browser-id !b] @!browsers]
            (swap! !b assoc-in [:pane-states :projects] :loading)
            (fetch-for-pane! browser-id :projects nil :project-list)))
    (swap! !add-project-state assoc :loading? false
           :error (:error data))))

;; Register event handler for all code-browser-v2 events
(bootstrap/register-event-handler!
 :code-browser-v2
 (fn [[event-id data]]
   (case event-id
     :code-browser-v2/fetch-response
     (handle-fetch-response! data)

     :code-browser-v2/invalidate
     (handle-invalidate! data)

     :code-browser-v2/add-project-result
     (handle-add-project-result! data)

     :code-browser-v2/var-fingerprint-result
     (handle-var-fingerprint-result! data)

     :code-browser-v2/ns-list-fingerprint-result
     (handle-ns-list-fingerprint-result! data)

     :code-browser-v2/symbol-list-fingerprint-result
     (handle-symbol-list-fingerprint-result! data)

     ;; Ignore other events
     nil)))

;; =============================================================================
;; Drag-and-Drop for Add Project
;; =============================================================================

(defn- extract-path-from-drop
  "Extract a filesystem path from a drag-and-drop event."
  [e]
  (let [dt (.-dataTransfer e)
        uri-list (.getData dt "text/uri-list")
        text (.getData dt "text/plain")]
    (cond
      (and (not (str/blank? uri-list))
           (str/starts-with? (str/trim uri-list) "file://"))
      (-> uri-list str/trim
          (str/split #"\r?\n") first
          (str/replace #"^file://" "")
          js/decodeURIComponent)

      (not (str/blank? text))
      (str/trim text)

      :else nil)))

(defn- install-global-drop-handler!
  "Install document-level drag/drop handlers for add-project."
  []
  (when-not @!drop-handler-installed
    (.addEventListener js/document "dragover"
                       (fn [e]
                         (.preventDefault e)
                         (set! (.-dropEffect (.-dataTransfer e)) "copy"))
                       true)
    (.addEventListener js/document "drop"
                       (fn [e]
                         (.preventDefault e)
                         (.stopPropagation e)
                         (when-let [p (extract-path-from-drop e)]
                                   (swap! !add-project-state assoc
                                          :path p :error nil)))
                       true)
    (reset! !drop-handler-installed true)))

;; =============================================================================
;; Divider Drag Handlers
;; =============================================================================

(defn- start-column-drag!
  "Begin dragging a vertical divider between columns.
   divider-index: 0=between col 0-1, 1=between col 1-2, 2=between col 2-3."
  [browser-id divider-index e]
  (.preventDefault e)
  (let [start-x (.-clientX e)
        !b (get-browser-atom browser-id)
        start-widths (:pane-widths @!b)
        container (.. e -target -parentNode)
        container-w (.-offsetWidth container)
        move-fn (fn [me]
                  (let [delta-frac (/ (- (.-clientX me) start-x) container-w)
                        left (nth start-widths divider-index)
                        right (nth start-widths (inc divider-index))
                        new-left (+ left delta-frac)
                        new-right (- right delta-frac)]
                    (when (and (>= new-left min-pane-frac)
                               (>= new-right min-pane-frac))
                      (swap! !b assoc :pane-widths
                             (-> start-widths
                                 (assoc divider-index new-left)
                                 (assoc (inc divider-index) new-right))))))
        !up (atom nil)]
    (reset! !up (fn [_ue]
                  (.removeEventListener js/document "mousemove" move-fn)
                  (.removeEventListener js/document "mouseup" @!up)))
    (.addEventListener js/document "mousemove" move-fn)
    (.addEventListener js/document "mouseup" @!up)))

(defn- start-row-drag!
  "Begin dragging the horizontal divider between columns and inspector."
  [browser-id e]
  (.preventDefault e)
  (let [start-y (.-clientY e)
        !b (get-browser-atom browser-id)
        start-height (:inspector-height @!b)
        container (.. e -target -parentNode)
        container-h (.-offsetHeight container)
        move-fn (fn [me]
                  (let [delta-frac (/ (- start-y (.-clientY me)) container-h)
                        new-height (+ start-height delta-frac)]
                    (when (and (>= new-height min-inspector-frac)
                               (<= new-height 0.85))
                      (swap! !b assoc :inspector-height new-height))))
        !up (atom nil)]
    (reset! !up (fn [_ue]
                  (.removeEventListener js/document "mousemove" move-fn)
                  (.removeEventListener js/document "mouseup" @!up)))
    (.addEventListener js/document "mousemove" move-fn)
    (.addEventListener js/document "mouseup" @!up)))

;; =============================================================================
;; Pane Components
;; =============================================================================

(defn- pane-loading
  "Loading indicator for a pane."
  []
  [:div.pane-loading
   [:div.mini-spinner]
   [:span "Loading..."]])

(defn- pane-idle-hint
  "Hint message for an idle pane."
  [text]
  [:div.pane-idle {:style {:padding "1rem" :color "#999"
                           :font-style "italic" :font-size "12px"}}
   text])

(defn- submit-add-project!
  "Submit an add-project request to the server."
  []
  (let [{:keys [path]} @!add-project-state]
    (when-not (str/blank? path)
      (swap! !add-project-state assoc :loading? true :error nil)
      (send-event! :code-browser-v2/add-project {:path (str/trim path)}))))

(defn- projects-pane
  "Projects column: list of projects with filter + add-project input."
  [browser-id !b]
  (let [state @!b
        projects (get-in state [:data :projects])
        pane-state (get-in state [:pane-states :projects])
        filter-text (get-in state [:filters :projects])
        selected (get-in state [:selections :project])
        filtered (filter #(matches-filter?
                           (or (:uri/project %) (:uri/string %))
                           filter-text)
                         projects)]
    [:div.pane-column
     {:style {:flex (str (nth (:pane-widths state) 0))}}
     [:div.pane-column-header "Projects"]
     [:input.pane-filter
      {:type "text" :placeholder "Filter..."
       :value filter-text
       :on-change #(swap! !b assoc-in [:filters :projects]
                          (-> % .-target .-value))}]
     (case pane-state
       :loading [pane-loading]
       [:div.pane-list
        (doall
         (for [p filtered]
              ^{:key (:uri/string p)}
              [:div.pane-item
               {:class (when (= (:uri/string p) selected) "selected")
                :on-click #(select-project! browser-id (:uri/string p))}
               (or (:uri/project p) (:uri/string p))]))])
     ;; Add project input
     (let [{:keys [path error loading?]} @!add-project-state]
       [:div.pane-add-project
        [:div {:style {:display "flex" :gap "2px" :padding "3px"}}
         [:input {:type "text" :placeholder "Add project path..."
                  :value path :disabled loading?
                  :style {:flex "1" :font-size "11px" :padding "2px 4px"
                          :border "1px solid #ccc" :border-radius "3px"
                          :font-family "'Fira Code', monospace"}
                  :on-change #(swap! !add-project-state assoc
                                     :path (-> % .-target .-value) :error nil)
                  :on-key-down #(when (= (.-key %) "Enter")
                                  (submit-add-project!))}]
         [:button {:disabled (or loading? (str/blank? path))
                   :style {:font-size "12px" :padding "2px 6px"
                           :border "1px solid #4CAF50" :border-radius "3px"
                           :background "#4CAF50" :color "white" :cursor "pointer"}
                   :on-click submit-add-project!}
          (if loading? "..." "+")]]
        (when error
          [:div {:style {:color "#d32f2f" :font-size "10px" :padding "2px 4px"}}
           error])])
     [:div.pane-footer (str (count filtered) " projects")]]))

(defn- namespaces-pane
  "Namespaces column: list of namespaces for selected project."
  [browser-id !b]
  (let [state @!b
        namespaces (get-in state [:data :namespaces])
        pane-state (get-in state [:pane-states :namespaces])
        filter-text (get-in state [:filters :namespaces])
        selected (get-in state [:selections :namespace])
        filtered (filter #(matches-filter? (:ns/name %) filter-text)
                         namespaces)]
    [:div.pane-column
     {:style {:flex (str (nth (:pane-widths state) 1))}}
     [:div.pane-column-header "Namespaces"]
     [:input.pane-filter
      {:type "text" :placeholder "Filter..."
       :value filter-text
       :on-change #(swap! !b assoc-in [:filters :namespaces]
                          (-> % .-target .-value))}]
     (case pane-state
       :loading [pane-loading]
       :idle [pane-idle-hint "Select a project"]
       [:div.pane-list
        (doall
         (for [ns-entity filtered]
              ^{:key (:uri/string ns-entity)}
              [:div.pane-item
               {:class (when (= (:uri/string ns-entity) selected) "selected")
                :on-click #(select-namespace! browser-id
                                              (:uri/string ns-entity))}
               (:ns/name ns-entity)
               (when (> (count (or (:ns/files ns-entity) [])) 1)
                 [:span {:style {:font-size "10px" :color "#888"
                                 :margin-left "4px"}}
                  (str "(" (count (:ns/files ns-entity)) ")")])]))])
     [:div.pane-footer (str (count filtered) " namespaces")]]))

(defn- sym-types-pane
  "Types column: groups symbols by :symbol/type with counts."
  [browser-id !b]
  (let [state @!b
        symbols (get-in state [:data :symbols])
        pane-state (get-in state [:pane-states :symbols])
        filter-text (get-in state [:filters :sym-types])
        selected-type (get-in state [:selections :sym-type])
        sym-types (derive-sym-types symbols)
        all-count (count symbols)
        filtered (filter #(matches-filter? (name (:type %)) filter-text)
                         sym-types)]
    [:div.pane-column
     {:style {:flex (str (nth (:pane-widths state) 2))}}
     [:div.pane-column-header "Types"]
     [:input.pane-filter
      {:type "text" :placeholder "Filter..."
       :value filter-text
       :on-change #(swap! !b assoc-in [:filters :sym-types]
                          (-> % .-target .-value))}]
     (case pane-state
       :loading [pane-loading]
       :idle [pane-idle-hint "Select a namespace"]
       [:div.pane-list
        ;; "All" entry
        [:div.pane-item
         {:class (when (nil? selected-type) "selected")
          :on-click #(select-sym-type! browser-id nil)}
         "All"
         [:span.type-count (str " [" all-count "]")]]
        (doall
         (for [{:keys [type count]} filtered]
              ^{:key (str type)}
              [:div.pane-item
               {:class (when (= type selected-type) "selected")
                :on-click #(select-sym-type! browser-id type)}
               (if type (name type) "unknown")
               [:span.type-count (str " [" count "]")]]))])
     [:div.pane-footer
      (str (count sym-types) " types")]]))

(defn- symbols-pane
  "Symbols column: list of symbols filtered by type and text."
  [browser-id !b]
  (let [state @!b
        symbols (get-in state [:data :symbols])
        pane-state (get-in state [:pane-states :symbols])
        sym-type (get-in state [:selections :sym-type])
        filter-text (get-in state [:filters :symbols])
        selected (get-in state [:selections :symbol])
        filtered (filter-symbols symbols sym-type filter-text)]
    [:div.pane-column
     {:style {:flex (str (nth (:pane-widths state) 3))}}
     [:div.pane-column-header "Symbols"]
     [:input.pane-filter
      {:type "text" :placeholder "Filter..."
       :value filter-text
       :on-change #(swap! !b assoc-in [:filters :symbols]
                          (-> % .-target .-value))}]
     (case pane-state
       :loading [pane-loading]
       :idle [pane-idle-hint "Select a namespace"]
       [:div.pane-list
        (doall
         (for [sym filtered]
              ^{:key (:uri/string sym)}
              [:div.pane-item
               {:class (when (= (:uri/string sym) selected) "selected")
                :on-click #(select-symbol! browser-id (:uri/string sym))}
               [:span (:symbol/name sym)]
               (when-let [kind (:symbol/type sym)]
                         [:span.sym-kind (name kind)])]))])
     [:div.pane-footer (str (count filtered) " symbols")]]))

;; =============================================================================
;; Inspector Content Components
;; =============================================================================

(defn- source-inspector
  "Render source code in CM6 editor."
  [data]
  (if data
    [:div.source-view
     [cm6/editor {:value (:content data)
                  :language :clojure
                  :read-only true}]
     (when (:file data)
       [:div.source-info
        [:span (str (:file data)
                    (when (:start-line data)
                      (str " lines " (:start-line data)
                           "-" (:end-line data))))]])]
    [:div.inspector-empty "No source available"]))

(defn- doc-inspector
  "Render symbol documentation."
  [data]
  (if data
    [:div {:style {:padding "0.75rem" :overflow "auto"}}
     [:div {:style {:display "flex" :align-items "center" :gap "0.5rem"
                    :margin-bottom "0.5rem"}}
      [:span {:style {:font-family "'Fira Code', monospace"
                      :font-weight 600 :font-size "14px"}}
       (:symbol/name data)]
      (when-let [kind (:symbol/type data)]
                [:span.symbol-kind (name kind)])]
     (when-let [arglists (:symbol/arglists data)]
               [:div {:style {:margin-bottom "0.5rem"
                              :font-family "'Fira Code', monospace"
                              :font-size "12px"}}
                [:strong "Args: "] [:code (pr-str arglists)]])
     (if-let [doc (:symbol/doc data)]
             [:pre.docstring doc]
             [:div {:style {:color "#888" :font-style "italic"}}
              "No documentation available"])]
    [:div.inspector-empty "No documentation available"]))

(defn- var-value-inspector
  "Render live var value display."
  [data]
  (if data
    [:div.var-value-view
     ;; Header: type + class
     [:div.value-header
      (when (:container-type data)
        [:span.atom-badge (str (:container-type data) " \u2192")])
      [:span.value-class-label (:value-class data)]
      (when (:count data)
        [:span.value-count-badge (str "(" (:count data) " items)")])
      (when (:truncated? data)
        [:span.truncated-badge "truncated"])]
     ;; Predicate badges
     (when (seq (:predicates data))
       [:div.predicate-badges
        (when (:is-service? data)
          [:span.pred-badge.pred-statechart "service"])
        (when (:is-store? data)
          [:span.pred-badge.pred-statechart "store"])
        (when (:is-statechart? data)
          [:span.pred-badge.pred-statechart "statechart"])
        (doall
         (map-indexed
          (fn [i pred]
            ^{:key i}
            [:span.pred-badge (str/replace pred "?" "")])
          (:predicates data)))])
     ;; Service info
     (when-let [svc (:service data)]
               [:div.statechart-info
                [:div.sc-row [:strong "FSM State: "]
                 [:span.sc-state-tag (str (:current-state svc))]]
                (when-let [ctx (:context svc)]
                          [:div.sc-row [:strong "Context: "]
                           [:pre.meta-content (pr-str ctx)]])])
     ;; Store info
     (when-let [st (:store data)]
               [:div.statechart-info
                [:div.sc-row [:strong "Store: "]
                 [:span (str (:store-type st))]]
                (when (:current-state st)
                  [:div.sc-row [:strong "FSM State: "]
                   [:span.sc-state-tag (str (:current-state st))]])])
     ;; Statechart info
     (when-let [sc (:statechart data)]
               [:div.statechart-info
                [:div.sc-row [:strong "Machine: "] [:span (str (:id sc))]]
                [:div.sc-row [:strong "Initial: "] [:span (str (:initial sc))]]])
     ;; Value
     [:pre.value-content (:value-str data)]
     ;; Var metadata
     (when (seq (:var-meta data))
       [:div.meta-section
        [:div.meta-title "Var Metadata"]
        [:div.meta-entries
         (doall
          (for [[k v] (sort-by key (:var-meta data))]
               ^{:key (str k)}
               [:div.meta-entry
                [:span.meta-key (str k)]
                [:span.meta-val (str v)]]))]])]
    [:div.inspector-empty "No value available"]))

(defn- aliases-inspector
  "Render namespace aliases."
  [data]
  (if (seq data)
    [:div {:style {:padding "0.5rem" :overflow "auto"}}
     (doall
      (for [a data]
           ^{:key (or (:uri/string a) (:alias/name a))}
           [:div.alias-item
            [:span.alias-short (:alias/name a)]
            [:span.alias-arrow " \u2192 "]
            [:span.alias-full (:alias/to-ns a)]]))]
    [:div.inspector-empty "No aliases"]))

(defn- refers-inspector
  "Render namespace refers."
  [data]
  (if (seq data)
    [:div {:style {:padding "0.5rem" :overflow "auto"}}
     (doall
      (for [r data]
           ^{:key (or (:uri/string r) (:refer/symbol r))}
           [:div.refer-item
            [:span.refer-sym (:refer/symbol r)]
            [:span.refer-from
             (str " (from " (:refer/from-ns-source r) ")")]]))]
    [:div.inspector-empty "No refers"]))

(defn- deps-inspector
  "Render symbol dependencies (placeholder)."
  [_data]
  [:div.inspector-empty "Dependencies view coming soon"])

(defn- callers-inspector
  "Render symbol callers (placeholder)."
  [_data]
  [:div.inspector-empty "Callers view coming soon"])

;; =============================================================================
;; Inspector Panel
;; =============================================================================

(defn- inspector-panel
  "Tabbed inspector panel below the 4 columns."
  [browser-id !b]
  (let [state @!b
        tabs (available-tabs state)
        active (:active-tab state)
        pane-state (get-in state [:pane-states :inspector])
        data (get-in state [:data :inspector active])]
    [:div.pane-inspector
     {:style {:flex (str (:inspector-height state))}}
     ;; Tab bar
     [:div.inspector-tabs
      (doall
       (for [tab tabs]
            ^{:key tab}
            [:div.inspector-tab
             {:class (when (= tab active) "active")
              :on-click #(switch-tab! browser-id tab)}
             (get tab-labels tab (name tab))]))]
     ;; Content area
     [:div.inspector-content
      (if (empty? tabs)
        [:div.inspector-empty "Select a namespace or symbol to inspect"]
        (case pane-state
          :loading [:div.pane-loading [:div.mini-spinner] [:span "Loading..."]]
          (case active
            :source [source-inspector data]
            :doc [doc-inspector data]
            :var-value [var-value-inspector data]
            :aliases [aliases-inspector data]
            :refers [refers-inspector data]
            :deps [deps-inspector data]
            :callers [callers-inspector data]
            [:div.inspector-empty "Unknown tab"])))]]))

;; =============================================================================
;; Main Browser Layout
;; =============================================================================

(defn- browser-layout
  "Complete pane browser layout for one instance.
   Rendered into a WinBox body."
  [browser-id]
  (let [!b (get-browser-atom browser-id)]
    (when !b
      (let [state @!b
            columns-flex (str (- 1.0 (:inspector-height state)))]
        [:div.pane-browser
         ;; 4-column pane area
         [:div.pane-columns {:style {:flex columns-flex}}
          [projects-pane browser-id !b]
          [:div.pane-divider-v
           {:on-mouse-down #(start-column-drag! browser-id 0 %)}]
          [namespaces-pane browser-id !b]
          [:div.pane-divider-v
           {:on-mouse-down #(start-column-drag! browser-id 1 %)}]
          [sym-types-pane browser-id !b]
          [:div.pane-divider-v
           {:on-mouse-down #(start-column-drag! browser-id 2 %)}]
          [symbols-pane browser-id !b]]
         ;; Horizontal divider
         [:div.pane-divider-h
          {:on-mouse-down #(start-row-drag! browser-id %)}]
         ;; Inspector panel
         [inspector-panel browser-id !b]]))))

;; =============================================================================
;; WinBox Browser Management
;; =============================================================================

(defn- next-browser-id
  "Generate a unique browser ID."
  []
  (keyword (str "browser-" (swap! !browser-counter inc))))

(declare close-browser!)

(defn open-browser!
  "Open a new pane browser instance in a WinBox window."
  [{:keys [title project-uri]}]
  (let [bid (next-browser-id)
        !b (r/atom (make-browser-state bid))
        vw (.-innerWidth js/window)
        vh (.-innerHeight js/window)
        w (min 1200 (- vw 60))
        h (min 800 (- vh 60))
        x (/ (- vw w) 2)
        y (max 40 (/ (- vh h) 2))
        !closing (atom false)
        wb-opts {:title (or title "Code Browser")
                 :width w :height h :x x :y y
                 :class ["no-full"]
                 :background "#37474f"
                 :onfocus (fn []
                            (reset! !focused-browser bid))
                 :onclose (fn []
                            (when-not @!closing
                              (reset! !closing true)
                              (close-browser! bid))
                            js/undefined)}
        wb (js/WinBox. (clj->js wb-opts))]
    ;; Register browser
    (swap! !browsers assoc bid !b)
    (swap! !winbox-instances assoc bid wb)
    (reset! !focused-browser bid)
    ;; Render layout into WinBox body
    (rdom/render [browser-layout bid] (.-body wb))
    ;; Fetch initial project list
    (fetch-for-pane! bid :projects nil :project-list)
    ;; Auto-select project if provided
    (when project-uri
      ;; Delay to let project list load first
      (js/setTimeout #(select-project! bid project-uri) 500))
    (log/log! {:level :info :id ::browser-opened
               :msg "Pane browser opened"
               :data {:browser-id bid}})
    bid))

(defn close-browser!
  "Close a pane browser instance and clean up."
  [browser-id]
  ;; Remove state first to prevent re-render loops
  (swap! !browsers dissoc browser-id)
  (when-let [wb (get @!winbox-instances browser-id)]
            (swap! !winbox-instances dissoc browser-id)
            (try (.close wb) (catch js/Error _e nil)))
  (when (= @!focused-browser browser-id)
    (reset! !focused-browser (first (keys @!browsers))))
  (log/log! {:level :info :id ::browser-closed
             :msg "Pane browser closed"
             :data {:browser-id browser-id}}))

;; =============================================================================
;; Fingerprint Polling
;; =============================================================================

(defn- poll-all-browsers!
  "Poll fingerprints for all browser instances."
  []
  (doseq [[browser-id !b] @!browsers]
         (let [state @!b
               sel (:selections state)]
           ;; Poll namespace list (nREPL projects only)
           (when-let [proj (:project sel)]
                     (when (str/starts-with? proj "nrepl://")
                       (send-event!
                        :code-browser-v2/check-ns-list-fingerprint
                        {:project-uri proj
                         :widget-id (make-request-id browser-id :fp-ns-list)})))
           ;; Poll symbol list (nREPL namespaces only)
           (when-let [ns-uri (:namespace sel)]
                     (let [parsed (uri/parse ns-uri)
                           proj-uri (when parsed
                                      (uri/build
                                       {:source (:uri/source parsed)
                                        :project (:uri/project parsed)
                                        :version (:uri/version parsed)}))]
                       (when (and proj-uri
                                  (str/starts-with? proj-uri "nrepl://"))
                         (send-event!
                          :code-browser-v2/check-symbol-list-fingerprint
                          {:ns-name (:uri/namespace parsed)
                           :widget-id (make-request-id browser-id
                                                       :fp-symbol-list)}))))
           ;; Poll var value (when Value tab active)
           (when (and (= (:active-tab state) :var-value) (:symbol sel))
             (let [parsed (uri/parse (:symbol sel))]
               (when (and (:uri/namespace parsed) (:uri/symbol parsed))
                 (send-event!
                  :code-browser-v2/check-var-fingerprint
                  {:ns-name (:uri/namespace parsed)
                   :var-name (:uri/symbol parsed)
                   :widget-id (make-request-id browser-id
                                               :fp-var-value)})))))))

;; =============================================================================
;; Main Toolbar + Panel
;; =============================================================================

(defn- main-toolbar
  "Toolbar above browser instances."
  []
  [:div.browser-toolbar
   [:button.new-browser-btn
    {:on-click #(open-browser! {})}
    "+ New Browser"]])

(defn- main-panel
  "Root component rendered to #code-browser-root."
  []
  [:div {:style {:height "100%" :display "flex" :flex-direction "column"}}
   [main-toolbar]
   [:div {:style {:position "relative" :flex "1" :overflow "hidden"
                  :background "#e8e8e8"}}
    (when-not (seq @!browsers)
      [:div {:style {:padding "2rem" :text-align "center"
                     :color "#888" :font-style "italic"}}
       "No browsers open. Click '+ New Browser' to start."])]])

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn mount!
  "Mount pane browser to #code-browser-root.
   Creates one default browser instance and starts polling."
  []
  (install-global-drop-handler!)
  (bootstrap/mount-root! [main-panel])
  ;; Create default browser instance
  (open-browser! {})
  ;; Start fingerprint polling
  (when-let [old @!poll-interval]
            (js/clearInterval old))
  (reset! !poll-interval (js/setInterval poll-all-browsers! poll-interval-ms))
  (js/console.log "[pane-browser] Mounted")
  (log/log! {:level :info :id ::mounted :msg "Pane browser mounted"}))

(defn unmount!
  "Unmount pane browser. Closes all instances and stops polling."
  []
  ;; Stop polling
  (when-let [interval @!poll-interval]
            (js/clearInterval interval)
            (reset! !poll-interval nil))
  ;; Close all browser instances
  (doseq [[bid _] @!browsers]
         (close-browser! bid))
  (reset! !browsers {})
  (reset! !winbox-instances {})
  (reset! !browser-counter 0)
  (reset! !focused-browser nil)
  (reset! !project-color-map {})
  (reset! !add-project-state {:path "" :error nil :loading? false})
  (js/console.log "[pane-browser] Unmounted")
  (log/log! {:level :info :id ::unmounted :msg "Pane browser unmounted"}))
