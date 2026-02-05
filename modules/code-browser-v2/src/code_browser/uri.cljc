(ns code-browser.uri
    "URI parsing, generation, and validation for Code Browser v2.

   URI format: <source>://<project>@<version>/<namespace>/<symbol>?key=val&...

   Examples:
     dir://bb-mcp-server@abc123/bb-mcp-server.main/register!
     dir://bb-mcp-server@abc123?view=ns-list
     dir://bb-mcp-server@abc123/some.ns/my-fn?view=source&line=42
     jar://taoensso.trove@1.0.0/taoensso.trove/log!
     github://taoensso/trove@v1.2.0/taoensso.trove.core/init!
     nrepl://localhost:7888@01950a3b-1234-7def/user/my-fn

   Source types:
     :dir     - Local directory (git-versioned)
     :jar     - Maven JAR file
     :github  - GitHub repository
     :nrepl   - Live nREPL connection (temporal)

   Version types:
     :static   - Immutable (git SHA, Maven version, tag)
     :temporal - Snapshot in time (UUIDv7 for nREPL)"
    (:require [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; URI Regex Pattern
;;; ---------------------------------------------------------------------------

(def ^:private uri-pattern
     "Regex to parse code browser URIs.
   Groups: 1=source, 2=project, 3=version, 4=namespace (optional), 5=symbol (optional)"
     #"^(dir|jar|github|nrepl)://([^@]+)@([^/]+)(?:/([^/]+))?(?:/(.+))?$")

;;; ---------------------------------------------------------------------------
;;; Source Type Classification
;;; ---------------------------------------------------------------------------

(def static-sources
     "Sources with immutable versions"
     #{:dir :jar :github})

(def temporal-sources
     "Sources with temporal snapshots"
     #{:nrepl})

(defn static-source?
  "Returns true if source type has immutable versions"
  [source]
  (contains? static-sources source))

(defn temporal-source?
  "Returns true if source type has temporal snapshots"
  [source]
  (contains? temporal-sources source))

;;; ---------------------------------------------------------------------------
;;; Query String Helpers
;;; ---------------------------------------------------------------------------

(defn- parse-query-string
  "Parse a query string into a map. Returns nil for blank input.
   \"k1=v1&k2=v2\" => {\"k1\" \"v1\", \"k2\" \"v2\"}"
  [qs]
  (when-not (str/blank? qs)
    (into {}
          (comp
           (remove str/blank?)
           (map (fn [pair]
                  (let [idx (str/index-of pair "=")]
                    (if idx
                      [(subs pair 0 idx) (subs pair (inc idx))]
                      [pair ""])))))
          (str/split qs #"&"))))

(defn- build-query-string
  "Build a query string from a map. Returns nil for empty/nil input.
   {\"k1\" \"v1\", \"k2\" \"v2\"} => \"k1=v1&k2=v2\""
  [m]
  (when (seq m)
    (str/join "&" (map (fn [[k v]] (str k "=" v))
                       (sort-by key m)))))

;;; ---------------------------------------------------------------------------
;;; URI Parsing
;;; ---------------------------------------------------------------------------

(defn parse
  "Parse a URI string into a map.

   Returns:
     {:uri/string    \"dir://proj@v/ns/sym?view=source\"
      :uri/source    :dir
      :uri/project   \"proj\"
      :uri/version   \"v\"
      :uri/version-type :static | :temporal
      :uri/namespace \"ns\"     ; nil if not present
      :uri/symbol    \"sym\"    ; nil if not present
      :uri/query     {\"view\" \"source\"}} ; nil if no query params

   Returns nil if URI is invalid."
  [uri-string]
  (let [[base qs] (str/split (str uri-string) #"\?" 2)]
    (when-let [match (re-matches uri-pattern base)]
              (let [[_ source project version namespace symbol] match
                    source-kw (keyword source)]
                {:uri/string       uri-string
                 :uri/source       source-kw
                 :uri/project      project
                 :uri/version      version
                 :uri/version-type (if (temporal-source? source-kw) :temporal :static)
                 :uri/namespace    namespace
                 :uri/symbol       symbol
                 :uri/query        (parse-query-string qs)}))))

(defn valid?
  "Returns true if URI string is valid"
  [uri-string]
  (some? (parse uri-string)))

;;; ---------------------------------------------------------------------------
;;; URI Generation
;;; ---------------------------------------------------------------------------

(defn build
  "Build a URI string from components.

   Required: :source, :project, :version
   Optional: :namespace, :symbol, :query (map of string keys/values)

   Example:
     (build {:source :dir :project \"foo\" :version \"abc\"})
     => \"dir://foo@abc\"

     (build {:source :dir :project \"foo\" :version \"abc\"
             :namespace \"foo.core\" :symbol \"bar\"})
     => \"dir://foo@abc/foo.core/bar\"

     (build {:source :dir :project \"foo\" :version \"abc\"
             :query {\"view\" \"ns-list\"}})
     => \"dir://foo@abc?view=ns-list\""
  [{:keys [source project version namespace symbol query]}]
  (let [base (str (name source) "://" project "@" version)
        path (cond
               (and namespace symbol) (str base "/" namespace "/" symbol)
               namespace              (str base "/" namespace)
               :else                  base)
        qs (build-query-string query)]
    (if qs
      (str path "?" qs)
      path)))

;;; ---------------------------------------------------------------------------
;;; URI Navigation Helpers
;;; ---------------------------------------------------------------------------

(defn project-uri
  "Extract or build the project-level URI from a parsed URI or components.
   Accepts either a parsed URI map (with :uri/* keys) or simple map (with :source etc)."
  [m]
  (let [src (or (:uri/source m) (:source m))
        prj (or (:uri/project m) (:project m))
        ver (or (:uri/version m) (:version m))]
    (build {:source src :project prj :version ver})))

(defn namespace-uri
  "Extract or build the namespace-level URI from a parsed URI or components"
  [m]
  (let [src (or (:uri/source m) (:source m))
        prj (or (:uri/project m) (:project m))
        ver (or (:uri/version m) (:version m))
        ns  (or (:uri/namespace m) (:namespace m))]
    (when ns
      (build {:source src :project prj :version ver :namespace ns}))))

(defn symbol-uri
  "Build a symbol-level URI from components"
  [m]
  (let [src (or (:uri/source m) (:source m))
        prj (or (:uri/project m) (:project m))
        ver (or (:uri/version m) (:version m))
        ns  (or (:uri/namespace m) (:namespace m))
        sym (or (:uri/symbol m) (:symbol m))]
    (when (and ns sym)
      (build {:source src :project prj :version ver :namespace ns :symbol sym}))))

(defn parent-uri
  "Get the parent URI (symbol→namespace, namespace→project, project→nil)"
  [uri-string]
  (when-let [parsed (parse uri-string)]
            (cond
              (:uri/symbol parsed)    (namespace-uri parsed)
              (:uri/namespace parsed) (project-uri parsed)
              :else                   nil)))

;;; ---------------------------------------------------------------------------
;;; URI Comparison
;;; ---------------------------------------------------------------------------

(defn same-project?
  "Returns true if two URIs refer to the same project (ignoring version)"
  [uri1 uri2]
  (let [p1 (parse uri1)
        p2 (parse uri2)]
    (and p1 p2
         (= (:uri/source p1) (:uri/source p2))
         (= (:uri/project p1) (:uri/project p2)))))

(defn same-namespace?
  "Returns true if two URIs refer to the same namespace (ignoring version)"
  [uri1 uri2]
  (let [p1 (parse uri1)
        p2 (parse uri2)]
    (and (same-project? uri1 uri2)
         (= (:uri/namespace p1) (:uri/namespace p2)))))

;;; ---------------------------------------------------------------------------
;;; Query Parameter Utilities
;;; ---------------------------------------------------------------------------

(defn base-uri
  "Strip query parameters from a URI string, returning the base URI only.
   Returns nil if the URI is invalid."
  [uri-string]
  (when-let [parsed (parse uri-string)]
            (build {:source    (:uri/source parsed)
                    :project   (:uri/project parsed)
                    :version   (:uri/version parsed)
                    :namespace (:uri/namespace parsed)
                    :symbol    (:uri/symbol parsed)})))

(defn with-query
  "Add or merge query parameters onto a URI string.
   Returns a new URI string with the query params appended/merged.
   Returns nil if the URI is invalid."
  [uri-string query-map]
  (when-let [parsed (parse uri-string)]
            (build {:source    (:uri/source parsed)
                    :project   (:uri/project parsed)
                    :version   (:uri/version parsed)
                    :namespace (:uri/namespace parsed)
                    :symbol    (:uri/symbol parsed)
                    :query     (merge (:uri/query parsed) query-map)})))
