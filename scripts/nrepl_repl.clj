#!/usr/bin/env bb

(ns nrepl-repl
    "Interactive nREPL REPL with JLine3 — multi-line editing, history, completion.

   Pure Babashka — no JVM startup, no rebel-readline dependency.
   Connects directly to nREPL via bencode (reuses nrepl-direct client).

   Usage: bb nrepl-repl -t <target> [--port PORT] [--ns NS] [--pprint]

   Examples:
     bb nrepl-repl -t cb-v2-test
     bb nrepl-repl --port 9876
     bb nrepl-repl -t cb-v2-test --ns my-app.core --pprint"
    (:require [bb-mcp-server.nrepl-direct.client :as client]
              [bencode.core :as bencode]
              [cheshire.core :as json]
              [clojure.edn :as edn]
              [clojure.pprint :as pp]
              [clojure.string :as str]
              [edamame.core :as edamame])
    (:import [org.jline.reader LineReader LineReader$Option LineReaderBuilder
              Parser Parser$ParseContext ParsedLine
              Candidate Completer Highlighter
              EndOfFileException UserInterruptException EOFError]
             [org.jline.terminal TerminalBuilder]
             [org.jline.utils AttributedStringBuilder AttributedStyle]))

;; =============================================================================
;; Port Discovery (reused from nrepl_direct_cli.clj)
;; =============================================================================

(defn- discover-port
  "Discover port from .ports/<nickname>.json file."
  [nickname service]
  (let [port-file (str ".ports/" nickname ".json")]
    (when (.exists (java.io.File. port-file))
      (let [data (json/parse-string (slurp port-file) true)
            ports (:ports data)]
        (get ports (keyword service))))))

(defn- parse-target
  "Parse --target shorthand into :nickname, :service, :connection.

   Format:
     server           -> {:nickname server :service nrepl-server}
     server/browser   -> {:nickname server :service nrepl-proxy :connection browser}"
  [target]
  (let [parts (str/split target #"/" 2)]
    (if (= 2 (count parts))
      {:nickname (first parts)
       :service "nrepl-proxy"
       :connection (second parts)}
      {:nickname (first parts)
       :service "nrepl-server"})))

(defn- resolve-port
  "Resolve port from options (explicit or discovered)."
  [{:keys [port nickname service]}]
  (or port
      (when nickname
        (discover-port nickname service))
      (do
       (binding [*out* *err*]
                (println "Error: --port or -t/--target required"))
       (System/exit 1))))

;; =============================================================================
;; CLI Argument Parsing
;; =============================================================================

(defn- parse-args
  "Parse command line arguments."
  [args]
  (loop [args args
         opts {:port nil
               :host "localhost"
               :nickname nil
               :service "nrepl-server"
               :connection nil
               :ns nil
               :pprint false
               :timeout 30000}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              (= arg "--port")
              (recur (rest rest-args) (assoc opts :port (Integer/parseInt (first rest-args))))

              (= arg "--host")
              (recur (rest rest-args) (assoc opts :host (first rest-args)))

              (or (= arg "--target") (= arg "-t"))
              (let [target-opts (parse-target (first rest-args))]
                (recur (rest rest-args) (merge opts target-opts)))

              (= arg "--ns")
              (recur (rest rest-args) (assoc opts :ns (first rest-args)))

              (= arg "--timeout")
              (recur (rest rest-args) (assoc opts :timeout (Integer/parseInt (first rest-args))))

              (= arg "--pprint")
              (recur rest-args (assoc opts :pprint true))

              (or (= arg "-h") (= arg "--help"))
              (assoc opts :help true)

              :else
              (do
               (binding [*out* *err*]
                        (println "Unknown option:" arg))
               (recur rest-args opts)))))))

(defn- print-help []
  (println "bb nrepl-repl — Interactive nREPL REPL with JLine3")
  (println)
  (println "Usage: bb nrepl-repl [options]")
  (println)
  (println "Options:")
  (println "  -t, --target TARGET  Server target (e.g., cb-v2-test, server/browser-1)")
  (println "  --port PORT          nREPL port (alternative to --target)")
  (println "  --host HOST          nREPL host (default: localhost)")
  (println "  --ns NAMESPACE       Initial namespace (default: from server)")
  (println "  --pprint             Pretty-print result values")
  (println "  --timeout MS         Eval timeout in ms (default: 30000)")
  (println "  -h, --help           Show this help")
  (println)
  (println "REPL commands:")
  (println "  :quit / :exit        Exit the REPL")
  (println "  :ns <name>           Switch namespace")
  (println "  :doc <sym>           Show documentation for symbol")
  (println "  :source <sym>        Show source for symbol")
  (println "  Ctrl+D               Exit (EOF)")
  (println "  Ctrl+C               Cancel current input")
  (println)
  (println "Examples:")
  (println "  bb nrepl-repl -t cb-v2-test")
  (println "  bb nrepl-repl --port 9876 --ns my-app.core")
  (println "  bb nrepl-repl -t cb-v2-test --pprint"))

;; =============================================================================
;; Clojure-aware Parser (multi-line support)
;; =============================================================================

(defn- incomplete-form?
  "Check if a string is an incomplete Clojure form using edamame.
   Returns true if the form needs more input (unclosed parens, etc.)."
  [s]
  (try
   (edamame/parse-string-all s {:all true
                                :auto-resolve {:current 'user}
                                :regex true
                                :read-cond :preserve
                                :syntax-quote {:resolve-symbol identity}})
   false
   (catch Exception e
          (let [msg (ex-message e)]
            (if (or (str/includes? (str msg) "EOF")
                    (str/includes? (str msg) "Unexpected EOF")
                    (str/includes? (str msg) "end of input"))
              true
              false)))))

(def ^:private force-accept?
     "Volatile flag to force accepting incomplete input."
     (volatile! false))

(defn- word-at-cursor
  "Extract the Clojure word at cursor position.
   Returns [word word-start] or nil if no word found."
  [^String line ^long cursor]
  (when (and line (pos? (count line)) (pos? cursor))
    (let [c (min cursor (count line))
          start (loop [i (dec c)]
                      (if (or (neg? i)
                              (let [ch (.charAt line i)]
                                (or (Character/isWhitespace ch)
                                    (contains? #{\( \) \[ \] \{ \} \' \` \~ \@ \^ \\} ch))))
                        (inc i)
                        (recur (dec i))))]
      (when (< start c)
        [(subs line start c) start]))))

(defn- make-clojure-parser
  "Create a JLine Parser that detects incomplete Clojure forms.
   Handles COMPLETE context with word-at-cursor for tab completion."
  []
  (let [empty-parsed (fn [line-str cursor]
                       (reify ParsedLine
                              (word [_] "")
                              (wordCursor [_] 0)
                              (wordIndex [_] 0)
                              (words [_] [])
                              (line [_] line-str)
                              (cursor [_] cursor)))]
    (reify Parser
           (^ParsedLine parse [_this ^String line ^int cursor ^Parser$ParseContext context]
                              (let [line-str (str line)]
                                (cond
                                  (identical? context Parser$ParseContext/COMPLETE)
                                  (if-let [[word word-start] (word-at-cursor line-str cursor)]
                                          (let [word-cursor (- cursor (int word-start))]
                                            (reify ParsedLine
                                                   (word [_] word)
                                                   (wordCursor [_] word-cursor)
                                                   (wordIndex [_] 0)
                                                   (words [_] [word])
                                                   (line [_] line-str)
                                                   (cursor [_] cursor)))
                                          (empty-parsed line-str cursor))

                                  (identical? context Parser$ParseContext/ACCEPT_LINE)
                                  (do
                                   (when (not @force-accept?)
                                     (when (and (seq (str/trim line-str))
                                                (incomplete-form? line-str))
                                       (throw (EOFError. -1 -1 "Incomplete Clojure form" nil))))
                                   (vreset! force-accept? false)
                                   (empty-parsed line-str cursor))

                                  :else
                                  (empty-parsed line-str cursor)))))))

;; =============================================================================
;; Syntax Highlighter
;; =============================================================================

(def ^:private special-forms
     "Clojure special forms and common macros for highlighting."
     #{"def" "defn" "defn-" "defmacro" "defonce" "defmethod" "defmulti" "defprotocol"
       "defrecord" "deftype" "definterface"
       "fn" "fn*" "let" "loop" "recur" "do" "if" "if-let" "if-not" "if-some"
       "when" "when-let" "when-not" "when-some" "when-first"
       "cond" "cond->" "cond->>" "condp" "case"
       "try" "catch" "finally" "throw"
       "for" "doseq" "dotimes" "while"
       "ns" "require" "import" "use" "refer" "in-ns"
       "quote" "var" "deref"
       "and" "or" "not"
       "binding" "with-open" "with-redefs" "with-out-str" "with-meta"
       "-> " "->>" "as->" "some->" "some->>"
       "atom" "swap!" "reset!" "add-watch" "remove-watch"
       "assoc" "dissoc" "update" "merge" "select-keys" "get" "get-in" "assoc-in" "update-in"
       "map" "filter" "reduce" "mapv" "filterv" "remove" "keep" "comp" "partial" "apply"
       "str" "pr" "prn" "print" "println"})

(defn- make-highlighter
  "Create a JLine Highlighter with Clojure syntax coloring."
  []
  (reify Highlighter
         (highlight [_this _reader buffer]
                    (let [s (str buffer)
                          asb (AttributedStringBuilder.)]
                      (if (empty? s)
                        (.toAttributedString asb)
                        (let [len (count s)]
                          (loop [i 0
                                 in-string? false
                                 in-comment? false]
                                (if (>= i len)
                                  (.toAttributedString asb)
                                  (let [ch (.charAt s i)]
                                    (cond
                    ;; Inside comment — rest of line is grey
                                      in-comment?
                                      (do
                                       (.style asb (.foreground AttributedStyle/DEFAULT AttributedStyle/BRIGHT))
                                       (.append asb ch)
                                       (recur (inc i) false in-comment?))

                    ;; Inside string
                                      (and in-string? (not= ch \"))
                                      (do
                                       (.style asb (.foreground AttributedStyle/DEFAULT AttributedStyle/YELLOW))
                                       (.append asb ch)
                                       (if (= ch \\)
                        ;; Escape next char
                                         (if (< (inc i) len)
                                           (do
                                            (.append asb (.charAt s (inc i)))
                                            (recur (+ i 2) true false))
                                           (recur (inc i) true false))
                                         (recur (inc i) true false)))

                    ;; String start/end
                                      (= ch \")
                                      (do
                                       (.style asb (.foreground AttributedStyle/DEFAULT AttributedStyle/YELLOW))
                                       (.append asb ch)
                                       (recur (inc i) (not in-string?) false))

                    ;; Comment start
                                      (= ch \;)
                                      (do
                                       (.style asb (.foreground AttributedStyle/DEFAULT AttributedStyle/BRIGHT))
                                       (.append asb ch)
                                       (recur (inc i) false true))

                    ;; Parens
                                      (or (= ch \() (= ch \)))
                                      (do
                                       (.style asb (.bold (.foreground AttributedStyle/DEFAULT AttributedStyle/RED)))
                                       (.append asb ch)
                                       (recur (inc i) false false))

                    ;; Brackets
                                      (or (= ch \[) (= ch \]))
                                      (do
                                       (.style asb (.foreground AttributedStyle/DEFAULT AttributedStyle/BLUE))
                                       (.append asb ch)
                                       (recur (inc i) false false))

                    ;; Braces
                                      (or (= ch \{) (= ch \}))
                                      (do
                                       (.style asb (.foreground AttributedStyle/DEFAULT AttributedStyle/GREEN))
                                       (.append asb ch)
                                       (recur (inc i) false false))

                    ;; Keyword
                                      (= ch \:)
                                      (let [end (loop [j (inc i)]
                                                      (if (and (< j len)
                                                               (let [c (.charAt s j)]
                                                                 (or (Character/isLetterOrDigit c)
                                                                     (contains? #{\- \_ \. \/ \? \!} c))))
                                                        (recur (inc j))
                                                        j))
                                            kw (subs s i end)]
                                        (.style asb (.foreground AttributedStyle/DEFAULT AttributedStyle/CYAN))
                                        (.append asb kw)
                                        (recur end false false))

                    ;; Word (check for special forms)
                                      (or (Character/isLetter ch) (contains? #{\- \_ \+ \* \< \> \= \!} ch))
                                      (let [end (loop [j (inc i)]
                                                      (if (and (< j len)
                                                               (let [c (.charAt s j)]
                                                                 (or (Character/isLetterOrDigit c)
                                                                     (contains? #{\- \_ \. \/ \? \! \* \+ \> \<} c))))
                                                        (recur (inc j))
                                                        j))
                                            word (subs s i end)]
                                        (if (contains? special-forms word)
                                          (.style asb (.bold (.foreground AttributedStyle/DEFAULT AttributedStyle/MAGENTA)))
                                          (.style asb AttributedStyle/DEFAULT))
                                        (.append asb word)
                                        (recur end false false))

                    ;; Default
                                      :else
                                      (do
                                       (.style asb AttributedStyle/DEFAULT)
                                       (.append asb ch)
                                       (recur (inc i) false false))))))))))

         (setErrorPattern [_this _pattern])
         (setErrorIndex [_this _index])))

;; =============================================================================
;; Tab Completion via nREPL
;; =============================================================================

(def ^:private repl-state
     "Shared mutable state for the REPL, accessible from Completer."
     (atom {:conn nil
            :session nil
            :current-ns "user"}))

(defn- bytes->str
  "Convert byte array or string to string."
  [obj]
  (cond
    (instance? (Class/forName "[B") obj) (String. ^bytes obj "UTF-8")
    (string? obj) obj
    :else (str obj)))

(defn- decode-bencode-response
  "Convert raw bencode response to keyword map, recursively stringifying bytes."
  [obj]
  (cond
    (map? obj) (into {} (map (fn [[k v]] [(keyword (bytes->str k)) (decode-bencode-response v)]) obj))
    (vector? obj) (mapv decode-bencode-response obj)
    (seq? obj) (map decode-bencode-response obj)
    :else (bytes->str obj)))

(defn- nrepl-completions
  "Fetch completions from nREPL server using babashka's 'completions' op.
   Reads raw bencode response because client/send-message's merge-responses
   drops the :completions key (it only keeps eval-related fields)."
  [prefix ns-str]
  (let [{:keys [conn session]} @repl-state]
    (when conn
      (try
       (let [out (:out conn)
             in (:in conn)
             msg (cond-> {:op "completions" :prefix prefix}
                         session (assoc :session session)
                         ns-str (assoc :ns ns-str))]
         (bencode/write-bencode out msg)
         (.flush out)
         (let [resp (decode-bencode-response (bencode/read-bencode in))]
           (:completions resp)))
       (catch Exception _e nil)))))

(defn- make-completer
  "Create a JLine Completer backed by nREPL completions."
  []
  (reify Completer
         (complete [_this _reader parsed-line candidates]
                   (let [word (.word ^ParsedLine parsed-line)
                         ns-str (:current-ns @repl-state)]
                     (when (and word (pos? (count word)))
                       (when-let [completions (nrepl-completions word ns-str)]
                                 (doseq [c completions]
                                        (let [candidate-str (if (map? c) (or (:candidate c) (str c)) (str c))]
                                          (.add ^java.util.List candidates
                                                (Candidate. candidate-str
                                                            candidate-str
                                                            nil
                                                            (when (map? c) (:ns c))
                                                            nil
                                                            nil
                                                            false))))))))))

;; =============================================================================
;; REPL Loop
;; =============================================================================

(defn- print-banner
  "Print startup banner with connection info."
  [{:keys [host port connection]} server-info session]
  (println)
  (println (str "bb nREPL REPL (JLine3) — connected to " host ":" port
                (when connection (str " → " connection))))
  (when-let [versions (:versions server-info)]
            (let [v (if (string? versions) versions
                        (pr-str versions))]
              (when (seq v)
                (println (str "Server: " v)))))
  (println (str "Session: " (subs (str session) 0 (min 8 (count (str session)))) "..."))
  (println "Type :quit to exit, Ctrl+D for EOF, Ctrl+C to cancel input")
  (println))

(defn- handle-special-command
  "Handle REPL special commands. Returns :handled, :quit, or nil (not a command)."
  [line {:keys [conn session current-ns timeout]}]
  (let [trimmed (str/trim line)]
    (cond
      ;; :quit / :exit
      (or (= trimmed ":quit") (= trimmed ":exit"))
      :quit

      ;; :ns <name>
      (str/starts-with? trimmed ":ns ")
      (let [new-ns (str/trim (subs trimmed 3))]
        (if (seq new-ns)
          (do
           (let [result (client/eval-code conn (str "(in-ns '" new-ns ")")
                                          :session session
                                          :ns current-ns
                                          :timeout-ms timeout)]
             (if (= "success" (:status result))
               (do
                (swap! repl-state assoc :current-ns new-ns)
                (println (str "Switched to " new-ns)))
               (println (str "Error: " (or (:err result) (:ex result))))))
           :handled)
          (do
           (println "Usage: :ns <namespace>")
           :handled)))

      ;; :doc <sym>
      (str/starts-with? trimmed ":doc ")
      (let [sym (str/trim (subs trimmed 4))]
        (if (seq sym)
          (let [result (client/eval-code conn
                                         (str "(clojure.repl/doc " sym ")")
                                         :session session
                                         :ns current-ns
                                         :timeout-ms timeout)]
            (when (:out result) (print (:out result)))
            (when (and (:err result) (seq (:err result)))
              (binding [*out* *err*] (print (:err result)))))
          (println "Usage: :doc <symbol>"))
        :handled)

      ;; :source <sym>
      (str/starts-with? trimmed ":source ")
      (let [sym (str/trim (subs trimmed 7))]
        (if (seq sym)
          (let [result (client/eval-code conn
                                         (str "(clojure.repl/source " sym ")")
                                         :session session
                                         :ns current-ns
                                         :timeout-ms timeout)]
            (when (:out result) (print (:out result)))
            (when (and (:err result) (seq (:err result)))
              (binding [*out* *err*] (print (:err result)))))
          (println "Usage: :source <symbol>"))
        :handled)

      :else nil)))

(defn- eval-and-print
  "Evaluate code and print results. Returns updated namespace or nil."
  [{:keys [conn session current-ns timeout pprint connection]}
   code]
  (try
   (let [result (client/eval-code conn code
                                  :session session
                                  :ns current-ns
                                  :timeout-ms timeout
                                  :connection connection)]
      ;; Print stdout
     (when (and (:out result) (seq (:out result)))
       (print (:out result))
       (flush))
      ;; Print stderr
     (when (and (:err result) (seq (:err result)))
       (binding [*out* *err*]
                (print (:err result))
                (flush)))
      ;; Print value
     (when (:value result)
       (if pprint
         (try
          (let [parsed (edn/read-string (:value result))]
            (pp/pprint parsed))
          (catch Exception _
                 (println (:value result))))
         (println (:value result))))
      ;; Print error info
     (when (= "error" (:status result))
       (when (:ex result)
         (println (str "Exception: " (:ex result)))))
      ;; Return updated namespace
     (:ns result))
   (catch java.net.SocketException e
          (println (str "Connection lost: " (ex-message e)))
          (System/exit 1))
   (catch Exception e
          (println (str "Error: " (ex-message e)))
          nil)))

(defn- make-history-path
  "Create history file path."
  []
  (let [dir (java.io.File. (str (System/getProperty "user.home") "/.bb-nrepl-repl"))]
    (when-not (.exists dir) (.mkdirs dir))
    (str (.getAbsolutePath dir) "/history")))

(defn- run-repl
  "Main REPL loop."
  [opts]
  (let [port (resolve-port opts)
        host (:host opts)
        connection (:connection opts)
        ;; Connect
        conn (try
              (client/connect {:host host :port port})
              (catch Exception e
                     (binding [*out* *err*]
                              (println (str "Failed to connect to " host ":" port ": " (ex-message e))))
                     (System/exit 1)))
        ;; Clone session
        session-result (client/clone-session conn)
        _ (when (not= "success" (:status session-result))
            (binding [*out* *err*]
                     (println "Failed to clone session:" (pr-str session-result)))
            (client/close conn)
            (System/exit 1))
        session (:session session-result)
        ;; Get server info
        server-info (try (client/describe conn) (catch Exception _ nil))
        ;; Initial namespace
        init-ns (or (:ns opts) "user")]

    ;; Update shared state for completer
    (reset! repl-state {:conn conn :session session :current-ns init-ns})

    ;; Set initial namespace if specified
    (when (:ns opts)
      (client/eval-code conn (str "(in-ns '" init-ns ")")
                        :session session :timeout-ms 5000))

    ;; Build JLine terminal and reader
    (let [terminal (-> (TerminalBuilder/builder)
                       (.system true)
                       (.build))
          history-path (make-history-path)
          reader (-> (LineReaderBuilder/builder)
                     (.terminal terminal)
                     (.parser (make-clojure-parser))
                     (.completer (make-completer))
                     (.highlighter (make-highlighter))
                     (.variable LineReader/HISTORY_FILE history-path)
                     (.variable LineReader/SECONDARY_PROMPT_PATTERN "%P  #_=> ")
                     (.option LineReader$Option/INSERT_BRACKET true)
                     (.build))]

      ;; Print banner
      (print-banner {:host host :port port :connection connection}
                    server-info session)

      ;; REPL loop
      (try
       (loop [current-ns init-ns]
             (let [prompt (str current-ns "=> ")
                   line (try
                         (.readLine reader prompt)
                         (catch EndOfFileException _
                                (println "\nBye!")
                                nil)
                         (catch UserInterruptException _
                                ::interrupted))]
               (cond
              ;; EOF (Ctrl+D)
                 (nil? line)
                 nil

              ;; Ctrl+C
                 (= line ::interrupted)
                 (recur current-ns)

              ;; Empty line
                 (empty? (str/trim (str line)))
                 (recur current-ns)

              ;; Special commands
                 :else
                 (let [cmd-result (handle-special-command
                                   (str line)
                                   {:conn conn
                                    :session session
                                    :current-ns current-ns
                                    :timeout (:timeout opts)})]
                   (case cmd-result
                     :quit (println "Bye!")
                     :handled (recur (:current-ns @repl-state))
                  ;; Normal eval
                     (let [ns-now (or (eval-and-print
                                       {:conn conn
                                        :session session
                                        :current-ns current-ns
                                        :timeout (:timeout opts)
                                        :pprint (:pprint opts)
                                        :connection connection}
                                       (str line))
                                      current-ns)]
                       (swap! repl-state assoc :current-ns ns-now)
                       (recur ns-now)))))))
       (finally
          ;; Cleanup
        (try (client/close-session conn session) (catch Exception _))
        (try (client/close conn) (catch Exception _))
        (try (.close terminal) (catch Exception _)))))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Entry point for nrepl-repl script."
  []
  (let [opts (parse-args *command-line-args*)]
    (if (:help opts)
      (print-help)
      (run-repl opts))))

(-main)