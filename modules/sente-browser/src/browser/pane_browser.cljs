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
      :deps "Deps" :callers "Callers"
      :project-info "Info"
      :ns-info "Info"})

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
   :sort-mode :alpha
   :focused-pane nil
   :highlight {:projects -1 :namespaces -1 :sym-types -1 :symbols -1}
   :pane-widths default-pane-widths
   :inspector-height default-inspector-height
   ;; Navigation history (browser-style back/forward)
   :nav-history []   ;; vector of {:project :namespace :symbol :tab}
   :nav-index -1})

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
      [:ns-info :aliases :refers]

      project
      [:project-info]

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

(defn- update-browser-title!
  "Update WinBox title to show the deepest selected URI."
  [browser-id]
  (when-let [!b (get-browser-atom browser-id)]
    (when-let [wb (get @!winbox-instances browser-id)]
      (let [sel (:selections @!b)
            fqn (or (:symbol sel) (:namespace sel) (:project sel))
            parsed (when fqn (uri/parse fqn))
            pname (or (:uri/project parsed) "Browser")
            live? (and (:project sel)
                       (str/starts-with? (:project sel) "nrepl://"))]
        (.setTitle wb (str "Code Browser \u2014 " (or fqn "Browser")
                           (when live? " - (Live)")))
        (.setBackground wb (project-color pname))))))

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
                            (assoc :active-tab :project-info)
                            ensure-valid-tab)))
            (fetch-for-pane! browser-id :namespaces project-uri :ns-list)
            (fetch-inspector-tab! browser-id)
            (update-browser-title! browser-id)))

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
                            (assoc :active-tab :ns-info)
                            ensure-valid-tab)))
            (fetch-for-pane! browser-id :symbols ns-uri :symbol-list)
            (fetch-inspector-tab! browser-id)
            (update-browser-title! browser-id)))

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
            (fetch-inspector-tab! browser-id)
            (update-browser-title! browser-id)))

(defn- fetch-inspector-tab!
  "Fetch data for the browser's currently active inspector tab."
  ([browser-id]
   (when-let [!b (get-browser-atom browser-id)]
             (let [state @!b
                   tab (:active-tab state)
                   cached (get-in state [:data :inspector tab])]
               (when-not cached
                 (let [sel (:selections state)
                       ;; Project-info uses project URI, aliases/refers use namespace URI, others use symbol URI
                       target-uri (case tab
                                    :project-info
                                    (:project sel)
                                    (:aliases :refers :ns-info)
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
;; Navigation History
;; =============================================================================

(defn- current-location
  "Capture the current selection state as a history entry."
  [state]
  (let [sel (:selections state)]
    {:project (:project sel)
     :namespace (:namespace sel)
     :symbol (:symbol sel)
     :tab (:active-tab state)}))

(defn- push-nav-history!
  "Save current location to history, truncating any forward entries.
   Deduplicates: won't push if current location matches the entry at nav-index."
  [!b]
  (swap! !b (fn [s]
              (let [loc (current-location s)
                    idx (:nav-index s)
                    current-entry (when (>= idx 0)
                                    (get (:nav-history s) idx))]
                (if (= loc current-entry)
                  s ;; Already at this location in history, no-op
                  (let [history (subvec (:nav-history s) 0 (max 0 (inc idx)))]
                    (-> s
                        (assoc :nav-history (conj history loc))
                        (assoc :nav-index (count history)))))))))

(defn- restore-location!
  "Restore a history entry by cascading selections with setTimeout delays."
  [browser-id loc]
  (let [{:keys [project namespace symbol tab]} loc]
    (if project
      (do (select-project! browser-id project)
          (when namespace
            (js/setTimeout
             (fn []
               (select-namespace! browser-id namespace)
               (when symbol
                 (js/setTimeout
                  (fn []
                    (select-symbol! browser-id symbol)
                    (when tab
                      (js/setTimeout
                       #(switch-tab! browser-id tab) 200)))
                  300)))
             300)))
      ;; No project — just clear selections
      (when-let [!b (get-browser-atom browser-id)]
                (swap! !b assoc :selections
                       {:project nil :namespace nil
                        :sym-type nil :symbol nil})))))

(defn- navigate-back!
  "Move back in navigation history."
  [browser-id]
  (when-let [!b (get-browser-atom browser-id)]
            (let [state @!b
                  idx (:nav-index state)]
              (when (> idx 0)
                (let [new-idx (dec idx)
                      loc (get (:nav-history state) new-idx)]
                  (swap! !b assoc :nav-index new-idx)
                  (restore-location! browser-id loc))))))

(defn- navigate-forward!
  "Move forward in navigation history."
  [browser-id]
  (when-let [!b (get-browser-atom browser-id)]
            (let [state @!b
                  idx (:nav-index state)
                  history (:nav-history state)]
              (when (< idx (dec (count history)))
                (let [new-idx (inc idx)
                      loc (get history new-idx)]
                  (swap! !b assoc :nav-index new-idx)
                  (restore-location! browser-id loc))))))

(declare open-browser!)

(defn navigate-to-symbol!
  "Navigate to a symbol by URI. Pushes current location to history.
   Options:
     :new-window? - Open in a new WinBox window instead of same window
     :project-uri - Override project URI (otherwise extracted from symbol-uri)
     :ns-uri      - Override namespace URI (otherwise extracted from symbol-uri)"
  ([browser-id symbol-uri]
   (navigate-to-symbol! browser-id symbol-uri {}))
  ([browser-id symbol-uri opts]
   (when-let [!b (get-browser-atom browser-id)]
             (let [parsed (uri/parse symbol-uri)]
               (when parsed
                 (if (:new-window? opts)
                   ;; Open in a new WinBox window
                   (let [proj-uri (or (:project-uri opts) (uri/project-uri parsed))
                         ns-uri (or (:ns-uri opts) (uri/namespace-uri parsed))]
                     (open-browser!
                      {:project-uri proj-uri
                       :auto-nav {:ns-uri ns-uri
                                  :symbol-uri symbol-uri}}))
                   ;; Navigate in same window
                   (let [state @!b
                         sel (:selections state)
                         proj-uri (or (:project-uri opts) (uri/project-uri parsed))
                         ns-uri (or (:ns-uri opts) (uri/namespace-uri parsed))
                         same-proj? (= (:project sel) proj-uri)
                         same-ns? (and same-proj?
                                       (= (:namespace sel) ns-uri))]
                     ;; Push current location before navigating
                     (push-nav-history! !b)
                     (cond
                       ;; Same namespace — just select the symbol
                       same-ns?
                       (do (select-symbol! browser-id symbol-uri)
                           (push-nav-history! !b))
                       ;; Same project — select namespace then symbol
                       same-proj?
                       (do (select-namespace! browser-id ns-uri)
                           (js/setTimeout
                            (fn []
                              (select-symbol! browser-id symbol-uri)
                              (push-nav-history! !b))
                            300))
                       ;; Different project — cascade all three
                       :else
                       (do (select-project! browser-id proj-uri)
                           (js/setTimeout
                            (fn []
                              (select-namespace! browser-id ns-uri)
                              (js/setTimeout
                               (fn []
                                 (select-symbol! browser-id symbol-uri)
                                 (push-nav-history! !b))
                               300))
                            300))))))))))

;; =============================================================================
;; Clipboard & Drag-and-Drop
;; =============================================================================

(defn- symbol-fqn
  "Build a fully-qualified name from a symbol's URI.
   Returns ns/name or just name if namespace is unavailable."
  [symbol-uri]
  (when symbol-uri
    (let [parsed (uri/parse symbol-uri)]
      (if (:uri/namespace parsed)
        (str (:uri/namespace parsed) "/" (:uri/symbol parsed))
        (:uri/symbol parsed)))))

(defn- copy-symbol-fqn!
  "Copy the currently selected symbol's FQN to clipboard.
   Shows brief visual feedback."
  [browser-id]
  (when-let [!b (get-browser-atom browser-id)]
            (let [sym-uri (get-in @!b [:selections :symbol])]
              (when sym-uri
                (let [fqn (symbol-fqn sym-uri)]
                  (-> (js/navigator.clipboard.writeText fqn)
                      (.then (fn []
                               (swap! !b assoc :copy-feedback fqn)
                               (js/setTimeout
                                #(swap! !b dissoc :copy-feedback)
                                1500)))))))))

(defn- paste-navigate!
  "Read clipboard text and navigate to it as a symbol FQN.
   Accepts ns/name or full URI."
  [browser-id]
  (-> (js/navigator.clipboard.readText)
      (.then (fn [text]
               (let [text (str/trim text)]
                 (when (not (str/blank? text))
                   (if (or (str/starts-with? text "dir://")
                           (str/starts-with? text "nrepl://"))
                     ;; Full URI — navigate directly
                     (navigate-to-symbol! browser-id text)
                     ;; Qualified name — resolve via server
                     (when-let [!b (get-browser-atom browser-id)]
                               (let [state @!b
                                     sel (:selections state)
                                     ns-uri (:namespace sel)
                                     parsed (when ns-uri (uri/parse ns-uri))
                                     proj-name (:uri/project parsed)]
                                 (send-event!
                                  :code-browser-v2/resolve-symbol
                                  {:project-name proj-name
                                   :ns-name ns-uri
                                   :symbol-text text
                                   :widget-id (make-request-id
                                               browser-id :resolve)}))))))))))

(defn- make-drag-data
  "Build drag event data for a symbol URI."
  [sym-uri]
  {:uri sym-uri
   :fqn (symbol-fqn sym-uri)})

(defn- handle-drag-start!
  "Set up drag data for a symbol."
  [e sym-uri]
  (let [dt (.-dataTransfer e)
        data (make-drag-data sym-uri)]
    (.setData dt "text/plain" (:fqn data))
    (.setData dt "application/x-cb-uri" sym-uri)
    (set! (.-effectAllowed dt) "copyMove")))

(defn- handle-browser-drop!
  "Handle drop on a browser window — navigate to the dropped symbol."
  [browser-id e]
  (.preventDefault e)
  (let [dt (.-dataTransfer e)
        cb-uri (.getData dt "application/x-cb-uri")
        text (.getData dt "text/plain")]
    ;; Remove drop-target highlight
    (when-let [!b (get-browser-atom browser-id)]
              (swap! !b dissoc :drop-target?))
    (cond
      ;; Prefer internal URI
      (not (str/blank? cb-uri))
      (navigate-to-symbol! browser-id cb-uri)

      ;; Fall back to text (FQN or URI)
      (not (str/blank? text))
      (let [text (str/trim text)]
        (if (or (str/starts-with? text "dir://")
                (str/starts-with? text "nrepl://"))
          (navigate-to-symbol! browser-id text)
          ;; Try to resolve as FQN
          (when-let [!b (get-browser-atom browser-id)]
                    (let [state @!b
                          sel (:selections state)
                          ns-uri (:namespace sel)
                          parsed (when ns-uri (uri/parse ns-uri))
                          proj-name (:uri/project parsed)]
                      (send-event!
                       :code-browser-v2/resolve-symbol
                       {:project-name proj-name
                        :ns-name ns-uri
                        :symbol-text text
                        :widget-id (make-request-id
                                    browser-id :resolve)}))))))))

;; =============================================================================
;; Derived Data
;; =============================================================================

(defn- derive-sym-types
  "Derive symbol type groups with counts from symbol data."
  [symbols sort-mode]
  (let [syms (if (= sort-mode :file-order)
               symbols
               (remove :symbol/top-level? symbols))]
    (->> syms
         (keep :symbol/type)
         frequencies
         (sort-by (comp str key))
         (mapv (fn [[t cnt]] {:type t :count cnt})))))

(defn- filter-symbols
  "Filter symbols by type, text, and sort mode."
  [symbols sym-type text-filter sort-mode]
  (let [syms (if (= sort-mode :file-order)
               symbols
               (remove :symbol/top-level? symbols))
        filtered (->> syms
                      (filter (fn [s]
                                (and (or (nil? sym-type)
                                         (= (:symbol/type s) sym-type))
                                     (matches-filter? (:symbol/name s)
                                                      text-filter)))))
        sorted (if (= sort-mode :file-order)
                 (sort-by (juxt (fn [s] (or (:symbol/file s) ""))
                                (fn [s] (or (:symbol/line s) 0)))
                          filtered)
                 (sort-by (juxt :symbol/type :symbol/name) filtered))
        ;; ns symbols always first (like v1 code browser)
        {ns-syms true other-syms false}
        (group-by #(= :ns (:symbol/type %)) sorted)]
    (vec (concat ns-syms other-syms))))

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

(defn- handle-resolve-symbol-result!
  "Handle resolve-symbol response. Navigate to the resolved symbol."
  [data]
  (let [{:keys [success uri widget-id error new-window?]} data]
    (when-let [parsed (parse-request-id (keyword (str (name widget-id))))]
              (let [{:keys [browser-id]} parsed]
                (if (and success uri)
                  (navigate-to-symbol! browser-id uri
                                       (when new-window?
                                         {:new-window? true}))
                  (log/log! {:level :warn :id ::resolve-failed
                             :msg "Symbol resolution failed"
                             :data {:error error
                                    :browser-id browser-id}}))))))

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

     :code-browser-v2/resolve-symbol-result
     (handle-resolve-symbol-result! data)

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
;; Keyboard Navigation
;; =============================================================================

(def ^:private pane-order
     "Pane navigation order for Tab cycling."
     [:projects :namespaces :sym-types :symbols])

(defn- get-pane-items
  "Get the filtered item list for a pane."
  [state pane]
  (let [filter-text (get-in state [:filters pane])]
    (case pane
      :projects (filterv #(matches-filter?
                           (or (:uri/project %) (:uri/string %))
                           filter-text)
                         (get-in state [:data :projects]))
      :namespaces (filterv #(matches-filter? (:ns/name %) filter-text)
                           (get-in state [:data :namespaces]))
      :sym-types (let [sort-mode (:sort-mode state)
                       sym-types (derive-sym-types
                                  (get-in state [:data :symbols]) sort-mode)]
                   (into [{:type nil :count (count
                                             (if (= sort-mode :file-order)
                                               (get-in state [:data :symbols])
                                               (remove :symbol/top-level?
                                                       (get-in state
                                                               [:data :symbols]))))}]
                         (filterv #(matches-filter? (name (:type %))
                                                    filter-text)
                                  sym-types)))
      :symbols (filter-symbols (get-in state [:data :symbols])
                               (get-in state [:selections :sym-type])
                               filter-text
                               (:sort-mode state))
      [])))

(defn- select-highlighted!
  "Select the currently highlighted item in the focused pane."
  [browser-id state pane idx]
  (let [items (get-pane-items state pane)]
    (when-let [item (get items idx)]
              (case pane
                :projects (select-project! browser-id (:uri/string item))
                :namespaces (select-namespace! browser-id (:uri/string item))
                :sym-types (select-sym-type! browser-id (:type item))
                :symbols (select-symbol! browser-id (:uri/string item))
                nil))))

(defn- handle-browser-keydown!
  "Handle keyboard events for pane navigation."
  [browser-id !b e]
  (let [key (.-key e)
        state @!b
        pane (:focused-pane state)]
    ;; Global shortcuts (work regardless of focused pane)
    (cond
      ;; Alt+Left → navigate back
      (and (= key "ArrowLeft") (.-altKey e))
      (do (.preventDefault e) (navigate-back! browser-id))

      ;; Alt+Right → navigate forward
      (and (= key "ArrowRight") (.-altKey e))
      (do (.preventDefault e) (navigate-forward! browser-id))

      ;; Cmd+C → copy selected symbol FQN (when no text selection)
      (and (= key "c") (.-metaKey e)
           (str/blank? (str (.getSelection js/window))))
      (do (.preventDefault e) (copy-symbol-fqn! browser-id))

      ;; Cmd+V → paste FQN to navigate (when no text input focused)
      (and (= key "v") (.-metaKey e)
           (not (#{"INPUT" "TEXTAREA"} (.-tagName (.-activeElement js/document)))))
      (do (.preventDefault e) (paste-navigate! browser-id))

      ;; Pane-specific shortcuts
      pane
      (case key
        "ArrowDown"
        (do (.preventDefault e)
            (let [items (get-pane-items state pane)
                  cur (get-in state [:highlight pane] -1)
                  next-idx (min (dec (count items)) (inc cur))]
              (swap! !b assoc-in [:highlight pane] next-idx)))

        "ArrowUp"
        (do (.preventDefault e)
            (let [cur (get-in state [:highlight pane] 0)
                  next-idx (max 0 (dec cur))]
              (swap! !b assoc-in [:highlight pane] next-idx)))

        "Enter"
        (do (.preventDefault e)
            (let [idx (get-in state [:highlight pane] -1)]
              (when (>= idx 0)
                (select-highlighted! browser-id state pane idx))))

        "Tab"
        (do (.preventDefault e)
            (let [cur-idx (.indexOf pane-order pane)
                  next-idx (if (.-shiftKey e)
                             (mod (dec cur-idx) (count pane-order))
                             (mod (inc cur-idx) (count pane-order)))
                  next-pane (nth pane-order next-idx)]
              (swap! !b assoc :focused-pane next-pane)))

        "Escape"
        (swap! !b assoc :focused-pane nil)

        ;; Default: don't handle
        nil))))

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

(defn- scroll-selected-into-view
  "Ref callback: scrolls the selected element into view (nearest edge only)."
  [el]
  (when el
    (.scrollIntoView el #js {:block "nearest"})))

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
        focused? (= (:focused-pane state) :projects)
        highlight-idx (get-in state [:highlight :projects] -1)
        filtered (filterv #(matches-filter?
                            (or (:uri/project %) (:uri/string %))
                            filter-text)
                          projects)]
    [:div.pane-column
     {:class (when focused? "pane-focused")
      :style {:flex (str (nth (:pane-widths state) 0))}
      :on-click #(swap! !b assoc :focused-pane :projects)}
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
         (map-indexed
          (fn [i p]
            ^{:key (:uri/string p)}
            [:div.pane-item
             (cond-> {:class (str (when (= (:uri/string p) selected) "selected")
                                  (when (and focused? (= i highlight-idx)) " highlighted"))
                      :on-click #(select-project! browser-id (:uri/string p))}
                     (= (:uri/string p) selected)
                     (assoc :ref scroll-selected-into-view))
             (or (:uri/project p) (:uri/string p))
             (when (= :jar (:uri/source p))
               [:span.source-badge "JAR"])])
          filtered))])
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
        focused? (= (:focused-pane state) :namespaces)
        highlight-idx (get-in state [:highlight :namespaces] -1)
        filtered (filterv #(matches-filter? (:ns/name %) filter-text)
                          namespaces)]
    [:div.pane-column
     {:class (when focused? "pane-focused")
      :style {:flex (str (nth (:pane-widths state) 1))}
      :on-click #(swap! !b assoc :focused-pane :namespaces)}
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
         (map-indexed
          (fn [i ns-entity]
            ^{:key (:uri/string ns-entity)}
            [:div.pane-item
             (cond-> {:class (str (when (= (:uri/string ns-entity) selected) "selected")
                                  (when (and focused? (= i highlight-idx)) " highlighted"))
                      :on-click #(select-namespace! browser-id
                                                    (:uri/string ns-entity))}
                     (= (:uri/string ns-entity) selected)
                     (assoc :ref scroll-selected-into-view))
             (:ns/name ns-entity)
             (when (> (count (or (:ns/files ns-entity) [])) 1)
               [:span {:style {:font-size "10px" :color "#888"
                               :margin-left "4px"}}
                (str "(" (count (:ns/files ns-entity)) ")")])])
          filtered))])
     [:div.pane-footer (str (count filtered) " namespaces")]]))

(defn- sym-types-pane
  "Types column: groups symbols by :symbol/type with counts."
  [browser-id !b]
  (let [state @!b
        symbols (get-in state [:data :symbols])
        pane-state (get-in state [:pane-states :symbols])
        filter-text (get-in state [:filters :sym-types])
        selected-type (get-in state [:selections :sym-type])
        sort-mode (:sort-mode state)
        sym-types (derive-sym-types symbols sort-mode)
        all-count (count (if (= sort-mode :file-order)
                           symbols
                           (remove :symbol/top-level? symbols)))
        filtered (filterv #(matches-filter? (when (:type %) (name (:type %)))
                                            filter-text)
                          sym-types)
        focused? (= (:focused-pane state) :sym-types)
        highlight-idx (get-in state [:highlight :sym-types] -1)]
    [:div.pane-column
     {:class (when focused? "pane-focused")
      :style {:flex (str (nth (:pane-widths state) 2))}
      :on-click #(swap! !b assoc :focused-pane :sym-types)}
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
        ;; "All" entry (index 0)
        [:div.pane-item
         {:class (str (when (nil? selected-type) "selected")
                      (when (and focused? (= 0 highlight-idx)) " highlighted"))
          :on-click #(select-sym-type! browser-id nil)}
         "All"
         [:span.type-count (str " [" all-count "]")]]
        (doall
         (map-indexed
          (fn [i {:keys [type count]}]
            ^{:key (str type)}
            [:div.pane-item
             {:class (str (when (= type selected-type) "selected")
                          (when (and focused? (= (inc i) highlight-idx))
                            " highlighted"))
              :on-click #(select-sym-type! browser-id type)}
             (if type (name type) "unknown")
             [:span.type-count (str " [" count "]")]])
          filtered))])
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
        sort-mode (:sort-mode state)
        filtered (filter-symbols symbols sym-type filter-text sort-mode)
        focused? (= (:focused-pane state) :symbols)
        highlight-idx (get-in state [:highlight :symbols] -1)]
    [:div.pane-column
     {:class (when focused? "pane-focused")
      :style {:flex (str (nth (:pane-widths state) 3))}
      :on-click #(swap! !b assoc :focused-pane :symbols)}
     [:div.pane-column-header
      [:span "Symbols"]
      [:button.sort-mode-btn
       {:title (if (= sort-mode :alpha)
                 "Switch to file order (shows top-level forms)"
                 "Switch to alphabetical order")
        :on-click (fn [e]
                    (.stopPropagation e)
                    (swap! !b update :sort-mode
                           (fn [m] (if (= m :alpha) :file-order :alpha))))}
       (if (= sort-mode :alpha) "A\u2193Z" "\u2193#")]]
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
         (map-indexed
          (fn [i sym]
            ^{:key (:uri/string sym)}
            [:div.pane-item
             (cond-> {:class (str (when (= (:uri/string sym) selected) "selected")
                                  (when (:symbol/top-level? sym) " top-level")
                                  (when (and focused? (= i highlight-idx))
                                    " highlighted"))
                      :draggable true
                      :on-drag-start #(handle-drag-start! % (:uri/string sym))
                      :on-click #(select-symbol! browser-id (:uri/string sym))}
                     (= (:uri/string sym) selected)
                     (assoc :ref scroll-selected-into-view))
             [:span (:symbol/name sym)]
             (when-let [kind (:symbol/type sym)]
                       [:span.sym-kind (name kind)])])
          filtered))])
     [:div.pane-footer (str (count filtered) " symbols")]]))

;; =============================================================================
;; Inspector Content Components
;; =============================================================================

(defn- source-inspector
  "Render source code in CM6 editor with Cmd+Click navigation."
  [browser-id data]
  (if data
    (let [on-navigate (fn [{:keys [symbol-text shift?]}]
                        (when-let [!b (get-browser-atom browser-id)]
                                  (let [state @!b
                                        sel (:selections state)
                                        ns-uri (:namespace sel)
                                        parsed (when ns-uri (uri/parse ns-uri))
                                        proj-name (:uri/project parsed)]
                                    (send-event!
                                     :code-browser-v2/resolve-symbol
                                     {:project-name proj-name
                                      :ns-name ns-uri
                                      :symbol-text symbol-text
                                      :new-window? shift?
                                      :widget-id (make-request-id
                                                  browser-id :resolve)}))))]
      [:div.source-view
       [cm6/editor {:value (:content data)
                    :language :clojure
                    :read-only true
                    :on-navigate on-navigate}]
       (when (:file data)
         [:div.source-info
          [:span (str (:file data)
                      (when (:start-line data)
                        (str " lines " (:start-line data)
                             "-" (:end-line data))))]])])
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

(defn- dep-caller-item
  "Render a single dep or caller item. Clickable to navigate."
  [browser-id entry]
  (let [sym-name (:symbol/name entry)
        sym-type (:symbol/type entry)
        ns-name (:uri/namespace entry)
        sym-uri (:uri/string entry)]
    [:div.dep-item
     {:draggable (boolean sym-uri)
      :on-drag-start #(when sym-uri (handle-drag-start! % sym-uri))
      :on-click #(when sym-uri
                   (navigate-to-symbol! browser-id sym-uri))}
     [:span.dep-name sym-name]
     (when sym-type
       [:span.sym-kind (name sym-type)])
     (when ns-name
       [:span.dep-ns (str "(" ns-name ")")])
     [:span.dep-open-new
      {:title "Open in new window"
       :on-click (fn [e]
                   (.stopPropagation e)
                   (when sym-uri
                     (navigate-to-symbol! browser-id sym-uri
                                          {:new-window? true})))}
      "\u2197"]]))

(defn- deps-inspector
  "Render symbol dependencies as clickable list."
  [browser-id data]
  (let [state (when-let [!b (get-browser-atom browser-id)]
                        @!b)
        is-nrepl? (and state
                       (some-> (get-in state [:selections :project])
                               (str/starts-with? "nrepl://")))]
    (cond
      is-nrepl?
      [:div.inspector-empty "Not available for runtime sources"]

      (seq data)
      [:div {:style {:padding "0.5rem" :overflow "auto"}}
       [:div.deps-header (str (count data) " dependencies")]
       (doall
        (for [entry data]
             ^{:key (or (:uri/string entry) (:symbol/name entry))}
             [dep-caller-item browser-id entry]))]

      :else
      [:div.inspector-empty "No dependencies"])))

(defn- callers-inspector
  "Render symbol callers as clickable list."
  [browser-id data]
  (let [state (when-let [!b (get-browser-atom browser-id)]
                        @!b)
        is-nrepl? (and state
                       (some-> (get-in state [:selections :project])
                               (str/starts-with? "nrepl://")))]
    (cond
      is-nrepl?
      [:div.inspector-empty "Not available for runtime sources"]

      (seq data)
      [:div {:style {:padding "0.5rem" :overflow "auto"}}
       [:div.deps-header (str (count data) " callers")]
       (doall
        (for [entry data]
             ^{:key (or (:uri/string entry) (:symbol/name entry))}
             [dep-caller-item browser-id entry]))]

      :else
      [:div.inspector-empty "No callers"])))

;; =============================================================================
;; Project Info Inspector
;; =============================================================================

(defn- format-number
  "Format a number with comma separators."
  [n]
  (if (and n (number? n))
    (let [s (str n)
          parts (reverse (partition-all 3 (reverse s)))]
      (str/join "," (map #(apply str %) parts)))
    (str n)))

(defn- info-row
  "Render a label: value row."
  [label value]
  (when value
    [:div.info-row
     [:span.info-label label]
     [:span.info-value (str value)]]))

(defn- info-section
  "Render a section with a heading and child rows."
  [heading & children]
  [:div.project-info-section
   [:div.info-section-heading heading]
   (into [:div.info-section-body] (remove nil? children))])

(defn- project-info-nrepl
  "Render project info for nREPL sources."
  [data]
  (let [rt (:runtime data)
        conn (:connection data)]
    [:div.project-info-content
     (when rt
       [info-section "Runtime"
        [info-row "Type" (if (:version rt)
                           (str (or (:type rt) "clojure") " " (:version rt))
                           (:type rt))]
        [info-row "Clojure" (:clojure-version rt)]
        [info-row "Java" (str (:java-vendor rt) " " (:java-version rt))]
        [info-row "OS" (:os rt)]])
     (when conn
       [info-section "Connection"
        [info-row "Host" (str (:host conn) ":" (:port conn))]])
     [info-section "Statistics"
      [info-row "Namespaces" (format-number (:namespace-count data))]
      [info-row "Symbols" (format-number (:symbol-count data))]
      (when (:max-memory-mb rt)
        [info-row "Memory"
         (str (- (:max-memory-mb rt) (or (:free-memory-mb rt) 0))
              "/" (:max-memory-mb rt) " MB")])
      (when (:loaded-lib-count rt)
        [info-row "Loaded libs" (format-number (:loaded-lib-count rt))])]]))

(defn- project-info-dir
  "Render project info for directory sources."
  [data]
  (let [git (:git data)]
    [:div.project-info-content
     [info-section "Project"
      [info-row "Path" (:path data)]
      (when (:build-tool data)
        [info-row "Build" (:build-tool data)])
      (when git
        [info-row "Git" (str (:branch git)
                              (when (:sha git) (str " @ " (:sha git))))])
      (when (:remote git)
        [info-row "Remote" (:remote git)])]
     [info-section "Statistics"
      [info-row "Namespaces" (format-number (:namespace-count data))]
      [info-row "Symbols" (format-number (:symbol-count data))]
      (when (:dep-count data)
        [info-row "Dependencies" (:dep-count data)])]]))

(defn- project-info-jar
  "Render project info for JAR sources."
  [data]
  (let [mvn (:maven data)]
    [:div.project-info-content
     (when mvn
       [info-section "Maven Artifact"
        [info-row "Group" (:group mvn)]
        [info-row "Artifact" (:artifact mvn)]
        [info-row "Version" (:version mvn)]])
     [info-section "JAR"
      [info-row "Path" (:jar-path data)]
      (when (:jar-size-mb data)
        [info-row "Size" (str (/ (:jar-size-mb data) 10.0) " MB")])]
     [info-section "Statistics"
      [info-row "Namespaces" (format-number (:namespace-count data))]
      [info-row "Symbols" (format-number (:symbol-count data))]]]))

(defn- project-info-inspector
  "Render project-level information in the inspector panel."
  [data]
  (if data
    (case (:source-type data)
      :nrepl [project-info-nrepl data]
      :dir [project-info-dir data]
      :jar [project-info-jar data]
      ;; Fallback for unknown source types
      [:div.project-info-content
       [info-section "Project"
        [info-row "Name" (:project-name data)]
        [info-row "Type" (name (or (:source-type data) :unknown))]
        [info-row "Namespaces" (format-number (:namespace-count data))]
        [info-row "Symbols" (format-number (:symbol-count data))]]])
    [:div.inspector-empty "Loading project info..."]))

;; =============================================================================
;; Namespace Info Inspector
;; =============================================================================

(defn- ns-dep-chips
  "Render a list of namespace names as compact clickable chips.
   Clicking a chip navigates to that namespace within the same project."
  [items color bg on-click]
  [:div {:style {:display "flex" :flex-wrap "wrap" :gap "4px"
                 :padding "4px 0"}}
   (doall
    (for [d items]
         ^{:key d}
         [:span {:style {:background bg :color color
                         :padding "2px 8px" :border-radius "12px"
                         :font-size "11px" :cursor "pointer"
                         :font-family "'Fira Code', monospace"}
                 :on-click #(when on-click (on-click d))}
          d]))])

(defn- navigate-to-ns-dep!
  "Navigate to a dependency namespace by name within the same project.
   Finds the matching URI from the loaded namespaces list."
  [browser-id ns-name]
  (when-let [!b (get-browser-atom browser-id)]
    (let [namespaces (get-in @!b [:data :namespaces])
          target (some #(when (= (:ns/name %) ns-name) (:uri/string %))
                       namespaces)]
      (when target
        (push-nav-history! !b)
        (select-namespace! browser-id target)))))

(defn- ns-info-inspector
  "Render namespace-level information in the inspector panel."
  [browser-id data]
  (if data
    (let [source-type (:source-type data)
          deps (:dependencies data)
          dependents (:dependents data)
          on-click (fn [ns-name] (navigate-to-ns-dep! browser-id ns-name))]
      [:div.project-info-content
       [info-section "Namespace"
        [info-row "Name" (:ns-name data)]
        [info-row "Source" (when source-type (name source-type))]
        [info-row "Symbols" (format-number (:symbol-count data))]]
       (when (:doc data)
         [info-section "Documentation"
          [:pre.docstring (:doc data)]])
       (when (:file data)
         [info-section "File"
          [info-row "Path" (:file data)]])
       (when (#{:dir :jar} source-type)
         [:div
          (when (seq deps)
            [info-section (str "Requires (" (count deps) ")")
             [ns-dep-chips deps "#1565c0" "#e3f2fd" on-click]])
          (when (seq dependents)
            [info-section (str "Required By (" (count dependents) ")")
             [ns-dep-chips dependents "#2e7d32" "#e8f5e9" on-click]])])
       (when (= :nrepl source-type)
         [info-section "Note"
          [:div {:style {:color "#888" :font-style "italic" :padding "4px"}}
           "Dependency data not available for runtime sources"]])])
    [:div.inspector-empty "Loading namespace info..."]))

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
        data (get-in state [:data :inspector active])
        nav-idx (:nav-index state)
        nav-history (:nav-history state)
        can-back? (> nav-idx 0)
        can-fwd? (< nav-idx (dec (count nav-history)))
        has-symbol? (some? (get-in state [:selections :symbol]))
        copy-feedback (:copy-feedback state)]
    [:div.pane-inspector
     {:style {:flex (str (:inspector-height state))}}
     ;; Tab bar with back/forward buttons
     [:div.inspector-tabs
      ;; Back/Forward navigation buttons
      [:button.nav-btn
       {:disabled (not can-back?)
        :title "Back (Alt+Left)"
        :on-click #(navigate-back! browser-id)}
       "\u25C0"]
      [:button.nav-btn
       {:disabled (not can-fwd?)
        :title "Forward (Alt+Right)"
        :on-click #(navigate-forward! browser-id)}
       "\u25B6"]
      ;; Copy FQN button
      [:button.nav-btn.copy-fqn-btn
       {:disabled (not has-symbol?)
        :title "Copy symbol FQN (Cmd+C)"
        :on-click #(copy-symbol-fqn! browser-id)}
       (if copy-feedback "\u2713" "\u2398")]
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
            :source [source-inspector browser-id data]
            :doc [doc-inspector data]
            :var-value [var-value-inspector data]
            :aliases [aliases-inspector data]
            :refers [refers-inspector data]
            :deps [deps-inspector browser-id data]
            :callers [callers-inspector browser-id data]
            :project-info [project-info-inspector data]
            :ns-info [ns-info-inspector browser-id data]
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
            columns-flex (str (- 1.0 (:inspector-height state)))
            drop-target? (:drop-target? state)]
        [:div.pane-browser
         {:tab-index 0
          :class (when drop-target? "drop-target")
          :on-key-down #(handle-browser-keydown! browser-id !b %)
          :on-drag-over (fn [e]
                          (.preventDefault e)
                          (set! (.-dropEffect (.-dataTransfer e)) "copy")
                          (swap! !b assoc :drop-target? true))
          :on-drag-leave (fn [e]
                           ;; Only clear when leaving the browser root
                           (when-not (.contains (.-currentTarget e)
                                                (.-relatedTarget e))
                             (swap! !b dissoc :drop-target?)))
          :on-drop #(handle-browser-drop! browser-id %)}
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
  "Open a new pane browser instance in a WinBox window.
   Options:
     :title        - Window title
     :project-uri  - Auto-select this project
     :auto-nav     - {:ns-uri :symbol-uri} to auto-navigate after opening"
  [{:keys [title project-uri auto-nav]}]
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
    ;; Auto-select project and optionally auto-navigate to symbol
    (let [p-uri (or project-uri
                    (when auto-nav
                      (when-let [parsed (uri/parse (:symbol-uri auto-nav))]
                                (uri/project-uri parsed))))]
      (when p-uri
        (js/setTimeout
         (fn []
           (select-project! bid p-uri)
           (when auto-nav
             (js/setTimeout
              (fn []
                (select-namespace! bid (:ns-uri auto-nav))
                (js/setTimeout
                 #(select-symbol! bid (:symbol-uri auto-nav))
                 300))
              300)))
         500)))
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