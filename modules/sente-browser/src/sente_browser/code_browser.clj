(ns sente-browser.code-browser
    "Server-side handlers for code browser.

   Fetches data from clojure-lsp and sends to browser via sente-lite.

   Events from browser:
   - :code-browser/request-namespaces {}
   - :code-browser/request-symbols {:file string}
   - :code-browser/request-source {:file string :start-line int :end-line int}

   Events to browser:
   - :code-browser/namespaces {:namespaces [...]}
   - :code-browser/symbols {:symbols [...]}
   - :code-browser/source {:code string :file string :language string}"
    (:require [bb-mcp-server.modules.clojure-lsp.client :as lsp-client]
              [clojure.java.io :as io]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

;; Whether code browser handlers are enabled
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !enabled (atom false))

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
  "Detect symbol kind, distinguishing forward declarations from variables.
   Forward declarations (declare foo) are kind 13 with single-line range."
  [sym]
  (let [kind-num (:kind sym)
        start-line (get-in sym [:location :range :start :line])
        end-line (get-in sym [:location :range :end :line])]
    (if (and (= 13 kind-num) (= start-line end-line))
      :declare  ; Forward declaration - single line variable
      (get symbol-kind->name kind-num :unknown))))

(defn extract-ns-symbols
  "Extract symbols belonging to a specific namespace.
   Uses file location to group symbols."
  [symbols file-uri]
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
       (sort-by :name)
       vec))

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

;; =============================================================================
;; Handler Functions
;; =============================================================================

(defn handle-request-namespaces
  "Handle request for namespace list.
   Returns {:namespaces [string ...]}."
  [_data]
  (log/log! {:level :info
             :id ::request-namespaces
             :msg "Handling namespace list request"})
  (let [symbols (fetch-all-symbols)
        namespaces (extract-namespaces symbols)]
    {:namespaces namespaces
     :count (count namespaces)}))

(defn handle-request-symbols
  "Handle request for symbols in a namespace.
   data: {:ns string}
   Returns {:symbols [...] :file string}."
  [{:keys [ns]}]
  (log/log! {:level :info
             :id ::request-symbols
             :msg "Handling symbols request"
             :data {:ns ns}})
  (let [symbols (fetch-all-symbols)
        file-uri (get-namespace-file symbols ns)]
    (if file-uri
      {:ns ns
       :file file-uri
       :symbols (extract-ns-symbols symbols file-uri)}
      {:ns ns
       :error (str "Namespace not found: " ns)})))

(defn handle-request-source
  "Handle request for source code.
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
                   content)]
        {:code code
         :file file
         :language "clojure"
         :start-line (or start-line 1)
         :end-line (or end-line (count (str/split-lines content)))})
      {:error (str "File not found: " file)})))

(defn handle-request-var-source
  "Handle request for source of a specific var.
   data: {:ns string :var-name string :kind keyword}
   Finds the var via clojure-lsp and returns its source.
   Uses :kind to disambiguate when multiple symbols have same name."
  [{:keys [ns var-name kind]}]
  (log/log! {:level :info
             :id ::request-var-source
             :msg "Handling var source request"
             :data {:ns ns :var-name var-name :kind kind}})
  (let [symbols (fetch-all-symbols)
        file-uri (get-namespace-file symbols ns)
        file-path (when file-uri (str/replace file-uri "file://" ""))]
    (if-not file-path
      {:error (str "Namespace not found: " ns)}
      ;; Find the var in symbols, using kind to disambiguate duplicates
      (let [matching (->> symbols
                          (filter #(and (= file-uri (get-in % [:location :uri]))
                                        (= var-name (:name %)))))
            ;; If kind provided, filter by it; otherwise take first
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
        (if-not var-sym
          {:error (str "Var not found: " ns "/" var-name)}
          (let [start-line (inc (get-in var-sym [:location :range :start :line] 0))
                end-line (inc (get-in var-sym [:location :range :end :line] 0))
                content (fetch-file-content file-uri)
                code (extract-source-region content start-line end-line)]
            {:code code
             :file file-uri
             :ns ns
             :var-name var-name
             :start-line start-line
             :end-line end-line
             :language "clojure"}))))))

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

      ;; Not a code-browser event
      nil)))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn enable!
  "Enable code browser handlers."
  []
  (reset! !enabled true)
  (log/log! {:level :info
             :id ::enabled
             :msg "Code browser handlers enabled"}))

(defn disable!
  "Disable code browser handlers."
  []
  (reset! !enabled false)
  (log/log! {:level :info
             :id ::disabled
             :msg "Code browser handlers disabled"}))
