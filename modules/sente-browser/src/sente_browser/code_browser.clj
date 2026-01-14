(ns sente-browser.code-browser
    "Server-side handlers for code browser.

   Fetches data from clojure-lsp and sends to browser via sente-lite.

   State Management:
   - Uses atom-sync to push state to browsers (Phase 1.5-Pre)
   - Parallel mode: updates atom AND sends events (for migration)

   Events from browser:
   - :code-browser/request-namespaces {}
   - :code-browser/request-symbols {:file string}
   - :code-browser/request-source {:file string :start-line int :end-line int}
   - :code-browser/select-ns {:ns string}        ; Phase 1.5-Pre action
   - :code-browser/select-var {:var-name string} ; Phase 1.5-Pre action
   - :code-browser/toggle-sort-mode {}           ; Phase 1.5E.1 - toggle alpha/file-order
   - :code-browser/set-sort-mode {:mode keyword} ; Phase 1.5E.1 - set to :alpha or :file-order

   Events to browser (legacy, kept during migration):
   - :code-browser/namespaces {:namespaces [...]}
   - :code-browser/symbols {:symbols [...]}
   - :code-browser/source {:code string :file string :language string}
   - :code-browser/sort-mode-changed {:sort-mode keyword}

   Synced Atom State Shape:
   {:namespaces [\"ns.a\" \"ns.b\" ...]
    :selected-ns \"ns.a\"
    :symbols [{:name \"foo\" :kind :function :line 10 ...}]
    :selected-symbol \"foo\"
    :source {:code \"...\" :file \"...\" :start-line 1 :end-line 20}
    :sort-mode :file-order  ; :alpha or :file-order (Phase 1.5E.1)
    :git {:project-root \"/path\" :branch \"main\" :dirty? false :upstream \"origin/main\"}
    :loading? false
    :error nil}"
    (:require [atom-sync.core :as atom-sync]
              [atom-sync.server :as atom-sync-server]
              [babashka.process :refer [shell]]
              [bb-mcp-server.modules.clojure-lsp.client :as lsp-client]
              [bb-mcp-server.modules.clojure-lsp.server :as lsp-server]
              [bb-mcp-server.modules.clojure-lsp.watcher :as lsp-watcher]
              [clojure.edn :as edn]
              [clojure.java.io :as io]
              [clojure.set :as set]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; =============================================================================
;; File Change Watching (Phase 1.5-Watch)
;; =============================================================================

;; Forward declarations for functions defined later
(declare !code-browser-state)
(declare handle-request-symbols)
(declare handle-request-namespaces)
(declare handle-request-var-source)
(declare refresh-git-info!)

;; -----------------------------------------------------------------------------
;; Debounced Re-fetch (Reactive Pattern)
;; -----------------------------------------------------------------------------
;; Instead of fixed delays, we debounce re-fetch actions. Each publishDiagnostics
;; notification resets the timer. We only execute when clojure-lsp goes "quiet"
;; for debounce-delay-ms, indicating it's done re-indexing.

(def debounce-delay-ms
     "Debounce delay in ms. After last notification, wait this long before re-fetching.
   500ms is aggressive but works well for small file changes.
   For large refactors, notifications will keep resetting the timer."
     500)

;; Pending debounced actions: {action-key {:timer future}}
;; action-key is :refresh-namespaces or a namespace name string
(defonce ^:private !pending-actions (atom {}))

(defn- cancel-pending-action!
  "Cancel any pending action for the given key."
  [action-key]
  (when-let [{:keys [timer]} (get @!pending-actions action-key)]
            (future-cancel timer))
  (swap! !pending-actions dissoc action-key))

(defn- schedule-debounced-action!
  "Schedule a debounced action. Cancels any pending action with same key.
   If more notifications arrive before delay expires, timer resets.
   This creates 'quiet period' detection - action runs when notifications stop."
  [action-key action-fn]
  ;; Cancel existing timer if any
  (cancel-pending-action! action-key)

  ;; Schedule new timer
  (let [timer (future
               (Thread/sleep debounce-delay-ms)
               ;; Only execute if still pending (wasn't cancelled by newer notification)
               (when (contains? @!pending-actions action-key)
                 (swap! !pending-actions dissoc action-key)
                 (log/log! {:level :info
                            :id ::debounce-triggered
                            :msg "Debounce period elapsed, executing action"
                            :data {:action-key action-key}})
                 (action-fn)))]
    (swap! !pending-actions assoc action-key {:timer timer})
    (log/log! {:level :debug
               :id ::debounce-scheduled
               :msg "Scheduled debounced action"
               :data {:action-key action-key :delay-ms debounce-delay-ms}})))

(defn- path->probable-namespace
  "Convert file path to probable namespace name.
   e.g., /foo/bar/src/my/cool/ns.clj -> my.cool.ns
   This is heuristic - may not always match actual ns declaration."
  [path]
  (when path
    (let [;; Remove common source prefixes
          cleaned (-> path
                      (str/replace #".*/src/" "")
                      (str/replace #".*/test/" "")
                      ;; Remove file extension
                      (str/replace #"\.(clj|cljs|cljc)$" "")
                      ;; Convert / to .
                      (str/replace "/" ".")
                      ;; Convert _ to -
                      (str/replace "_" "-"))]
      cleaned)))

(defn- handle-file-change!
  "Handle notification that a file has changed.
   Invalidates cached data for the affected namespace and re-fetches if needed.
   Also detects new files (not in namespace list) and deleted files (no longer exist)."
  [uri]
  (let [path (lsp-client/uri->path uri)
        probable-ns (path->probable-namespace path)
        state @!code-browser-state
        known-namespaces (set (:namespaces state))
        selected-ns (:selected-ns state)
        file-exists? (when path (.exists (io/file path)))]

    (log/log! {:level :info
               :id ::file-change-detected
               :msg "File change detected"
               :data {:uri uri :probable-ns probable-ns :selected-ns selected-ns
                      :file-exists? file-exists?}})

    ;; Case 1: File was deleted - refresh namespace list
    (when (and path (not file-exists?))
      (log/log! {:level :info
                 :id ::file-deleted-detected
                 :msg "File deletion detected, refreshing namespace list"
                 :data {:path path :probable-ns probable-ns}})
      ;; Invalidate any cached data for this namespace (single atomic update)
      (when probable-ns
        (swap! !code-browser-state
               (fn [state]
                 (-> state
                     (update :symbols-by-ns dissoc probable-ns)
                     (update :source-by-var
                             (fn [source-cache]
                               (into {}
                                     (remove (fn [[var-key _]]
                                               (str/starts-with? var-key (str probable-ns "/")))
                                             source-cache))))))))
      ;; Refresh namespace list with debounce (reactive - waits for quiet period)
      (schedule-debounced-action! :refresh-namespaces
                                  #(handle-request-namespaces {})))

    ;; Case 2: New file (namespace not in known list) - refresh namespace list
    (when (and file-exists? probable-ns
               (not (contains? known-namespaces probable-ns))
               ;; Also check partial match
               (not (some #(str/ends-with? % probable-ns) known-namespaces)))
      (log/log! {:level :info
                 :id ::new-namespace-detected
                 :msg "New namespace detected, refreshing namespace list"
                 :data {:probable-ns probable-ns}})
      ;; Refresh namespace list with debounce (reactive - waits for quiet period)
      (schedule-debounced-action! :refresh-namespaces
                                  #(handle-request-namespaces {})))

    ;; Case 3: Existing file modified - re-fetch if this is the selected namespace
    ;; NOTE: We don't invalidate cache here to prevent jitter (0 vars → N vars).
    ;; Instead, handle-request-symbols will atomically replace the cached data.
    (when (and file-exists? (= probable-ns selected-ns))
      (log/log! {:level :info
                 :id ::refetching-selected-ns
                 :msg "Re-fetching symbols for currently selected namespace"
                 :data {:ns selected-ns :selected-symbol (:selected-symbol state)}})
      ;; Re-fetch with debounce (reactive - waits for quiet period)
      ;; Uses namespace as key so multiple changes to same ns only trigger one re-fetch
      ;; preserve-selection?: true keeps selected-symbol intact IF it still exists
      (schedule-debounced-action! selected-ns
                                  #(do
                                    (handle-request-symbols {:ns selected-ns
                                                             :preserve-selection? true})
                                     ;; Re-fetch source for selected symbol if any STILL EXISTS
                                     ;; (check current state, not captured state - symbol may have been cleared)
                                    (when-let [current-symbol (:selected-symbol @!code-browser-state)]
                                              (log/log! {:level :info
                                                         :id ::refetching-selected-source
                                                         :msg "Re-fetching source for selected symbol"
                                                         :data {:ns selected-ns :var current-symbol}})
                                              (handle-request-var-source {:ns selected-ns
                                                                          :var-name current-symbol})))))

    ;; Always refresh git status on file changes (Phase 1.5E.2)
    ;; Debounced to avoid excessive calls during rapid saves
    (schedule-debounced-action! :refresh-git-info refresh-git-info!)))

(defn- on-lsp-notification!
  "Callback for LSP notifications. Handles publishDiagnostics to detect file changes."
  [{:keys [method params]}]
  (when (= method "textDocument/publishDiagnostics")
    (let [uri (:uri params)]
      (handle-file-change! uri))))

;; =============================================================================
;; Configuration
;; =============================================================================

;; Whether code browser handlers are enabled
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !enabled (atom false))

;; =============================================================================
;; Synced Atom State (Phase 1.5-Acc)
;; =============================================================================

;; The main code browser state, synced to all browsers via atom-sync.
;; This is the canonical state - browsers observe this atom.
;; Uses accumulated maps for instant back-navigation:
;;   :symbols-by-ns  {ns-name [symbols...]}
;;   :source-by-var  {"ns/var-name" {:code ... :file ...}}
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !code-browser-state
         (atom {:namespaces []
                :selected-ns nil
                :symbols-by-ns {}      ;; Accumulated: {ns [symbols...]}
                :selected-symbol nil
                :source-by-var {}      ;; Accumulated: {"ns/var" {:code ...}}
                :sort-mode :file-order ;; :alpha or :file-order (Phase 1.5E.1)
                :git nil               ;; Git info: {:project-root :branch :dirty? :upstream}
                :loading? false
                :error nil}))

(def ^:private symbol-kind->name
     "LSP Symbol Kinds (from LSP spec) mapped to keywords."
     {1 :file
      2 :module
      3 :namespace
      4 :package
      5 :class
      6 :method
      7 :property
      8 :field
      9 :constructor
      10 :enum
      11 :interface
      12 :function
      13 :variable
      14 :constant
      15 :string
      16 :number
      17 :boolean
      18 :array
      19 :object
      20 :key
      21 :null
      22 :enum-member
      23 :struct
      24 :event
      25 :operator
      26 :type-parameter})

;; =============================================================================
;; clj-kondo Analysis (Phase 1.5A - Rich Var Classification)
;; =============================================================================

(def ^:private defined-by->label
     "Map clj-kondo :defined-by to human-readable kind labels.
   These provide richer classification than LSP's generic kinds."
     {'clojure.core/defn        :function
      'clojure.core/defn-       :private-fn
      'clojure.core/def         :variable
      'clojure.core/defonce     :defonce
      'clojure.core/declare     :declare
      'clojure.core/defmacro    :macro
      'clojure.core/defmulti    :multimethod
      'clojure.core/defmethod   :method
      'clojure.core/defprotocol :protocol
      'clojure.core/deftype     :deftype
      'clojure.core/defrecord   :defrecord
      'clojure.test/deftest     :test})

(defn- analyze-file-with-kondo
  "Run clj-kondo on a file and return analysis map.
   Returns {:var-definitions [...] :var-usages [...]} or nil if analysis fails.
   Note: clj-kondo returns exit code 2 for warnings, 3 for errors.
   We accept exit codes 0 (clean), 2 (warnings), 3 (errors with partial output)
   since we only need the :analysis data which is still provided."
  [file-path]
  (log/log! {:level :debug
             :id ::kondo-analyze
             :msg "Analyzing file with clj-kondo"
             :data {:file file-path}})
  (try
   ;; Use :continue true to prevent throwing on non-zero exit codes
   ;; clj-kondo returns 2 for warnings, 3 for errors, but still outputs analysis
   ;; Include :var-usages for defmethod detection (Phase 1.5E.6)
   ;; Include :protocol-impls for protocol implementation detection (Phase 1.5E.7)
   (let [result (shell {:out :string :err :string :continue true}
                       "clj-kondo" "--lint" file-path
                       "--config" "{:output {:analysis {:var-definitions true :var-usages true :protocol-impls true} :format :edn}}")
         output (:out result)
         exit-code (:exit result)]
     ;; Log warnings if exit code indicates issues
     (when (and (pos? exit-code) (seq (:err result)))
       (log/log! {:level :debug
                  :id ::kondo-warnings
                  :msg "clj-kondo reported warnings/errors"
                  :data {:file file-path
                         :exit-code exit-code
                         :stderr (subs (:err result) 0 (min 200 (count (:err result))))}}))
     (when (seq output)
       (let [analysis (edn/read-string output)
             var-defs (get-in analysis [:analysis :var-definitions])
             var-usages (get-in analysis [:analysis :var-usages])
             protocol-impls (get-in analysis [:analysis :protocol-impls])]
         (log/log! {:level :debug
                    :id ::kondo-result
                    :msg "clj-kondo analysis complete"
                    :data {:file file-path
                           :var-count (count var-defs)
                           :usage-count (count var-usages)
                           :protocol-impl-count (count protocol-impls)
                           :exit-code exit-code}})
         {:var-definitions var-defs
          :var-usages var-usages
          :protocol-impls protocol-impls})))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::kondo-error
                     :msg "clj-kondo analysis failed"
                     :data {:file file-path :error (ex-message e)}})
          nil)))

(defn- kondo-var->symbol
  "Convert a clj-kondo var-definition to our symbol format."
  [var-def]
  (let [defined-by (:defined-by var-def)
        kind (get defined-by->label defined-by :variable)]
    {:name (str (:name var-def))
     :kind kind
     :line (:row var-def)
     :column (:col var-def)
     :end-line (:end-row var-def)
     :end-column (:end-col var-def)
     :doc (:doc var-def)
     :defined-by (str defined-by)
     ;; Preserve original column for protocol method detection
     :_orig-col (:col var-def)}))

;; -----------------------------------------------------------------------------
;; Phase 1.5E.6: defmethod extraction
;; -----------------------------------------------------------------------------

(defn- extract-defmethods
  "Extract defmethod implementations from var-usages.
   Returns symbols for each defmethod with dispatch value in name."
  [var-usages]
  (->> var-usages
       (filter :defmethod)
       (map (fn [usage]
              {:name (str (:name usage) " " (:dispatch-val-str usage))
               :kind :method
               :line (:row usage)
               :column (:col usage)
               :end-line (:end-row usage)
               :end-column (:end-col usage)
               :dispatch-val (:dispatch-val-str usage)
               :multimethod (str (:name usage))}))))

;; -----------------------------------------------------------------------------
;; Phase 1.5E.9: top-level forms extraction
;; -----------------------------------------------------------------------------

;; Forms that indicate side-effects or non-defining top-level code
(def ^:private top-level-form-names
     #{'comment 'println 'prn 'print 'set! 'require 'import 'use 'do 'when 'if 'let
       'binding 'alter-var-root 'reset! 'swap!})

(defn- extract-top-level-forms
  "Extract non-defining top-level forms from var-usages.
   Only includes forms at column 1 (true top-level) with names in the allowlist.
   These are shown only in file-order view to reveal load-time behavior."
  [var-usages]
  (->> var-usages
       (filter (fn [usage]
                 (and (= 1 (:col usage))
                      (contains? top-level-form-names (:name usage)))))
       (map (fn [usage]
              (let [form-name (str (:name usage))]
                {:name (str "(" form-name " ...)")
                 :kind (case (:name usage)
                         comment :comment
                         (println prn print) :side-effect
                         (set! alter-var-root) :config
                         (require import use) :require
                         :form)
                 :line (:row usage)
                 :column (:col usage)
                 :end-line (:end-row usage)
                 :end-column (:end-col usage)
                 :form-type form-name
                 :top-level? true})))))

;; -----------------------------------------------------------------------------
;; Phase 1.5E.7: protocol implementation extraction
;; -----------------------------------------------------------------------------

(defn- find-parent-protocol
  "Find the defprotocol that contains a protocol method.
   Protocol methods have col > 1, parent protocols have col = 1.
   Returns the parent protocol var-definition or nil if not found."
  [var-definitions method-row]
  (->> var-definitions
       (filter (fn [v]
                 (and (= 'clojure.core/defprotocol (:defined-by v))
                      (= 1 (:col v))  ; Protocol definition at column 1
                      (<= (:row v) method-row)
                      (>= (:end-row v) method-row))))
       first))

(defn- fix-protocol-method-lines
  "Update protocol methods to use parent protocol's line range.
   Protocol methods (col > 1) should show the entire defprotocol form.
   Preserves :method-line for highlighting the method within the protocol.
   Returns updated symbols with parent protocol's row/end-row for methods."
  [symbols var-definitions]
  (map (fn [sym]
         (if (and (= :protocol (:kind sym))
                  (> (:_orig-col sym 1) 1))  ; Protocol method, not protocol itself
           ;; Find parent protocol and use its line range
           (let [original-line (:line sym)]  ; Preserve original method line
             (if-let [parent (find-parent-protocol var-definitions original-line)]
                     (-> sym
                         (assoc :line (:row parent)
                                :end-line (:end-row parent)
                                :parent-protocol (str (:name parent))
                                ;; Preserve original method line for highlighting (Phase 1.5E.12)
                                :method-line original-line)
                         (dissoc :_orig-col))
                     (dissoc sym :_orig-col)))
           ;; Not a protocol method - just remove internal field
           (dissoc sym :_orig-col)))
       symbols))

(defn- find-containing-type-var
  "Find the defrecord/deftype var-definition that contains a given line.
   Returns the full var-definition map or nil if not found."
  [var-definitions line]
  (->> var-definitions
       ;; Only defrecord/deftype, not the generated ->Type and map->Type fns
       (filter (fn [v]
                 (and (contains? #{'clojure.core/defrecord 'clojure.core/deftype}
                                 (:defined-by v))
                      ;; Exclude generated constructor functions
                      (not (str/starts-with? (str (:name v)) "->"))
                      (not (str/starts-with? (str (:name v)) "map->")))))
       ;; Find the one containing this line
       (filter (fn [v]
                 (and (<= (:row v) line)
                      (>= (:end-row v) line))))
       first))

(defn- extract-protocol-impls
  "Extract protocol method implementations from protocol-impls analysis.
   Creates symbols like 'protocol-method (MyRecord)' with kind :protocol-impl.
   Uses containing defrecord/deftype's line range for source display.
   Preserves :method-line and :method-end-line for highlighting implementation.
   Requires var-definitions to determine the implementing type name and bounds."
  [protocol-impls var-definitions]
  (->> protocol-impls
       (map (fn [impl]
              (let [type-var (find-containing-type-var var-definitions (:row impl))
                    type-name (when type-var (:name type-var))
                    method-name (str (:method-name impl))
                    display-name (if type-name
                                   (str method-name " (" type-name ")")
                                   method-name)
                    ;; Original method implementation lines (for highlighting)
                    impl-line (:row impl)
                    impl-end-line (:end-row impl)]
                {:name display-name
                 :kind :protocol-impl
                 ;; Use containing type's line range so source shows full defrecord/deftype
                 :line (if type-var (:row type-var) (:row impl))
                 :column (:col impl)
                 :end-line (if type-var (:end-row type-var) impl-end-line)
                 :end-column (:end-col impl)
                 ;; Preserve original method lines for source highlighting (Phase 1.5E.12)
                 :method-line impl-line
                 :method-end-line impl-end-line
                 :protocol (str (:protocol-name impl))
                 :protocol-ns (str (:protocol-ns impl))
                 :method-name method-name
                 :implementing-type (when type-name (str type-name))})))))

(defn- sort-symbols
  "Sort symbols based on current sort mode.
   :file-order sorts by line number (eval order)
   :alpha sorts alphabetically by name
   Args ordered for ->> threading: [sort-mode symbols]"
  [sort-mode symbols]
  (case sort-mode
    :file-order (sort-by :line symbols)
    :alpha (sort-by :name symbols)
    ;; Default to file-order
    (sort-by :line symbols)))

;; =============================================================================
;; Git Status (Phase 1.5E.2)
;; =============================================================================

(defn- shell-git
  "Run a git command and return trimmed stdout, or nil if it fails.
   args is a string of space-separated arguments (e.g., \"rev-parse --show-toplevel\").
   Runs in project-root directory if provided, otherwise current directory."
  ([args] (shell-git args nil))
  ([args project-root]
   (try
    (let [opts (cond-> {:out :string :err :string :continue true}
                       project-root (assoc :dir project-root))
           ;; Split args string into individual arguments for shell
          git-args (str/split args #"\s+")
          result (apply shell opts "git" git-args)]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _
           nil))))

(defn get-git-info
  "Get git repository information for current project.
   Returns map with :project-root, :branch, :dirty?, :upstream, or nil if not a git repo."
  []
  (when-let [project-root (shell-git "rev-parse --show-toplevel")]
            (let [branch (shell-git "rev-parse --abbrev-ref HEAD" project-root)
                  status-output (shell-git "status --porcelain" project-root)
                  dirty? (and status-output (not (str/blank? status-output)))
                  upstream (shell-git "rev-parse --abbrev-ref @{u}" project-root)]
              {:project-root project-root
               :branch branch
               :dirty? dirty?
               :upstream upstream})))

(defn- refresh-git-info!
  "Refresh git info in state atom.
   Called on enable! and after file changes."
  []
  (let [git-info (get-git-info)]
    (swap! !code-browser-state assoc :git git-info)
    (log/log! {:level :debug
               :id ::git-info-refreshed
               :msg "Git info refreshed"
               :data {:branch (:branch git-info)
                      :dirty? (:dirty? git-info)}})))

(defn- extract-ns-symbols-kondo
  "Extract symbols for a namespace using clj-kondo analysis.
   Falls back to LSP-based extraction if kondo fails.
   Sorts according to current :sort-mode in state.
   Includes:
   - var-definitions (defn, def, defmacro, etc.)
   - defmethod implementations (Phase 1.5E.6)
   - protocol implementations (Phase 1.5E.7)
   - top-level forms like comment, println (Phase 1.5E.9, marked with :top-level?)
   Note: Top-level forms are always included; browser filters them in :alpha mode."
  [file-uri ns-name]
  (let [file-path (str/replace file-uri "file://" "")
        sort-mode (:sort-mode @!code-browser-state :file-order)]
    (if-let [analysis (analyze-file-with-kondo file-path)]
      ;; Extract all symbol types from analysis
            (let [ns-sym (symbol ns-name)
                  {:keys [var-definitions var-usages protocol-impls]} analysis

            ;; 1. Var definitions (defn, def, etc.)
            ;; Filter to this namespace, then fix protocol method line ranges
                  ns-var-defs (filter #(= ns-sym (:ns %)) var-definitions)
                  var-symbols (->> ns-var-defs
                                   (map kondo-var->symbol)
                                   (#(fix-protocol-method-lines % var-definitions)))

            ;; 2. defmethod implementations (Phase 1.5E.6)
            ;; Filter to usages from this namespace
                  defmethod-symbols (->> var-usages
                                         (filter #(= ns-sym (:from %)))
                                         extract-defmethods)

            ;; 3. Protocol implementations (Phase 1.5E.7)
            ;; Filter to impls in this namespace, pass var-definitions for type lookup
                  ns-protocol-impls (filter #(= ns-sym (:impl-ns %)) protocol-impls)
                  protocol-impl-symbols (extract-protocol-impls ns-protocol-impls var-definitions)

            ;; 4. Top-level forms (Phase 1.5E.9)
            ;; Always include - browser filters in :alpha mode
            ;; Marked with :top-level? true for browser-side filtering
                  top-level-symbols (->> var-usages
                                         (filter #(= ns-sym (:from %)))
                                         extract-top-level-forms)

            ;; Combine all symbols and sort
                  all-symbols (->> (concat var-symbols defmethod-symbols
                                           protocol-impl-symbols top-level-symbols)
                                   (sort-symbols sort-mode)
                                   vec)]
              (log/log! {:level :info
                         :id ::kondo-symbols-extracted
                         :msg "Extracted symbols with clj-kondo"
                         :data {:ns ns-name
                                :var-count (count var-symbols)
                                :defmethod-count (count defmethod-symbols)
                                :protocol-impl-count (count protocol-impl-symbols)
                                :top-level-count (count top-level-symbols)
                                :total (count all-symbols)
                                :sort-mode sort-mode}})
              all-symbols)
      ;; Fallback - return nil to signal caller should use LSP
            (do
             (log/log! {:level :info
                        :id ::kondo-fallback
                        :msg "Falling back to LSP for symbols"
                        :data {:ns ns-name}})
             nil))))

;; =============================================================================
;; Data Fetching from clojure-lsp
;; =============================================================================

(defn fetch-all-symbols
  "Fetch all symbols from clojure-lsp workspace.
   Returns raw LSP symbols with kind, name, location.
   Returns [] on error (including LSP parse errors from NUL byte corruption)."
  []
  (try
   (let [result (lsp-client/request! "workspace/symbol" {:query ""})]
     ;; Check for LSP error response (e.g., from NUL byte corruption)
     (if (:error result)
       (do
        (log/log! {:level :warn
                   :id ::fetch-all-symbols-lsp-error
                   :msg "LSP returned error, returning empty symbols"
                   :data {:error (:error result)}})
        [])
       (do
        (log/log! {:level :info
                   :id ::fetch-all-symbols
                   :msg "Fetched workspace symbols"
                   :data {:count (count result)}})
        result)))
   (catch Exception e
          (log/log! {:level :error
                     :id ::fetch-all-symbols-error
                     :msg "Failed to fetch symbols"
                     :data {:error (.getMessage e)}})
          [])))

(defn extract-namespaces
  "Extract unique namespaces from LSP symbols.
   Filters for kind=3 (namespace) and returns sorted list."
  [symbols]
  (->> symbols
       (filter #(= 3 (:kind %)))
       (map :name)
       (sort)
       vec))

(defn- detect-kind
  "Map LSP symbol kind number to keyword.
   Note: clojure-lsp reports both 'def' and 'declare' as kind 13 (Variable).
   We can't reliably distinguish them without parsing, so we use :variable for all."
  [sym]
  (let [kind-num (:kind sym)]
    (get symbol-kind->name kind-num :unknown)))

(defn extract-ns-symbols
  "Extract symbols belonging to a specific namespace.
   Uses file location to group symbols.
   Sorts according to current :sort-mode in state."
  [symbols file-uri]
  (let [sort-mode (:sort-mode @!code-browser-state :file-order)]
    (->> symbols
         ;; Filter to symbols in the same file (excluding the ns declaration itself)
         (filter (fn [sym]
                   (and (= file-uri (get-in sym [:location :uri]))
                        (not= 3 (:kind sym)))))  ; Exclude namespace
         (map (fn [sym]
                {:name (:name sym)
                 :kind (detect-kind sym)
                 :line (inc (get-in sym [:location :range :start :line] 0))
                 :column (inc (get-in sym [:location :range :start :character] 0))}))
         (sort-symbols sort-mode)
         vec)))

(defn get-namespace-file
  "Get file URI for a namespace from symbols."
  [symbols ns-name]
  (->> symbols
       (filter #(and (= 3 (:kind %)) (= ns-name (:name %))))
       first
       :location
       :uri))

(defn fetch-file-content
  "Read file content from disk.
   uri should be file:// URI."
  [uri]
  (try
   (let [path (str/replace uri "file://" "")
         file (io/file path)]
     (when (.exists file)
       (slurp file)))
   (catch Exception e
          (log/log! {:level :error
                     :id ::read-file-error
                     :msg "Failed to read file"
                     :data {:uri uri :error (.getMessage e)}})
          nil)))

(defn extract-source-region
  "Extract lines from source code.
   start-line and end-line are 1-indexed, inclusive."
  [content start-line end-line]
  (when content
    (let [lines (str/split-lines content)
          ;; Adjust to 0-indexed
          start-idx (max 0 (dec start-line))
          end-idx (min (count lines) end-line)]
      (str/join "\n" (subvec (vec lines) start-idx end-idx)))))

(defn- find-form-end-line
  "Find the end line of a form by scanning for balanced parentheses.
   Starts at start-line (1-indexed) and scans forward until parens balance.
   Returns the line number (1-indexed) where the form ends.
   This handles cases where kondo only gives start position (e.g., defmethod)."
  [content start-line]
  (when content
    (let [lines (vec (str/split-lines content))
          start-idx (dec start-line)]
      (loop [line-idx start-idx
             depth 0
             seen-open? false]
            (if (>= line-idx (count lines))
          ;; Ran off end - return last line
              (count lines)
              (let [line (nth lines line-idx)
                ;; Count parens on this line (simple - doesn't handle strings/comments)
                    open-count (count (filter #(= \( %) line))
                    close-count (count (filter #(= \) %) line))
                    new-depth (+ depth open-count (- close-count))
                    saw-open? (or seen-open? (pos? open-count))]
                (if (and saw-open? (<= new-depth 0))
              ;; Found balanced end - return 1-indexed line number
                  (inc line-idx)
                  (recur (inc line-idx) new-depth saw-open?))))))))

;; =============================================================================
;; Handler Functions
;; =============================================================================

(defn handle-request-namespaces
  "Handle request for namespace list.
   Updates synced atom AND returns response (parallel mode).
   Also cleans up stale cached data for namespaces that no longer exist.
   Returns {:namespaces [string ...]}."
  [_data]
  (log/log! {:level :info
             :id ::request-namespaces
             :msg "Handling namespace list request"})
  (let [symbols (fetch-all-symbols)
        namespaces (extract-namespaces symbols)
        namespace-set (set namespaces)]
    ;; Update synced atom and clean up stale cached data
    (swap! !code-browser-state
           (fn [state]
             (let [cached-ns-keys (set (keys (:symbols-by-ns state)))
                   stale-namespaces (set/difference cached-ns-keys namespace-set)]
               (when (seq stale-namespaces)
                 (log/log! {:level :info
                            :id ::removing-stale-namespaces
                            :msg "Removing stale namespace caches"
                            :data {:stale stale-namespaces}}))
               (-> state
                   (assoc :namespaces namespaces
                          :loading? false)
                   ;; Remove symbols cache for stale namespaces
                   (update :symbols-by-ns #(apply dissoc % stale-namespaces))
                   ;; Remove source cache for vars in stale namespaces
                   (update :source-by-var
                           (fn [source-cache]
                             (into {}
                                   (remove (fn [[var-key _]]
                                             (some #(str/starts-with? var-key (str % "/"))
                                                   stale-namespaces))
                                           source-cache))))))))
    ;; Also return response for legacy event mechanism
    {:namespaces namespaces
     :count (count namespaces)}))

(defn handle-request-symbols
  "Handle request for symbols in a namespace.
   Updates synced atom AND returns response (parallel mode).
   data: {:ns string, :preserve-selection? bool}
   When :preserve-selection? is true, keeps :selected-symbol intact (for file-change re-fetches).
   Returns {:symbols [...] :file string}."
  [{:keys [ns preserve-selection?]}]
  (log/log! {:level :info
             :id ::request-symbols
             :msg "Handling symbols request"
             :data {:ns ns :preserve-selection? preserve-selection?}})
  (let [symbols (fetch-all-symbols)
        file-uri (get-namespace-file symbols ns)]
    (if file-uri
      ;; Try clj-kondo first for rich classification, fall back to LSP
      (let [ns-symbols (or (extract-ns-symbols-kondo file-uri ns)
                           (extract-ns-symbols symbols file-uri))
            symbol-names (set (map :name ns-symbols))]
        ;; Update synced atom - ACCUMULATE symbols by namespace
        (swap! !code-browser-state
               (fn [state]
                 (let [selected (:selected-symbol state)
                       selected-ns-for-var (:selected-ns state)
                       ;; Check if selected symbol still exists in new symbol list
                       symbol-still-exists? (and selected (contains? symbol-names selected))
                       ;; Keep selection only if: preserving AND symbol still exists
                       keep-selection? (and preserve-selection? symbol-still-exists?)
                       ;; Build var-key for source cache cleanup
                       var-key (when selected (str selected-ns-for-var "/" selected))]
                   (when (and preserve-selection? selected (not symbol-still-exists?))
                     (log/log! {:level :info
                                :id ::selected-symbol-removed
                                :msg "Selected symbol no longer exists, clearing selection and source"
                                :data {:selected selected :ns ns :var-key var-key}}))
                   (-> state
                       (assoc :selected-ns ns
                              :loading? false)
                       ;; Clear selection if: switching ns, OR symbol was deleted/renamed
                       (cond-> (not keep-selection?)
                               (-> (assoc :selected-symbol nil)
                                   ;; Also clear the cached source for removed symbol
                                   (update :source-by-var dissoc var-key)))
                       (assoc-in [:symbols-by-ns ns] ns-symbols)))))
        ;; Also return response for legacy event mechanism
        {:ns ns
         :file file-uri
         :symbols ns-symbols})
      (do
       ;; Update synced atom with error state
       (swap! !code-browser-state assoc
              :selected-ns ns
              :error (str "Namespace not found: " ns)
              :loading? false)
       {:ns ns
        :error (str "Namespace not found: " ns)}))))

(defn handle-request-source
  "Handle request for source code.
   Updates synced atom AND returns response (parallel mode).
   data: {:file string :start-line int :end-line int}
   Returns {:code string :file string :language string}."
  [{:keys [file start-line end-line]}]
  (log/log! {:level :info
             :id ::request-source
             :msg "Handling source request"
             :data {:file file :start-line start-line :end-line end-line}})
  (let [content (fetch-file-content file)]
    (if content
      (let [code (if (and start-line end-line)
                   (extract-source-region content start-line end-line)
                   content)
            source-data {:code code
                         :file file
                         :language "clojure"
                         :start-line (or start-line 1)
                         :end-line (or end-line (count (str/split-lines content)))}]
        ;; Update synced atom (browsers will receive via atom-sync)
        (swap! !code-browser-state assoc
               :source source-data
               :loading? false)
        ;; Also return response for legacy event mechanism
        source-data)
      (do
       ;; Update synced atom with error state
       (swap! !code-browser-state assoc
              :error (str "File not found: " file)
              :loading? false)
       {:error (str "File not found: " file)}))))

(defn- find-cached-symbol
  "Find symbol in cached symbols-by-ns.
   Returns symbol map with :line/:end-line if found, nil otherwise.
   This is preferred over LSP lookup for kondo-derived symbols (defmethod, etc.)."
  [ns-name var-name kind]
  (let [cached-symbols (get-in @!code-browser-state [:symbols-by-ns ns-name] [])]
    (->> cached-symbols
         (filter #(and (= var-name (:name %))
                       (or (nil? kind) (= kind (:kind %)))))
         first)))

(defn handle-request-var-source
  "Handle request for source of a specific var.
   Updates synced atom AND returns response (parallel mode).
   data: {:ns string :var-name string :kind keyword}
   First checks cached symbols (kondo data with accurate line ranges),
   falls back to clojure-lsp lookup if not found.
   Uses :kind to disambiguate when multiple symbols have same name."
  [{:keys [ns var-name kind]}]
  (log/log! {:level :info
             :id ::request-var-source
             :msg "Handling var source request"
             :data {:ns ns :var-name var-name :kind kind}})
  (let [symbols (fetch-all-symbols)
        file-uri (get-namespace-file symbols ns)
        file-path (when file-uri (str/replace file-uri "file://" ""))
        ;; First check cached symbol data (kondo-derived, has accurate line ranges)
        cached-sym (find-cached-symbol ns var-name kind)]
    (if-not file-path
      (do
       ;; Update synced atom with error state
       (swap! !code-browser-state assoc
              :error (str "Namespace not found: " ns)
              :loading? false)
       {:error (str "Namespace not found: " ns)})
      ;; Use cached symbol if available, otherwise fall back to LSP lookup
      (let [;; If we have cached symbol with line info, use it
            ;; Otherwise fall back to LSP lookup
            [start-line end-line]
            (if (and cached-sym (:line cached-sym) (:end-line cached-sym))
              ;; Use cached kondo data (already 1-based)
              [(:line cached-sym) (:end-line cached-sym)]
              ;; Fall back to LSP lookup
              (let [matching (->> symbols
                                  (filter #(and (= file-uri (get-in % [:location :uri]))
                                                (= var-name (:name %)))))
                    var-sym (if kind
                              (let [kind-num (case kind
                                               :declare 13
                                               :function 12
                                               :variable 13
                                               nil)]
                                (or (->> matching
                                         (filter #(= kind-num (:kind %)))
                                         first)
                                    (first matching)))
                              (first matching))]
                (when var-sym
                  ;; LSP lines are 0-based, convert to 1-based
                  [(inc (get-in var-sym [:location :range :start :line] 0))
                   (inc (get-in var-sym [:location :range :end :line] 0))])))]
        (if-not (and start-line end-line)
          (do
           ;; Update synced atom with error state
           (swap! !code-browser-state assoc
                  :error (str "Var not found: " ns "/" var-name)
                  :loading? false)
           {:error (str "Var not found: " ns "/" var-name)})
          (let [content (fetch-file-content file-uri)
                ;; For defmethod/top-level-forms, end-line from kondo only covers the name.
                ;; If end-line == start-line, scan for balanced parens to find actual end.
                actual-end-line (if (= start-line end-line)
                                  (find-form-end-line content start-line)
                                  end-line)
                code (extract-source-region content start-line actual-end-line)
                var-key (str ns "/" var-name)
                ;; Phase 1.5E.12: Calculate highlight lines for protocol impls/methods
                ;; If cached symbol has :method-line/:method-end-line, compute relative lines
                method-line (:method-line cached-sym)
                method-end-line (:method-end-line cached-sym)
                highlight-line (when (and method-line
                                          (>= method-line start-line)
                                          (<= method-line actual-end-line))
                                 ;; Convert absolute line to 1-based line within extracted source
                                 (- method-line (dec start-line)))
                highlight-end-line (when (and method-end-line
                                              (>= method-end-line start-line)
                                              (<= method-end-line actual-end-line))
                                     (- method-end-line (dec start-line)))
                source-data (cond-> {:code code
                                     :file file-uri
                                     :ns ns
                                     :var-name var-name
                                     :start-line start-line
                                     :end-line actual-end-line
                                     :language "clojure"}
                              ;; Include highlight lines if available (Phase 1.5E.12)
                                    highlight-line (assoc :highlight-line highlight-line)
                                    highlight-end-line (assoc :highlight-end-line highlight-end-line))
                ;; Get cached source to compare
                cached-code (get-in @!code-browser-state [:source-by-var var-key :code])
                source-changed? (not= code cached-code)]
            ;; Update synced atom - ACCUMULATE source by qualified var name
            ;; Only update if source actually changed to prevent unnecessary UI updates
            (swap! !code-browser-state
                   (fn [state]
                     (-> state
                         (assoc :selected-symbol var-name
                                :loading? false)
                         ;; Only update source-by-var if code changed
                         (cond-> source-changed?
                                 (assoc-in [:source-by-var var-key] source-data)))))
            (when source-changed?
              (log/log! {:level :info
                         :id ::source-changed
                         :msg "Source code changed, updating cache"
                         :data {:var-key var-key}}))
            ;; Also return response for legacy event mechanism
            source-data))))))

(defn handle-clear-error
  "Handle request to clear error state.
   Updates synced atom to remove error."
  [_data]
  (log/log! {:level :info
             :id ::clear-error
             :msg "Clearing error state"})
  (swap! !code-browser-state dissoc :error)
  {:cleared true})

(defn handle-toggle-sort-mode
  "Handle request to toggle symbol sort mode.
   Toggles between :alpha and :file-order.
   Re-sorts the currently displayed symbols immediately.
   Note: Top-level forms (Phase 1.5E.9) are filtered browser-side based on sort-mode."
  [_data]
  (let [current-mode (:sort-mode @!code-browser-state :file-order)
        new-mode (if (= current-mode :alpha) :file-order :alpha)]
    (log/log! {:level :info
               :id ::toggle-sort-mode
               :msg "Toggling sort mode"
               :data {:from current-mode :to new-mode}})
    ;; Update mode and re-sort cached symbols
    (swap! !code-browser-state
           (fn [state]
             (-> state
                 (assoc :sort-mode new-mode)
                 ;; Re-sort all cached symbols
                 (update :symbols-by-ns
                         (fn [symbols-map]
                           (into {}
                                 (map (fn [[ns-name syms]]
                                        [ns-name (vec (sort-symbols new-mode syms))])
                                      symbols-map)))))))
    {:sort-mode new-mode}))

(defn handle-set-sort-mode
  "Handle request to set symbol sort mode to a specific value.
   data: {:mode :alpha|:file-order}
   Re-sorts the currently displayed symbols immediately."
  [{:keys [mode]}]
  (let [new-mode (if (#{:alpha :file-order} mode) mode :file-order)]
    (log/log! {:level :info
               :id ::set-sort-mode
               :msg "Setting sort mode"
               :data {:mode new-mode}})
    ;; Update mode and re-sort cached symbols
    (swap! !code-browser-state
           (fn [state]
             (-> state
                 (assoc :sort-mode new-mode)
                 ;; Re-sort all cached symbols
                 (update :symbols-by-ns
                         (fn [symbols-map]
                           (into {}
                                 (map (fn [[ns-name syms]]
                                        [ns-name (vec (sort-symbols new-mode syms))])
                                      symbols-map)))))))
    {:sort-mode new-mode}))

;; =============================================================================
;; Event Dispatch
;; =============================================================================

(defn dispatch-event
  "Dispatch code browser event, return response data.
   Returns nil if event-id not handled."
  [event-id data]
  (when @!enabled
    (case event-id
      :code-browser/request-namespaces
      [:code-browser/namespaces (handle-request-namespaces data)]

      :code-browser/request-symbols
      [:code-browser/symbols (handle-request-symbols data)]

      :code-browser/request-source
      [:code-browser/source (handle-request-source data)]

      :code-browser/request-var-source
      [:code-browser/var-source (handle-request-var-source data)]

      :code-browser/clear-error
      [:code-browser/error-cleared (handle-clear-error data)]

      :code-browser/toggle-sort-mode
      [:code-browser/sort-mode-changed (handle-toggle-sort-mode data)]

      :code-browser/set-sort-mode
      [:code-browser/sort-mode-changed (handle-set-sort-mode data)]

      ;; Not a code-browser event
      nil)))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn enable!
  "Enable code browser handlers and register synced atom.
   Idempotent - safe to call multiple times."
  []
  (when-not @!enabled
    (reset! !enabled true)
    ;; Register atom for sync - browsers will receive updates automatically
    (atom-sync/register-synced-atom! :code-browser !code-browser-state)
    ;; Register for file change notifications (Phase 1.5-Watch)
    (lsp-client/on-notification! :code-browser on-lsp-notification!)
    ;; Fetch initial git info (Phase 1.5E.2)
    (refresh-git-info!)
    (log/log! {:level :info
               :id ::enabled
               :msg "Code browser handlers enabled"
               :data {:synced-atom-key :code-browser
                      :file-watching true}})))

(defn disable!
  "Disable code browser handlers and unregister synced atom."
  []
  (when @!enabled
    (reset! !enabled false)
    ;; Unregister atom from sync
    (atom-sync/unregister-synced-atom! :code-browser)
    ;; Unregister file change notification callback (Phase 1.5-Watch)
    (lsp-client/remove-notification-callback! :code-browser)
    ;; Unregister on-connect callback
    (atom-sync-server/unregister-on-connect! :code-browser)
    ;; Reset state (Phase 1.5-Acc shape)
    (reset! !code-browser-state
            {:namespaces []
             :selected-ns nil
             :symbols-by-ns {}
             :selected-symbol nil
             :source-by-var {}
             :sort-mode :file-order
             :git nil
             :loading? false
             :error nil})
    (log/log! {:level :info
               :id ::disabled
               :msg "Code browser handlers disabled"})))

(defn- ensure-lsp-initialized!
  "Ensure clojure-lsp is initialized. If not, start initialization in background.
   Non-blocking - returns immediately, init happens async.
   Uses current working directory as project root."
  []
  (when-not (lsp-server/initialized?)
    (let [project-root (System/getProperty "user.dir")]
      (log/log! {:level :info
                 :id ::auto-init-lsp
                 :msg "Auto-initializing clojure-lsp for code browser"
                 :data {:project-root project-root}})
      ;; Run in background thread to avoid blocking browser connection
      (future
       (try
        (lsp-server/init! {:project-root project-root})
        (log/log! {:level :info
                   :id ::auto-init-lsp-complete
                   :msg "clojure-lsp auto-initialization complete"})
        ;; Auto-start file watcher for live updates
        (try
         (lsp-watcher/start!)
         (log/log! {:level :info
                    :id ::auto-watcher-started
                    :msg "File watcher auto-started for live updates"})
         (catch Exception e
                (log/log! {:level :warn
                           :id ::auto-watcher-failed
                           :msg "File watcher auto-start failed (non-fatal)"
                           :data {:error (ex-message e)}})))
        (catch Exception e
               (log/log! {:level :warn
                          :id ::auto-init-lsp-error
                          :msg "clojure-lsp auto-initialization failed"
                          :data {:error (ex-message e)}})))))))

(defn- auto-enable-on-connect!
  "On-connect callback that auto-enables code browser.
   Called when a browser connects, before initial sync.
   Also ensures clojure-lsp is initialized (async, non-blocking)."
  [_sente-conn-id]
  (enable!)
  (ensure-lsp-initialized!))

(defn register-auto-enable!
  "Register the on-connect callback for automatic code browser initialization.
   When any browser connects, code browser will be auto-enabled.
   Call this once at module load time."
  []
  (atom-sync-server/register-on-connect! :code-browser auto-enable-on-connect!)
  (log/log! {:level :info
             :id ::auto-enable-registered
             :msg "Code browser auto-enable registered"
             :data {:callback-key :code-browser}}))

;; =============================================================================
;; Auto-Registration at Load Time
;; =============================================================================

;; Register the on-connect callback when this namespace is loaded.
;; This enables reactive auto-initialization: first browser connect triggers
;; code browser enable + clojure-lsp init automatically.
(register-auto-enable!)