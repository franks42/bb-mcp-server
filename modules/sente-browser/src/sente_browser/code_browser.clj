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
   - :code-browser/list-directory {:path string :show-hidden boolean} ; Phase 1.5E.16
   - :code-browser/list-home {:show-hidden boolean}                   ; Phase 1.5E.16
   - :code-browser/find-projects {:path string}                       ; Phase 1.5E.16
   - :code-browser/get-breadcrumbs {:path string}                     ; Phase 1.5E.16

   Events to browser (legacy, kept during migration):
   - :code-browser/namespaces {:namespaces [...]}
   - :code-browser/symbols {:symbols [...]}
   - :code-browser/source {:code string :file string :language string}
   - :code-browser/sort-mode-changed {:sort-mode keyword}
   - :code-browser/directory-listing {:path :parent :entries :properties :error}
   - :code-browser/projects-found {:path :projects}
   - :code-browser/breadcrumbs {:path :breadcrumbs}

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
              [babashka.fs :as fs]
              [babashka.process :refer [shell]]
              [bb-mcp-server.modules.clojure-lsp.client :as lsp-client]
              [bb-mcp-server.modules.clojure-lsp.server :as lsp-server]
              [bb-mcp-server.modules.clojure-lsp.watcher :as lsp-watcher]
              [clojure.edn :as edn]
              [clojure.java.io :as io]
              [clojure.set :as set]
              [clojure.string :as str]
              [directory-browser.core :as dir-browser]
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
                     (update :var-usages-by-ns dissoc probable-ns)  ;; Phase 1.5E.10
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
;;   :var-usages-by-ns {ns-name [usages...]}  - for deps/dependents (Phase 1.5E.10)
;;   :ns-usages-by-ns {ns-name [ns-usages...]} - for NS deps/aliases (Phase 1.5E.19/20)
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !code-browser-state
         (atom {:namespaces []
                :selected-ns nil
                :symbols-by-ns {}      ;; Accumulated: {ns [symbols...]}
                :var-usages-by-ns {}   ;; Accumulated: {ns [usages...]} (Phase 1.5E.10)
                :ns-usages-by-ns {}    ;; Accumulated: {ns [ns-usages...]} (Phase 1.5E.19/20)
                :ns-file-counts {}     ;; Phase 1.5E.11: {ns {:file-count N :files [uri...]}}
                :ns-files {}           ;; Phase 1.5E.11: {ns [file1 file2...]} - ALL namespaces
                :selected-symbol nil
                :source-by-var {}      ;; Accumulated: {"ns/var" {:code ...}}
                :sort-mode :file-order ;; :alpha or :file-order (Phase 1.5E.1)
                :git nil               ;; Git info: {:project-root :branch :dirty? :upstream}
                :projects []           ;; Phase 1.5E.3: Configured project paths
                :current-project nil   ;; Phase 1.5E.3: Current project root path
                ;; Phase 1.5E.18: Lazy JAR Dependency Exploration
                :ns->jar {}            ;; Mapping: {"cheshire.core" "/path/to/cheshire.jar"}
                :jar-analyses {}       ;; Cached: {"/path/to.jar" {:var-definitions [...]}}
                :explored-deps []      ;; List of explored JAR namespace names
                :loading? false
                :error nil}))

;; Phase 1.5E.20: Core vars for shadow detection
;; Dynamically computed from running Clojure/Babashka - always up-to-date
(def ^:private core-var-names
     "Set of clojure.core public var names (as strings) for shadow detection."
     (set (map str (keys (ns-publics 'clojure.core)))))

;; =============================================================================
;; Phase 1.5E.11: Multi-File Namespace Detection (using clj-kondo)
;; =============================================================================

(defn- uri->filename
  "Extract short path from file URI for display.
   Shows grandparent/parent/filename.clj to distinguish files with same name.
   e.g., file:///modules/claude-manager/test/mock_claude.clj -> claude-manager/test/mock_claude.clj"
  [uri]
  (when uri
    (let [parts (str/split uri #"/")
          n (count parts)]
      (cond
        (>= n 3) (str (nth parts (- n 3)) "/" (nth parts (- n 2)) "/" (nth parts (- n 1)))
        (>= n 2) (str (nth parts (- n 2)) "/" (nth parts (- n 1)))
        :else (last parts)))))

(defn- analyze-project-with-kondo
  "Run clj-kondo on src and test directories to get all var-definitions.
   Returns map with :var-definitions containing all vars with their :ns field.
   This correctly identifies namespaces even for (in-ns ...) patterns."
  []
  (try
   (let [;; Analyze src and test directories
         result (shell {:out :string :err :string :continue true}
                       "clj-kondo" "--lint" "src" "--lint" "test" "--lint" "modules"
                       "--config" "{:output {:analysis {:var-definitions true} :format :edn}}")
         output (:out result)]
     (when (seq output)
       (let [analysis (edn/read-string output)
             var-defs (get-in analysis [:analysis :var-definitions])]
         (log/log! {:level :debug
                    :id ::kondo-project-analysis
                    :msg "clj-kondo project analysis complete"
                    :data {:var-count (count var-defs)}})
         {:var-definitions var-defs})))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::kondo-project-error
                     :msg "clj-kondo project analysis failed"
                     :data {:error (ex-message e)}})
          nil)))

(defn- extract-namespaces-from-kondo-vars
  "Extract unique namespaces from clj-kondo var-definitions.
   Uses the :ns field which correctly identifies (in-ns ...) patterns."
  [var-defs]
  (->> var-defs
       (map :ns)
       (filter some?)
       (map str)
       distinct
       sort
       vec))

(defn- compute-ns-file-counts-kondo
  "Compute file counts per namespace from clj-kondo var-definitions.
   Uses :ns and :filename fields which correctly handle (in-ns ...) patterns.
   Returns map of {ns-name {:file-count N :files [filename1 filename2 ...]}}
   for namespaces with > 1 file."
  [var-defs]
  (let [ns-files (->> var-defs
                      (filter (fn [v] (and (:ns v) (:filename v))))
                      (group-by :ns)
                      (map (fn [[ns-name vars]]
                             (let [files (->> vars
                                              (map :filename)
                                              distinct
                                              vec)]
                               [(str ns-name) {:file-count (count files)
                                               :files files}])))
                      (into {}))]
    ;; Only keep namespaces with > 1 file
    (into {} (filter (fn [[_ data]] (> (:file-count data) 1)) ns-files))))

(defn- compute-ns-files-kondo
  "Compute namespace → files mapping for ALL namespaces from clj-kondo var-definitions.
   Uses :ns and :filename fields which correctly handle (in-ns ...) patterns.
   Returns map of {ns-name [filename1 filename2 ...]} for ALL namespaces."
  [var-defs]
  (->> var-defs
       (filter (fn [v] (and (:ns v) (:filename v))))
       (group-by :ns)
       (map (fn [[ns-name vars]]
              (let [files (->> vars
                               (map :filename)
                               distinct
                               vec)]
                [(str ns-name) files])))
       (into {})))

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
   ;; Include :namespace-definitions for ns form display (Phase 1.5E.13)
   ;; Include :namespace-usages for NS deps and alias panel (Phase 1.5E.19/20)
   (let [result (shell {:out :string :err :string :continue true}
                       "clj-kondo" "--lint" file-path
                       "--config" "{:output {:analysis {:var-definitions true :var-usages true :protocol-impls true :namespace-definitions true :namespace-usages true} :format :edn}}")
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
             protocol-impls (get-in analysis [:analysis :protocol-impls])
             ns-defs (get-in analysis [:analysis :namespace-definitions])
             ns-usages (get-in analysis [:analysis :namespace-usages])]
         (log/log! {:level :debug
                    :id ::kondo-result
                    :msg "clj-kondo analysis complete"
                    :data {:file file-path
                           :var-count (count var-defs)
                           :usage-count (count var-usages)
                           :protocol-impl-count (count protocol-impls)
                           :ns-def-count (count ns-defs)
                           :ns-usage-count (count ns-usages)
                           :exit-code exit-code}})
         {:var-definitions var-defs
          :var-usages var-usages
          :protocol-impls protocol-impls
          :namespace-definitions ns-defs
          :namespace-usages ns-usages})))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::kondo-error
                     :msg "clj-kondo analysis failed"
                     :data {:file file-path :error (ex-message e)}})
          nil)))

(defn- kondo-var->symbol
  "Convert a clj-kondo var-definition to our symbol format.
   Phase 1.5E.11: Includes :filename for multi-file namespace display."
  [var-def]
  (let [defined-by (:defined-by var-def)
        kind (get defined-by->label defined-by :variable)
        ;; Extract just filename from full path for display
        filename (uri->filename (:filename var-def))]
    {:name (str (:name var-def))
     :kind kind
     :line (:row var-def)
     :column (:col var-def)
     :end-line (:end-row var-def)
     :end-column (:end-col var-def)
     :doc (:doc var-def)
     :defined-by (str defined-by)
     ;; Phase 1.5E.11: Include filename for multi-file display
     :filename filename
     ;; Preserve original column for protocol method detection
     :_orig-col (:col var-def)}))

;; -----------------------------------------------------------------------------
;; Phase 1.5E.6: defmethod extraction
;; -----------------------------------------------------------------------------

(defn- extract-defmethods
  "Extract defmethod implementations from var-usages.
   Returns symbols for each defmethod with dispatch value in name.
   Phase 1.5E.11: Includes :filename for multi-file namespace display."
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
               :multimethod (str (:name usage))
               ;; Phase 1.5E.11: Include filename
               :filename (uri->filename (:filename usage))}))))

;; -----------------------------------------------------------------------------
;; Phase 1.5E.9: top-level forms extraction
;; -----------------------------------------------------------------------------

;; Forms that indicate side-effects or non-defining top-level code
(def ^:private top-level-form-names
     #{'comment 'println 'prn 'print 'set! 'require 'import 'use 'do 'when 'if 'let
       'binding 'alter-var-root 'reset! 'swap!
       ;; Multi-file namespace forms (Phase 1.5E.11)
       'load 'load-file 'in-ns})

(defn- extract-top-level-forms
  "Extract non-defining top-level forms from var-usages.
   Only includes forms at column 1 (true top-level) with names in the allowlist.
   These are shown only in file-order view to reveal load-time behavior.
   Phase 1.5E.11: Includes :filename for multi-file namespace display."
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
                         (load load-file) :load
                         in-ns :in-ns
                         :form)
                 :line (:row usage)
                 :column (:col usage)
                 :end-line (:end-row usage)
                 :end-column (:end-col usage)
                 :form-type form-name
                 :top-level? true
                 ;; Phase 1.5E.11: Include filename
                 :filename (uri->filename (:filename usage))})))))

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
   Requires var-definitions to determine the implementing type name and bounds.
   Phase 1.5E.11: Includes :filename for multi-file namespace display."
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
                 :implementing-type (when type-name (str type-name))
                 ;; Phase 1.5E.11: Include filename
                 :filename (uri->filename (:filename impl))})))))

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
;; Phase 1.5E.18: Lazy JAR Dependency Exploration
;; =============================================================================

(defn- get-project-classpath
  "Get the classpath for the current project using clojure -Spath.
   Returns a vector of paths (JARs and directories) or nil if it fails."
  []
  (try
   (let [result (shell {:out :string :err :string :continue true :timeout 30000}
                       "clojure" "-Spath")]
     (when (zero? (:exit result))
       (let [cp (str/trim (:out result))]
         (when (seq cp)
           (str/split cp #":")))))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::classpath-error
                     :msg "Failed to get classpath"
                     :data {:error (ex-message e)}})
          nil)))

(defn- jar-entry->namespace
  "Convert a JAR entry path to a namespace name.
   e.g., 'cheshire/core.clj' -> 'cheshire.core'
   Returns nil if the entry is not a Clojure source file."
  [entry-name]
  (when (and entry-name
             (or (str/ends-with? entry-name ".clj")
                 (str/ends-with? entry-name ".cljs")
                 (str/ends-with? entry-name ".cljc"))
             (not (str/starts-with? entry-name "META-INF")))
    (-> entry-name
        (str/replace #"\.(clj|cljs|cljc)$" "")
        (str/replace "/" ".")
        (str/replace "_" "-"))))

(defn- scan-jar-namespaces
  "Quickly scan a JAR file to extract namespace names from entry paths.
   Returns a set of namespace name strings."
  [jar-path]
  (try
   (let [jar-file (java.util.jar.JarFile. (io/file jar-path))
         entries (enumeration-seq (.entries jar-file))]
     (->> entries
          (map #(.getName %))
          (keep jar-entry->namespace)
          set))
   (catch Exception e
          (log/log! {:level :debug
                     :id ::jar-scan-error
                     :msg "Failed to scan JAR"
                     :data {:jar jar-path :error (ex-message e)}})
          #{})))

(defn- build-ns->jar-mapping
  "Build a mapping of namespace names to JAR paths from the classpath.
   Only includes JARs, not directories (those are project sources).
   Returns {:ns->jar {\"cheshire.core\" \"/path/to/cheshire.jar\" ...}}."
  []
  (log/log! {:level :info
             :id ::building-ns-jar-mapping
             :msg "Building NS -> JAR mapping from classpath"})
  (let [classpath (get-project-classpath)
        jars (filter #(str/ends-with? % ".jar") classpath)]
    (when (seq jars)
      (log/log! {:level :debug
                 :id ::jar-count
                 :msg "Found JARs on classpath"
                 :data {:count (count jars)}})
      (let [ns->jar (reduce
                     (fn [acc jar-path]
                       (let [namespaces (scan-jar-namespaces jar-path)]
                         (reduce (fn [m ns-name]
                                   ;; First JAR wins (in case of duplicates)
                                   (if (contains? m ns-name)
                                     m
                                     (assoc m ns-name jar-path)))
                                 acc
                                 namespaces)))
                     {}
                     jars)]
        (log/log! {:level :info
                   :id ::ns-jar-mapping-complete
                   :msg "NS -> JAR mapping complete"
                   :data {:namespace-count (count ns->jar)
                          :jar-count (count jars)}})
        {:ns->jar ns->jar}))))

(defn- analyze-jar-with-kondo
  "Run clj-kondo analysis on a JAR file.
   Returns {:var-definitions [...] :namespace-definitions [...]} or nil."
  [jar-path]
  (log/log! {:level :info
             :id ::analyzing-jar
             :msg "Analyzing JAR with clj-kondo"
             :data {:jar jar-path}})
  (try
   (let [result (shell {:out :string :err :string :continue true :timeout 60000}
                       "clj-kondo" "--lint" jar-path
                       "--config" "{:output {:analysis {:var-definitions true :namespace-definitions true :var-usages true} :format :edn}}")
         output (:out result)]
     (when (seq output)
       (let [analysis (edn/read-string output)
             var-defs (get-in analysis [:analysis :var-definitions])
             ns-defs (get-in analysis [:analysis :namespace-definitions])
             var-usages (get-in analysis [:analysis :var-usages])]
         (log/log! {:level :info
                    :id ::jar-analysis-complete
                    :msg "JAR analysis complete"
                    :data {:jar jar-path
                           :var-count (count var-defs)
                           :ns-count (count ns-defs)}})
         {:var-definitions var-defs
          :namespace-definitions ns-defs
          :var-usages var-usages})))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::jar-analysis-error
                     :msg "JAR analysis failed"
                     :data {:jar jar-path :error (ex-message e)}})
          nil)))

(defn- read-source-from-jar
  "Read source code from a JAR file without extracting.
   entry-path should be like 'cheshire/core.clj'.
   Returns the source code string or nil."
  [jar-path entry-path]
  (try
   (let [jar-file (java.util.jar.JarFile. (io/file jar-path))
         entry (.getEntry jar-file entry-path)]
     (when entry
       (slurp (.getInputStream jar-file entry))))
   (catch Exception e
          (log/log! {:level :debug
                     :id ::jar-read-error
                     :msg "Failed to read from JAR"
                     :data {:jar jar-path :entry entry-path :error (ex-message e)}})
          nil)))

(defn- ns->jar-entry-path
  "Convert a namespace to a JAR entry path.
   e.g., 'cheshire.core' -> 'cheshire/core'
   Note: caller needs to try .clj, .cljs, .cljc extensions."
  [ns-name]
  (-> ns-name
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn- get-jar-for-namespace
  "Look up which JAR contains a namespace.
   Returns the JAR path or nil if not found."
  [ns-name]
  (get-in @!code-browser-state [:ns->jar ns-name]))

(defn- is-project-namespace?
  "Check if a namespace is part of the project (not from a JAR).
   Returns true if the namespace is in the project's namespace list."
  [ns-name]
  (let [namespaces (:namespaces @!code-browser-state)]
    (some #(= % ns-name) namespaces)))

(defn- ensure-jar-analyzed!
  "Ensure a JAR has been analyzed and cached.
   Returns the cached analysis or nil if analysis fails."
  [jar-path]
  (if-let [cached (get-in @!code-browser-state [:jar-analyses jar-path])]
          cached
          (when-let [analysis (analyze-jar-with-kondo jar-path)]
                    (swap! !code-browser-state assoc-in [:jar-analyses jar-path] analysis)
                    analysis)))

(defn- get-jar-namespace-symbols
  "Get symbols for a namespace from JAR analysis.
   Similar to project symbols but from JAR cache."
  [jar-path ns-name]
  (when-let [analysis (ensure-jar-analyzed! jar-path)]
            (let [ns-sym (symbol ns-name)
                  var-defs (:var-definitions analysis)
                  ns-vars (->> var-defs
                               (filter #(= ns-sym (:ns %))))]
              (mapv (fn [v]
                      {:name (str (:name v))
                       :kind (get defined-by->label (:defined-by v) :variable)
                       :line (:row v)
                       :end-line (:end-row v)
                       :filename (:filename v)
                       :doc (:doc v)
                       :arglists (:arglist-strs v)
                       :from-jar true
                       :jar-path jar-path})
                    ns-vars))))

(defn- get-jar-symbol-source
  "Get source code for a symbol from a JAR.
   Returns {:code string :file string :start-line int :end-line int} or nil."
  [jar-path ns-name _var-name start-line end-line]
  (let [base-path (ns->jar-entry-path ns-name)
        ;; Try each possible extension
        extensions [".cljc" ".clj" ".cljs"]
        entry-path (some (fn [ext]
                           (let [path (str base-path ext)]
                             (when (read-source-from-jar jar-path path)
                               path)))
                         extensions)]
    (when entry-path
      (let [full-source (read-source-from-jar jar-path entry-path)
            lines (str/split-lines full-source)
            ;; Extract just the lines we need (1-indexed)
            start-idx (max 0 (dec start-line))
            end-idx (min (count lines) end-line)
            source-lines (subvec (vec lines) start-idx end-idx)]
        {:code (str/join "\n" source-lines)
         :file (str "jar:" jar-path "!" entry-path)
         :start-line start-line
         :end-line end-line
         :from-jar true}))))

(defn- add-explored-dep!
  "Add a namespace to the explored dependencies list."
  [ns-name]
  (swap! !code-browser-state update :explored-deps
         (fn [deps]
           (if (some #(= % ns-name) deps)
             deps
             (conj (vec deps) ns-name)))))

(defn- initialize-ns->jar-mapping!
  "Initialize the NS -> JAR mapping at startup.
   Called when code browser is enabled."
  []
  (future
   (when-let [{:keys [ns->jar]} (build-ns->jar-mapping)]
             (swap! !code-browser-state assoc :ns->jar ns->jar)
             (log/log! {:level :info
                        :id ::ns-jar-mapping-initialized
                        :msg "NS -> JAR mapping initialized"
                        :data {:namespace-count (count ns->jar)}}))))

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

;; =============================================================================
;; Project Directory Management (Phase 1.5E.3)
;; =============================================================================

(defn- is-project-root?
  "Check if a directory is a Clojure/Babashka project root.
   Looks for: deps.edn, bb.edn, project.clj, or shadow-cljs.edn."
  [path]
  (when (and path (not (str/blank? path)))
    (let [dir (io/file path)]
      (and (.isDirectory dir)
           (some #(.exists (io/file dir %))
                 ["deps.edn" "bb.edn" "project.clj" "shadow-cljs.edn"])))))

(defn- project-basename
  "Extract project name from full path."
  [path]
  (when path
    (last (str/split path #"/"))))

(defn set-projects!
  "Set the list of available projects from config.
   Called during initialization with config from system.edn."
  [projects]
  (let [;; Filter to valid project roots
        valid-projects (filter is-project-root? projects)]
    (swap! !code-browser-state assoc :projects valid-projects)
    (log/log! {:level :info
               :id ::projects-configured
               :msg "Available projects configured"
               :data {:configured (count projects)
                      :valid (count valid-projects)
                      :projects (mapv project-basename valid-projects)}})))

(defn- clear-project-caches!
  "Clear all cached data when switching projects.
   Clears namespaces, symbols, sources - all project-specific data."
  []
  (swap! !code-browser-state assoc
         :namespaces []
         :selected-ns nil
         :symbols-by-ns {}
         :var-usages-by-ns {}
         :ns-usages-by-ns {}    ;; Phase 1.5E.19/20: NS-level deps/aliases
         :ns-files {}           ;; Phase 1.5E.11: ALL ns → files
         :ns-file-counts {}     ;; Phase 1.5E.11: Multi-file namespace info
         :selected-symbol nil
         :source-by-var {}))

(defn handle-set-project-root
  "Handle setting a new project root.
   Validates path, reinitializes LSP, clears caches, refreshes namespaces.
   data: {:path string}"
  [{:keys [path]}]
  (if-not (is-project-root? path)
    (do
     (swap! !code-browser-state assoc :error (str "Not a valid project: " path))
     {:success false :error "Not a valid project root"})
    (do
     (log/log! {:level :info
                :id ::set-project-root
                :msg "Setting project root"
                :data {:path path :name (project-basename path)}})
      ;; 1. Clear all caches
     (clear-project-caches!)
      ;; 2. Update current project in state
     (swap! !code-browser-state assoc :current-project path)
      ;; 3. Reinitialize LSP with new project (async)
     (future
      (try
        ;; Shutdown existing LSP if running
       (when (lsp-server/initialized?)
         (log/log! {:level :info :id ::lsp-shutdown :msg "Shutting down LSP for project switch"})
         (lsp-watcher/stop!)
         (lsp-server/stop!))
        ;; Init with new project
       (log/log! {:level :info :id ::lsp-reinit :msg "Reinitializing LSP" :data {:project path}})
       (lsp-server/init! {:project-root path})
        ;; Restart file watcher
       (lsp-watcher/start!)
       (log/log! {:level :info :id ::lsp-reinit-complete :msg "LSP reinitialized for new project"})
        ;; Refresh git info for new project
       (refresh-git-info!)
        ;; Auto-refresh namespaces after LSP is ready
       (handle-request-namespaces {})
       (catch Exception e
              (log/log! {:level :error
                         :id ::lsp-reinit-failed
                         :msg "LSP reinitialization failed"
                         :data {:error (ex-message e)}}))))
     {:success true :path path :name (project-basename path)})))

(defn handle-add-project
  "Handle adding a new project to the available projects list.
   Phase 1.5E.15: Validates directory exists and is a Clojure project.
   data: {:path string}"
  [{:keys [path]}]
  (let [trimmed-path (when path (str/trim path))]
    (cond
      ;; Empty path
      (or (nil? trimmed-path) (str/blank? trimmed-path))
      {:success false :error "Path cannot be empty"}

      ;; Not a directory
      (not (fs/directory? trimmed-path))
      (do
       (swap! !code-browser-state assoc :error (str "Not a directory: " trimmed-path))
       {:success false :error "Path is not a directory"})

      ;; Not a valid Clojure project
      (not (is-project-root? trimmed-path))
      (do
       (swap! !code-browser-state assoc :error (str "Not a Clojure project: " trimmed-path))
       {:success false :error "Not a valid Clojure project (no deps.edn, bb.edn, project.clj, or shadow-cljs.edn)"})

      ;; Already in list
      (some #(= % trimmed-path) (:projects @!code-browser-state))
      {:success false :error "Project already in list"}

      ;; Valid - add to list and auto-select
      :else
      (do
       (swap! !code-browser-state update :projects conj trimmed-path)
       (log/log! {:level :info
                  :id ::project-added
                  :msg "Project added to list"
                  :data {:path trimmed-path :name (project-basename trimmed-path)}})
        ;; Auto-select the newly added project (Phase 1.5E.16 enhancement)
        ;; This triggers LSP reinit and namespace loading
       (handle-set-project-root {:path trimmed-path})
       {:success true :path trimmed-path :name (project-basename trimmed-path)}))))

(defn- extract-ns-symbols-kondo
  "Extract symbols for a namespace using clj-kondo analysis.
   Falls back to LSP-based extraction if kondo fails.
   Sorts according to current :sort-mode in state.
   Returns {:symbols [...] :var-usages [...]} or nil if analysis fails.
   Includes:
   - namespace definition (ns form) - always first (Phase 1.5E.13)
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
                  {:keys [var-definitions var-usages protocol-impls
                          namespace-definitions namespace-usages]} analysis

            ;; 0. Namespace definition (Phase 1.5E.13)
            ;; Find the ns definition for this namespace, create symbol entry
                  ns-def (first (filter #(= ns-sym (:name %)) namespace-definitions))
                  ns-symbol (when ns-def
                              {:name ns-name
                               :kind :ns
                               :line (:row ns-def)
                               :end-line (:end-row ns-def)
                               :doc (:doc ns-def)
                               ;; Phase 1.5E.11: Include filename
                               :filename (uri->filename (:filename ns-def))})

            ;; 1. Var definitions (defn, def, etc.)
            ;; Filter to this namespace, then fix protocol method line ranges
                  ns-var-defs (filter #(= ns-sym (:ns %)) var-definitions)
                  var-symbols (->> ns-var-defs
                                   (map kondo-var->symbol)
                                   (#(fix-protocol-method-lines % var-definitions)))

            ;; 2. defmethod implementations (Phase 1.5E.6)
            ;; Filter to usages from this namespace
                  ns-var-usages (filter #(= ns-sym (:from %)) var-usages)
                  defmethod-symbols (->> ns-var-usages extract-defmethods)

            ;; 3. Protocol implementations (Phase 1.5E.7)
            ;; Filter to impls in this namespace, pass var-definitions for type lookup
                  ns-protocol-impls (filter #(= ns-sym (:impl-ns %)) protocol-impls)
                  protocol-impl-symbols (extract-protocol-impls ns-protocol-impls var-definitions)

            ;; 4. Top-level forms (Phase 1.5E.9)
            ;; Always include - browser filters in :alpha mode
            ;; Marked with :top-level? true for browser-side filtering
                  top-level-symbols (->> ns-var-usages extract-top-level-forms)

            ;; 5. Namespace usages (Phase 1.5E.19/20)
            ;; Filter to requires/refers from this namespace
            ;; Each has :from, :to, :alias (optional)
                  ns-ns-usages-raw (->> namespace-usages
                                        (filter #(= ns-sym (:from %)))
                                        (mapv (fn [u]
                                                {:to (str (:to u))
                                                 :alias (when (:alias u) (str (:alias u)))
                                                 :row (:row u)})))

            ;; 6. Derive refers from var-usages (Phase 1.5E.20)
            ;; Var usages with :refer true are explicitly referred symbols
            ;; Group by :to namespace, include shadow detection for clojure.core
                  refers-by-ns (->> ns-var-usages
                                    (filter :refer)
                                    (group-by #(str (:to %)))
                                    (reduce-kv (fn [m ns-str usages]
                                                 (assoc m ns-str
                                                        (->> usages
                                                             (map #(str (:name %)))
                                                             distinct
                                                             sort
                                                             (mapv (fn [sym-name]
                                                                     {:name sym-name
                                                                      :shadows-core? (contains? core-var-names sym-name)})))))
                                               {}))

            ;; Merge refers into ns-usages
                  ns-ns-usages (mapv (fn [u]
                                       (if-let [refers (get refers-by-ns (:to u))]
                                               (assoc u :refers refers)
                                               u))
                                     ns-ns-usages-raw)

            ;; Combine all symbols and sort (ns form always first)
                  sorted-symbols (->> (concat var-symbols defmethod-symbols
                                              protocol-impl-symbols top-level-symbols)
                                      (sort-symbols sort-mode)
                                      vec)
            ;; Prepend ns symbol if present (always first regardless of sort)
                  all-symbols (if ns-symbol
                                (vec (cons ns-symbol sorted-symbols))
                                sorted-symbols)]
              (log/log! {:level :info
                         :id ::kondo-symbols-extracted
                         :msg "Extracted symbols with clj-kondo"
                         :data {:ns ns-name
                                :has-ns-symbol (some? ns-symbol)
                                :var-count (count var-symbols)
                                :defmethod-count (count defmethod-symbols)
                                :protocol-impl-count (count protocol-impl-symbols)
                                :top-level-count (count top-level-symbols)
                                :ns-usages-count (count ns-ns-usages)
                                :total (count all-symbols)
                                :var-usages-count (count ns-var-usages)
                                :sort-mode sort-mode}})
        ;; Return symbols, var-usages, and ns-usages (Phase 1.5E.10, 1.5E.19/20)
              {:symbols all-symbols
               :var-usages (vec ns-var-usages)
               :ns-usages ns-ns-usages})
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
   Phase 1.5E.11: Now includes namespaces from containerName fields,
   which catches namespaces defined via (in-ns ...) that have no (ns ...) form.
   Returns sorted list of unique namespace names."
  [symbols]
  (let [;; Namespaces with explicit (ns ...) declarations (kind=3)
        ns-declarations (->> symbols
                             (filter #(= 3 (:kind %)))
                             (map :name))
        ;; Namespaces from symbol containerName (catches in-ns patterns)
        ns-from-containers (->> symbols
                                (map :containerName)
                                (filter some?))]
    (->> (concat ns-declarations ns-from-containers)
         distinct
         sort
         vec)))

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
   Phase 1.5E.11: Uses clj-kondo project analysis to correctly detect
   namespaces including those defined via (in-ns ...).
   Returns {:namespaces [string ...]}."
  [_data]
  (log/log! {:level :info
             :id ::request-namespaces
             :msg "Handling namespace list request"})
  ;; Use clj-kondo for namespace discovery (correctly handles in-ns patterns)
  (let [kondo-result (analyze-project-with-kondo)
        var-defs (:var-definitions kondo-result)
        ;; Fall back to LSP if kondo fails
        namespaces (if (seq var-defs)
                     (extract-namespaces-from-kondo-vars var-defs)
                     (let [symbols (fetch-all-symbols)]
                       (extract-namespaces symbols)))
        namespace-set (set namespaces)
        ;; Phase 1.5E.11: Compute namespace → files mappings from kondo data
        ns-files (if (seq var-defs)
                   (compute-ns-files-kondo var-defs)
                   {})
        ns-file-counts (if (seq var-defs)
                         (compute-ns-file-counts-kondo var-defs)
                         {})]
    ;; Log multi-file namespaces if any
    (when (seq ns-file-counts)
      (log/log! {:level :info
                 :id ::multi-file-namespaces-detected
                 :msg "Detected multi-file namespaces"
                 :data {:count (count ns-file-counts)
                        :namespaces (keys ns-file-counts)}}))
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
                          :ns-files ns-files              ;; Phase 1.5E.11: ALL ns → files
                          :ns-file-counts ns-file-counts  ;; Phase 1.5E.11: multi-file only
                          :loading? false)
                   ;; Remove symbols cache for stale namespaces
                   (update :symbols-by-ns #(apply dissoc % stale-namespaces))
                   ;; Remove var-usages cache for stale namespaces (Phase 1.5E.10)
                   (update :var-usages-by-ns #(apply dissoc % stale-namespaces))
                   ;; Remove ns-usages cache for stale namespaces (Phase 1.5E.19/20)
                   (update :ns-usages-by-ns #(apply dissoc % stale-namespaces))
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
     :count (count namespaces)
     :multi-file-count (count ns-file-counts)}))

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
  ;; Phase 1.5E.11: Use kondo's ns-files mapping first, fall back to LSP
  (let [state @!code-browser-state
        kondo-files (get-in state [:ns-files ns])
        ;; Fall back to LSP if kondo doesn't have the file
        file-uri (or (first kondo-files)
                     (let [symbols (fetch-all-symbols)]
                       (get-namespace-file symbols ns)))
        ;; For multi-file ns, we'll analyze all files
        all-files (or (seq kondo-files) (when file-uri [file-uri]))]
    (if (seq all-files)
      ;; Phase 1.5E.11: Analyze all files for the namespace and merge results
      ;; Try clj-kondo first for rich classification
      (let [kondo-results (mapv #(extract-ns-symbols-kondo % ns) all-files)
            ;; Merge results from all files
            merged-symbols (vec (mapcat :symbols kondo-results))
            merged-var-usages (vec (mapcat :var-usages kondo-results))
            merged-ns-usages (vec (mapcat :ns-usages kondo-results))
            ;; Use merged kondo results, or fall back to LSP for first file
            ns-symbols (if (seq merged-symbols)
                         merged-symbols
                         (let [lsp-symbols (fetch-all-symbols)]
                           (extract-ns-symbols lsp-symbols file-uri)))
            ns-var-usages (or (seq merged-var-usages) [])
            ns-ns-usages (or (seq merged-ns-usages) [])
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
                       (assoc-in [:symbols-by-ns ns] ns-symbols)
                       ;; Phase 1.5E.10: Store var-usages for deps/dependents
                       (assoc-in [:var-usages-by-ns ns] ns-var-usages)
                       ;; Phase 1.5E.19/20: Store ns-usages for aliases/deps
                       (assoc-in [:ns-usages-by-ns ns] ns-ns-usages)))))
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

;; =============================================================================
;; Phase 1.5E.10: Dependencies/Dependents Computation
;; =============================================================================

(defn- compute-dependencies
  "Compute what symbols a var calls (outgoing dependencies).
   Looks up var-usages from the symbol's namespace to find calls from this var.
   Returns [{:name \"fn\" :ns \"ns\" :line N} ...] for symbols called by var-name."
  [ns-name var-name]
  (let [var-usages (get-in @!code-browser-state [:var-usages-by-ns ns-name] [])]
    ;; Filter usages where :from-var matches var-name (this var calls others)
    (->> var-usages
         (filter #(= var-name (str (:from-var %))))
         (map (fn [u]
                {:name (str (:name u))
                 :ns (str (:to u))
                 :line (:row u)}))
         ;; Remove duplicates (same symbol may be called multiple times)
         (distinct)
         vec)))

(defn- compute-dependents
  "Compute what symbols call a var (incoming dependencies/callers).
   Searches all cached var-usages to find calls TO this var.
   Returns [{:name \"fn\" :ns \"ns\" :line N} ...] for symbols that call var-name."
  [ns-name var-name]
  (let [all-usages (mapcat (fn [[_ns usages]] usages)
                           (:var-usages-by-ns @!code-browser-state))]
    ;; Filter usages where target matches ns-name/var-name
    (->> all-usages
         (filter #(and (= (str ns-name) (str (:to %)))
                       (= var-name (str (:name %)))))
         (map (fn [u]
                {:name (str (:from-var u))
                 :ns (str (:from u))
                 :line (:row u)}))
         ;; Remove duplicates
         (distinct)
         vec)))

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

;; =============================================================================
;; Phase 1.5E.8: Protocol/Multimethod Implementations Computation
;; =============================================================================

(defn- compute-protocol-implementations
  "Find all implementations of a protocol or protocol method.
   For a protocol: finds all protocol-impls in any namespace that implement this protocol.
   For a protocol method: finds protocol-impls matching both protocol and method name.
   Returns [{:name \"method (Type)\" :ns \"impl-ns\" :line N :implementing-type \"Type\"} ...]."
  [protocol-name method-name]
  (let [all-symbols (mapcat (fn [[ns-key symbols]]
                              (map #(assoc % :_ns ns-key) symbols))
                            (:symbols-by-ns @!code-browser-state))]
    (->> all-symbols
         (filter #(= :protocol-impl (:kind %)))
         (filter (fn [sym]
                   (and (= protocol-name (:protocol sym))
                        (or (nil? method-name)
                            (= method-name (:method-name sym))))))
         (map (fn [sym]
                {:name (:name sym)
                 :ns (:_ns sym)
                 :line (:line sym)
                 :implementing-type (:implementing-type sym)
                 :method-name (:method-name sym)}))
         vec)))

(defn- compute-multimethod-implementations
  "Find all defmethod implementations for a defmulti.
   Searches all cached symbols for :method kind with matching :multimethod.
   Returns [{:name \"multimethod :dispatch\" :ns \"ns\" :line N :dispatch-val \"val\"} ...]."
  [multimethod-name]
  (let [all-symbols (mapcat (fn [[ns-key symbols]]
                              (map #(assoc % :_ns ns-key) symbols))
                            (:symbols-by-ns @!code-browser-state))]
    (->> all-symbols
         (filter #(= :method (:kind %)))
         (filter #(= multimethod-name (:multimethod %)))
         (map (fn [sym]
                {:name (:name sym)
                 :ns (:_ns sym)
                 :line (:line sym)
                 :dispatch-val (:dispatch-val sym)}))
         vec)))

(defn- compute-implementations
  "Compute implementations for a symbol.
   For protocols: finds all protocol method implementations.
   For protocol methods (in defprotocol): finds implementations of that method.
   For defmulti: finds all defmethod implementations.
   For protocol-impl: returns link to protocol definition.
   For defmethod: returns link to defmulti definition.
   Returns {:implementations [...] :definition {...}} or nil if not applicable."
  [cached-sym ns-name var-name]
  (let [kind (:kind cached-sym)]
    (case kind
      ;; Protocol definition - find all implementations of all its methods
      :protocol
      (let [;; Check if this is a protocol method (has :parent-protocol) or the protocol itself
            parent-protocol (:parent-protocol cached-sym)
            protocol-name (or parent-protocol var-name)
            ;; If it's a protocol method, search for that specific method
            method-name (when parent-protocol var-name)
            impls (compute-protocol-implementations protocol-name method-name)]
        (when (seq impls)
          {:implementations impls}))

      ;; defmulti - find all defmethod implementations
      :multimethod
      (let [impls (compute-multimethod-implementations var-name)]
        (when (seq impls)
          {:implementations impls}))

      ;; Protocol implementation - link back to protocol definition
      :protocol-impl
      (let [protocol-name (:protocol cached-sym)
            protocol-ns (:protocol-ns cached-sym)]
        (when (and protocol-name protocol-ns)
          {:definition {:name protocol-name
                        :ns protocol-ns
                        :type :protocol}}))

      ;; defmethod - link back to defmulti definition
      :method
      (let [multimethod-name (:multimethod cached-sym)]
        (when multimethod-name
          {:definition {:name multimethod-name
                        :ns ns-name
                        :type :multimethod}}))

      ;; Other symbol types - no implementations
      nil)))

(defn handle-request-var-source
  "Handle request for source of a specific var.
   Updates synced atom AND returns response (parallel mode).
   data: {:ns string :var-name string :kind keyword}
   First checks cached symbols (kondo data with accurate line ranges),
   falls back to clojure-lsp lookup if not found.
   Uses :kind to disambiguate when multiple symbols have same name.
   Phase 1.5E.18: Handles JAR namespaces specially since source files
   are inside JARs and can't be read directly."
  [{:keys [ns var-name kind]}]
  (log/log! {:level :info
             :id ::request-var-source
             :msg "Handling var source request"
             :data {:ns ns :var-name var-name :kind kind}})
  ;; Phase 1.5E.18C: Check if this is a JAR namespace - read source from JAR
  (if-not (is-project-namespace? ns)
    ;; JAR namespace - read source from JAR file
    (let [cached-sym (find-cached-symbol ns var-name kind)
          jar-path (get-jar-for-namespace ns)
          start-line (or (:line cached-sym) 1)
          end-line (or (:end-line cached-sym) start-line)
          source-data (when jar-path
                        (get-jar-symbol-source jar-path ns var-name start-line end-line))]
      (log/log! {:level :debug
                 :id ::jar-var-source
                 :msg "JAR namespace - reading source from JAR"
                 :data {:ns ns :var-name var-name :jar-path jar-path
                        :start-line start-line :end-line end-line
                        :found? (boolean source-data)}})
      (if source-data
        ;; Successfully read source from JAR
        (let [var-key (str ns "/" var-name)
              ;; Build source-data map matching browser expectations
              jar-source-data {:code (:code source-data)
                               :file (:file source-data)
                               :ns ns
                               :var-name var-name
                               :from-jar true
                               :start-line (:start-line source-data)
                               :end-line (:end-line source-data)}]
          (swap! !code-browser-state
                 (fn [state]
                   (-> state
                       (assoc :selected-symbol var-name
                              :loading? false)
                       (assoc-in [:source-by-var var-key] jar-source-data))))
          {:source (:code source-data) :ns ns :var-name var-name :from-jar true
           :file (:file source-data) :start-line start-line :end-line end-line})
        ;; Fallback if source reading fails
        (let [var-key (str ns "/" var-name)
              placeholder (str ";; Could not read source from JAR.\n;;\n"
                               ";; Symbol: " var-name "\n"
                               ";; Namespace: " ns "\n"
                               (when cached-sym
                                 (str ";; Type: " (:kind cached-sym) "\n"
                                      ";; Line: " start-line "-" end-line "\n"))
                               (when jar-path
                                 (str ";; JAR: " jar-path)))
              fallback-data {:code placeholder
                             :file nil
                             :ns ns
                             :var-name var-name
                             :from-jar true}]
          (swap! !code-browser-state
                 (fn [state]
                   (-> state
                       (assoc :selected-symbol var-name
                              :loading? false)
                       (assoc-in [:source-by-var var-key] fallback-data))))
          {:source placeholder :ns ns :var-name var-name :from-jar true})))
    ;; Project namespace - continue with normal logic
    ;; Phase 1.5E.11: Use kondo's ns-files mapping first, fall back to LSP
    (let [state @!code-browser-state
          kondo-files (get-in state [:ns-files ns])
          symbols (fetch-all-symbols)
          ;; Use kondo files first, fall back to LSP
          file-uri (or (first kondo-files)
                       (get-namespace-file symbols ns))
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
                ;; Phase 1.5E.10: Compute deps/dependents
                  dependencies (compute-dependencies ns var-name)
                  dependents (compute-dependents ns var-name)
                ;; Phase 1.5E.10: Get docstring from cached symbol
                  doc (:doc cached-sym)
                ;; Phase 1.5E.8: Compute implementations for protocols/multimethods
                  impl-data (when cached-sym (compute-implementations cached-sym ns var-name))
                  source-data (cond-> {:code code
                                       :file file-uri
                                       :ns ns
                                       :var-name var-name
                                       :start-line start-line
                                       :end-line actual-end-line
                                       :language "clojure"}
                              ;; Include highlight lines if available (Phase 1.5E.12)
                                      highlight-line (assoc :highlight-line highlight-line)
                                      highlight-end-line (assoc :highlight-end-line highlight-end-line)
                              ;; Phase 1.5E.10: Include doc and deps
                                      doc (assoc :doc doc)
                                      (seq dependencies) (assoc :dependencies dependencies)
                                      (seq dependents) (assoc :dependents dependents)
                              ;; Phase 1.5E.8: Include implementations/definition
                                      (:implementations impl-data) (assoc :implementations (:implementations impl-data))
                                      (:definition impl-data) (assoc :definition (:definition impl-data)))
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
              source-data)))))))

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

(defn handle-navigate-to-symbol
  "Handle navigation to a symbol in any namespace.
   Phase 1.5E.10.7: Click deps/callers -> navigate to that symbol.
   Phase 1.5E.18: Also supports JAR namespaces.
   data: {:ns string :name string}
   First fetches symbols for the namespace (if not cached), then fetches the source."
  [{:keys [ns name]}]
  (log/log! {:level :info
             :id ::navigate-to-symbol
             :msg "Navigating to symbol"
             :data {:ns ns :name name}})
  (if (is-project-namespace? ns)
    ;; Project namespace - use regular navigation
    (do
     (handle-request-symbols {:ns ns})
     (handle-request-var-source {:ns ns :var-name name :kind nil})
     {:navigated true :ns ns :name name :from-project true})
    ;; JAR namespace - use JAR exploration
    (if-let [jar-path (get-jar-for-namespace ns)]
            (do
             (log/log! {:level :debug
                        :id ::navigate-to-jar-symbol
                        :msg "Navigating to JAR symbol"
                        :data {:ns ns :name name :jar-path jar-path}})
        ;; Load JAR symbols if needed
             (let [symbols (get-jar-namespace-symbols jar-path ns)]
               (add-explored-dep! ns)
               ;; Update both :symbols and :symbols-by-ns so find-cached-symbol works
               (swap! !code-browser-state
                      (fn [state]
                        (-> state
                            (assoc :selected-ns ns)
                            (assoc :symbols symbols)
                            (assoc :selected-symbol name)
                            (assoc-in [:symbols-by-ns ns] (vec symbols)))))
               ;; Phase 1.5E.18C: Use handle-request-var-source which extracts JAR source
               ;; Find the symbol to get its line info for source extraction
               (when-let [sym (first (filter #(= (:name %) name) symbols))]
                         (handle-request-var-source {:ns ns
                                                     :var-name name
                                                     :kind (:kind sym)}))
               {:navigated true :ns ns :name name :from-jar true :jar-path jar-path}))
      ;; No JAR found
            (do
             (log/log! {:level :warn
                        :id ::jar-not-found-for-symbol
                        :msg "No JAR found for namespace"
                        :data {:ns ns}})
             (swap! !code-browser-state assoc
                    :error (str "Namespace not found: " ns))
             {:error (str "Namespace not found: " ns)}))))

;; =============================================================================
;; Phase 1.5E.18: JAR Dependency Exploration Handler
;; =============================================================================

(defn handle-explore-jar-dep
  "Handle request to explore a dependency from a JAR.
   data: {:ns string} - namespace name from var-usages :to field
   Analyzes the JAR if needed, caches the analysis, and returns symbols."
  [{:keys [ns]}]
  (log/log! {:level :info
             :id ::explore-jar-dep
             :msg "Exploring JAR dependency"
             :data {:ns ns}})
  (if (is-project-namespace? ns)
    ;; Project namespace - use regular navigation
    (do
     (log/log! {:level :debug
                :id ::jar-dep-is-project-ns
                :msg "Namespace is a project namespace, using regular navigation"
                :data {:ns ns}})
     (handle-request-symbols {:ns ns}))
    ;; External dep - try to find in JAR
    (if-let [jar-path (get-jar-for-namespace ns)]
            (let [symbols (get-jar-namespace-symbols jar-path ns)]
        ;; Add to explored deps list
              (add-explored-dep! ns)
        ;; Update state with JAR symbols
              (swap! !code-browser-state
                     (fn [state]
                       (-> state
                           (assoc :selected-ns ns)
                           (assoc :selected-symbol nil)
                           (assoc-in [:symbols-by-ns ns] (vec symbols)))))
              (log/log! {:level :info
                         :id ::jar-dep-explored
                         :msg "JAR dependency explored"
                         :data {:ns ns :jar jar-path :symbol-count (count symbols)}})
              {:ns ns :symbols symbols :from-jar true :jar-path jar-path})
      ;; No JAR found
            (do
             (log/log! {:level :warn
                        :id ::jar-not-found
                        :msg "No JAR found for namespace"
                        :data {:ns ns}})
             (swap! !code-browser-state assoc :error (str "Namespace not found in classpath JARs: " ns))
             {:error (str "Namespace not found: " ns)}))))

(defn handle-request-jar-source
  "Handle request for source code from a JAR.
   data: {:ns string :var-name string :line int :end-line int}
   Returns the source code from the JAR."
  [{:keys [ns var-name line end-line]}]
  (log/log! {:level :debug
             :id ::request-jar-source
             :msg "Requesting JAR source"
             :data {:ns ns :var var-name :line line :end-line end-line}})
  (if-let [jar-path (get-jar-for-namespace ns)]
          (if-let [source (get-jar-symbol-source jar-path ns var-name line end-line)]
                  (let [cache-key (str ns "/" var-name)]
                    (swap! !code-browser-state assoc-in [:source-by-var cache-key] source)
                    (swap! !code-browser-state assoc :selected-symbol var-name)
                    {:source source})
                  {:error "Source not found in JAR"})
          {:error "JAR not found for namespace"}))

;; =============================================================================
;; Directory Browser (Phase 1.5E.16)
;; =============================================================================

(defn- convert-sets-to-vecs
  "Convert sets to vectors for JSON serialization."
  [data]
  (cond
    (set? data) (vec data)
    (map? data) (into {} (map (fn [[k v]] [k (convert-sets-to-vecs v)]) data))
    (sequential? data) (mapv convert-sets-to-vecs data)
    :else data))

(defn handle-list-directory
  "Handle request to list directory contents.
   data: {:path string, :show-hidden boolean}
   Returns directory listing with properties and breadcrumbs."
  [{:keys [path show-hidden]}]
  (log/log! {:level :debug
             :id ::list-directory
             :msg "Listing directory"
             :data {:path path :show-hidden show-hidden}})
  (let [target-path (or path (dir-browser/home-directory))
        result (dir-browser/list-directory
                target-path
                {:show-hidden (boolean show-hidden)
                 :enrich true})
        ;; Add breadcrumbs
        breadcrumbs (dir-browser/breadcrumbs (:path result))
        ;; Convert sets to vectors for JSON serialization
        serializable (-> result
                         (assoc :breadcrumbs breadcrumbs)
                         convert-sets-to-vecs)]
    (if (:error result)
      (do
       (log/log! {:level :warn
                  :id ::list-directory-error
                  :msg "Directory listing failed"
                  :data {:path path :error (:error result)}})
       serializable)
      (do
       (log/log! {:level :debug
                  :id ::list-directory-success
                  :msg "Directory listed"
                  :data {:path (:path result)
                         :entry-count (count (:entries result))
                         :breadcrumb-count (count breadcrumbs)}})
       serializable))))

(defn handle-list-home
  "Handle request to list home directory.
   data: {:show-hidden boolean}"
  [{:keys [show-hidden]}]
  (log/log! {:level :debug
             :id ::list-home
             :msg "Listing home directory"})
  (let [result (dir-browser/list-home {:show-hidden (boolean show-hidden) :enrich true})
        breadcrumbs (dir-browser/breadcrumbs (:path result))]
    (-> result
        (assoc :breadcrumbs breadcrumbs)
        convert-sets-to-vecs)))

(defn handle-find-projects
  "Handle request to find projects in a directory.
   data: {:path string}
   Returns list of project directories found."
  [{:keys [path]}]
  (log/log! {:level :debug
             :id ::find-projects
             :msg "Finding projects"
             :data {:path path}})
  (let [target-path (or path (str (dir-browser/home-directory) "/Development"))
        projects (dir-browser/find-projects-in target-path)]
    (log/log! {:level :debug
               :id ::projects-found
               :msg "Projects found"
               :data {:path target-path :count (count projects)}})
    {:path target-path :projects (or projects [])}))

(defn handle-get-breadcrumbs
  "Handle request to get breadcrumb navigation for path.
   data: {:path string}
   Returns vector of breadcrumb segments."
  [{:keys [path]}]
  (log/log! {:level :debug
             :id ::get-breadcrumbs
             :msg "Getting breadcrumbs"
             :data {:path path}})
  (let [crumbs (dir-browser/breadcrumbs path)]
    {:path path :breadcrumbs crumbs}))

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

      :code-browser/navigate-to-symbol
      [:code-browser/navigated (handle-navigate-to-symbol data)]

      :code-browser/set-project-root
      [:code-browser/project-changed (handle-set-project-root data)]

      :code-browser/add-project
      [:code-browser/project-added (handle-add-project data)]

      ;; Phase 1.5E.18: JAR exploration
      :code-browser/explore-jar-dep
      [:code-browser/jar-explored (handle-explore-jar-dep data)]

      :code-browser/request-jar-source
      [:code-browser/jar-source (handle-request-jar-source data)]

      ;; Phase 1.5E.16: Directory browser
      :code-browser/list-directory
      [:code-browser/directory-listing (handle-list-directory data)]

      :code-browser/list-home
      [:code-browser/directory-listing (handle-list-home data)]

      :code-browser/find-projects
      [:code-browser/projects-found (handle-find-projects data)]

      :code-browser/get-breadcrumbs
      [:code-browser/breadcrumbs (handle-get-breadcrumbs data)]

      ;; Not a code-browser event
      nil)))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn enable!
  "Enable code browser handlers and register synced atom.
   Idempotent - safe to call multiple times.
   Optional config map can include:
   - :projects - list of project root paths to make available"
  ([] (enable! {}))
  ([config]
   (when-not @!enabled
     (reset! !enabled true)
     ;; Register atom for sync - browsers will receive updates automatically
     (atom-sync/register-synced-atom! :code-browser !code-browser-state)
     ;; Register for file change notifications (Phase 1.5-Watch)
     (lsp-client/on-notification! :code-browser on-lsp-notification!)
     ;; Phase 1.5E.3: Set up projects if configured
     (when-let [projects (:projects config)]
               (set-projects! projects))
     ;; Set current project to working directory
     (let [cwd (System/getProperty "user.dir")]
       (swap! !code-browser-state assoc :current-project cwd))
     ;; Fetch initial git info (Phase 1.5E.2)
     (refresh-git-info!)
     ;; Phase 1.5E.18: Build NS -> JAR mapping in background
     (initialize-ns->jar-mapping!)
     (log/log! {:level :info
                :id ::enabled
                :msg "Code browser handlers enabled"
                :data {:synced-atom-key :code-browser
                       :file-watching true
                       :projects-configured (count (:projects @!code-browser-state))}}))))

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
    ;; Reset state (Phase 1.5-Acc shape with Phase 1.5E.3 fields + JAR fields)
    (reset! !code-browser-state
            {:namespaces []
             :selected-ns nil
             :symbols-by-ns {}
             :var-usages-by-ns {}
             :ns-usages-by-ns {}    ;; Phase 1.5E.19/20: NS-level deps/aliases
             :ns-files {}           ;; Phase 1.5E.11: ALL ns → files
             :ns-file-counts {}     ;; Phase 1.5E.11: Multi-file namespace info
             :selected-symbol nil
             :source-by-var {}
             :sort-mode :file-order
             :git nil
             :projects []
             :current-project nil
             :ns->jar {}            ;; Phase 1.5E.18: JAR mapping
             :jar-analyses {}       ;; Phase 1.5E.18: JAR analysis cache
             :explored-deps []      ;; Phase 1.5E.18: Explored JAR namespaces
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