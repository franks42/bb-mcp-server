(ns code-browser.sources.nrepl
    "nREPL source adapter for Code Browser v2.

   Connects to a live running server via nREPL, introspects its runtime
   (namespaces, vars, types, docstrings), and snapshots the data into
   the same Datalevin format as directory sources.

   Uses multimethod dispatch via code-browser.sources.runtime for
   runtime-specific introspection (Babashka, JVM Clojure, etc.).

   Design decisions:
   - Lazy connection: connects on first scan-project call
   - Temporal versioning: UUIDv7 version per snapshot
   - Same entity format as dir:// — existing handlers/browser work unchanged
   - Uses nrepl-direct client for lightweight TCP communication"
    (:require [code-browser.sources.protocol :as proto]
              [code-browser.sources.runtime :as runtime]
              [code-browser.uri :as uri]
              [bb-mcp-server.nrepl-direct.client :as nrepl-client]
              [com.github.franks42.uuidv7.core :as uuidv7]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- make-eval-fn
  "Create an eval function that sends code to an nREPL server.

   Returns (fn [code-string] -> parsed-result) wrapping nrepl-direct/eval-code.
   Connects lazily and caches the connection in conn-atom."
  [conn-atom host port]
  (fn [code]
    (let [conn (or @conn-atom
                   (let [c (nrepl-client/connect {:host host :port port})]
                     (reset! conn-atom c)
                     c))
          result (nrepl-client/eval-code conn code :timeout-ms 30000)]
      (if (= "success" (:status result))
        (:value-parsed result)
        (do
         (log/log! {:level :warn
                    :id ::eval-error
                    :msg "nREPL eval failed"
                    :data {:code (subs code 0 (min 100 (count code)))
                           :status (:status result)
                           :error (:err result)}})
         nil)))))

(defn build-ns-entity
  "Build a namespace entity map for Datalevin from introspection data.

   Arguments:
     ns-data      - map from runtime introspection {:name :doc}
     uri-base     - base URI for the project
     project-name - project identifier
     version      - UUIDv7 version string"
  [ns-data uri-base project-name version]
  (let [ns-name (:name ns-data)]
    {:uri/string (str uri-base "/" ns-name)
     :uri/source :nrepl
     :uri/project project-name
     :uri/version version
     :uri/version-type :temporal
     :uri/namespace ns-name
     :ns/name ns-name
     :ns/doc (:doc ns-data)}))

(defn build-symbol-entity
  "Build a symbol entity map for Datalevin from var introspection data.

   Arguments:
     var-data     - map from runtime introspection {:name :arglists :doc :macro :private :dynamic}
     ns-name      - namespace name string
     uri-base     - base URI for the project
     project-name - project identifier
     version      - UUIDv7 version string"
  [var-data ns-name uri-base project-name version]
  (let [sym-name (:name var-data)
        sym-type (runtime/infer-symbol-type var-data)]
    {:uri/string (str uri-base "/" ns-name "/" sym-name)
     :uri/source :nrepl
     :uri/project project-name
     :uri/version version
     :uri/version-type :temporal
     :uri/namespace ns-name
     :uri/symbol sym-name
     :symbol/name sym-name
     :symbol/type sym-type
     :symbol/doc (:doc var-data)
     :symbol/arglists (:arglists var-data)
     :symbol/private? (:private var-data)
     :symbol/macro? (:macro var-data)
     :symbol/dynamic? (:dynamic var-data)}))

;;; ---------------------------------------------------------------------------
;;; NreplSource Record
;;; ---------------------------------------------------------------------------

(defrecord NreplSource [host port project-name version uri-base
                        conn runtime-info]
           proto/IProjectSource

           (scan-project [_this]
                         (log/log! {:level :info
                                    :id ::scanning-nrepl
                                    :msg "Scanning nREPL runtime"
                                    :data {:host host :port port
                                           :project-name project-name
                                           :version version}})
                         (let [eval-fn (make-eval-fn conn host port)
                               ;; Detect runtime type
                               rt-info (runtime/detect-runtime eval-fn)
                               _ (reset! runtime-info rt-info)
                               rt-type (:type rt-info)
                               ;; Batch introspect all namespaces + vars
                               ns-data (runtime/batch-introspect rt-type eval-fn {})
                               ;; Build project entity
                               project {:uri/string uri-base
                                        :uri/source :nrepl
                                        :uri/project project-name
                                        :uri/version version
                                        :uri/version-type :temporal
                                        :project/nrepl-host (str host ":" port)}
                               ;; Build namespace entities
                               namespaces (mapv #(build-ns-entity % uri-base project-name version)
                                                ns-data)
                               ;; Build symbol entities
                               symbols (vec
                                        (mapcat
                                         (fn [ns-map]
                                           (map #(build-symbol-entity % (:name ns-map)
                                                                      uri-base project-name version)
                                                (:vars ns-map)))
                                         ns-data))]
                           (log/log! {:level :info
                                      :id ::scan-nrepl-complete
                                      :msg "nREPL scan complete"
                                      :data {:project-name project-name
                                             :runtime-type rt-type
                                             :namespace-count (count namespaces)
                                             :symbol-count (count symbols)}})
                           {:project project
                            :namespaces namespaces
                            :symbols symbols
                            :aliases []
                            :refers []}))

           (fetch-source [_this uri-string]
                         (log/log! {:level :debug
                                    :id ::fetch-nrepl-source
                                    :msg "Fetching source from nREPL"
                                    :data {:uri uri-string}})
                         (let [parsed (uri/parse uri-string)
                               ns-name (:uri/namespace parsed)
                               sym-name (:uri/symbol parsed)]
                           (when (and ns-name sym-name)
                             (let [eval-fn (make-eval-fn conn host port)
                                   rt-type (:type @runtime-info)
                                   result (runtime/fetch-var-source
                                           (or rt-type :babashka)
                                           eval-fn ns-name sym-name {})]
                               (when result
                                 {:content (:source result)})))))

           (watch! [_this _callback]
                   nil)

           (unwatch! [_this _handle]
                     nil)

           (source-info [_this]
                        {:type :nrepl
                         :version-type :temporal
                         :supports-watch? false
                         :description (str "nREPL: " host ":" port)}))

;;; ---------------------------------------------------------------------------
;;; Runtime Info
;;; ---------------------------------------------------------------------------

(defn get-runtime-info
  "Fetch extended runtime information from an nREPL source.

   Arguments:
     source - NreplSource instance

   Returns a map with :clojure-version, :java-version, :java-vendor, :os,
   :processors, :max-memory-mb, :free-memory-mb, :loaded-lib-count,
   :namespace-count, :babashka-version, or nil on failure."
  [source]
  (let [eval-fn (make-eval-fn (:conn source) (:host source) (:port source))
        rt-type (:type @(:runtime-info source))]
    (runtime/runtime-info (or rt-type :babashka) eval-fn {})))

;;; ---------------------------------------------------------------------------
;;; Value Introspection
;;; ---------------------------------------------------------------------------

(defn fetch-var-value
  "Fetch the current runtime value of a var via nREPL eval.

   Arguments:
     source   - NreplSource instance
     ns-name  - namespace name string
     var-name - var name string
     opts     - options map

   Returns the value info map from runtime/fetch-var-value or nil."
  [source ns-name var-name opts]
  (let [eval-fn (make-eval-fn (:conn source) (:host source) (:port source))
        rt-type (:type @(:runtime-info source))]
    (runtime/fetch-var-value (or rt-type :babashka) eval-fn ns-name var-name opts)))

(defn check-var-fingerprint
  "Check if a var's value has changed by comparing identity fingerprints.

   Arguments:
     source      - NreplSource instance
     ns-name     - namespace name string
     var-name    - var name string
     fingerprint - map with :var-id and :value-id from previous fetch
     opts        - options map

   Returns {:changed? false} when fingerprint matches, or the full value
   map (with :changed? true and updated fingerprint fields) when changed."
  [source ns-name var-name fingerprint opts]
  (let [eval-fn (make-eval-fn (:conn source) (:host source) (:port source))
        rt-type (:type @(:runtime-info source))]
    (runtime/check-var-fingerprint
     (or rt-type :babashka) eval-fn ns-name var-name fingerprint opts)))

(defn check-ns-list-fingerprint
  "Check if the namespace list has changed by comparing count and hash.

   Arguments:
     source      - NreplSource instance
     project-uri - project URI string
     fingerprint - map with :count and :hash from previous check
     opts        - options map

   Returns {:changed? false} when fingerprint matches, or {:changed? true
   :count N :hash \"...\" :namespaces [...]} when changed."
  [source project-uri fingerprint opts]
  (let [eval-fn (make-eval-fn (:conn source) (:host source) (:port source))
        rt-type (:type @(:runtime-info source))]
    (runtime/check-ns-list-fingerprint
     (or rt-type :babashka) eval-fn project-uri fingerprint opts)))

(defn check-symbol-list-fingerprint
  "Check if a namespace's symbol list has changed by comparing count and hash.

   Arguments:
     source      - NreplSource instance
     ns-name     - namespace name string
     fingerprint - map with :count and :hash from previous check
     opts        - options map

   Returns {:changed? false} when fingerprint matches, or {:changed? true
   :count N :hash \"...\" :symbols [...]} when changed."
  [source ns-name fingerprint opts]
  (let [eval-fn (make-eval-fn (:conn source) (:host source) (:port source))
        rt-type (:type @(:runtime-info source))]
    (runtime/check-symbol-list-fingerprint
     (or rt-type :babashka) eval-fn ns-name fingerprint opts)))

;;; ---------------------------------------------------------------------------
;;; Constructor
;;; ---------------------------------------------------------------------------

(defn create-nrepl-source
  "Create an NreplSource for a live nREPL connection.

   Arguments:
     host - nREPL server hostname
     port - nREPL server port number

   Options:
     :project-name     - Override project name (default: host:port)
     :exclude-patterns - Namespace exclusion patterns

   Example:
     (create-nrepl-source \"localhost\" 7888)
     (create-nrepl-source \"localhost\" 7888 {:project-name \"my-server\"})"
  ([host port]
   (create-nrepl-source host port {}))
  ([host port {:keys [project-name]}]
   (let [proj-name (or project-name (str host ":" port))
         ver (str (uuidv7/uuidv7))
         uri-base (uri/build {:source :nrepl :project proj-name :version ver})
         conn-atom (atom nil)
         rt-info (atom nil)]
     (log/log! {:level :info
                :id ::creating-nrepl-source
                :msg "Creating nREPL source"
                :data {:host host :port port
                       :project-name proj-name
                       :version ver
                       :uri-base uri-base}})
     (->NreplSource host port proj-name ver uri-base conn-atom rt-info))))
