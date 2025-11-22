#!/usr/bin/env bb
;; HTTP MCP Server test script
;; Usage: bb scripts/http_test.clj [command] [args...]
;;
;; Commands:
;;   health              - Check server health
;;   init                - Initialize MCP session
;;   tools               - List available tools
;;   call <tool> <json>  - Call a tool with JSON arguments
;;   hello <name>        - Shortcut for hello tool
;;   add <a> <b>         - Shortcut for add tool
;;   concat <strings>... - Shortcut for concat tool
;;   all                 - Run all tests

(require '[babashka.http-client :as http]
         '[cheshire.core :as json])

(def base-url
     "Base URL for MCP server from MCP_HTTP_URL env or default."
     (or (System/getenv "MCP_HTTP_URL") "http://localhost:3000"))

(def mcp-url
     "Full MCP endpoint URL."
     (str base-url "/mcp"))

;; -----------------------------------------------------------------------------
;; HTTP Helpers
;; -----------------------------------------------------------------------------

(defn fetch-health
  "Fetch health status from server."
  []
  (let [resp (http/get (str base-url "/health"))]
    (json/parse-string (:body resp) true)))

(defn json-rpc-request
  "Build a JSON-RPC 2.0 request map."
  [id method params]
  {:jsonrpc "2.0"
   :id id
   :method method
   :params params})

(defn send-rpc
  "Send JSON-RPC request and return parsed response."
  [id method params]
  (let [body (json/generate-string (json-rpc-request id method params))
        resp (http/post mcp-url
                        {:headers {"Content-Type" "application/json"}
                         :body body
                         :throw false})]
    {:status (:status resp)
     :body (json/parse-string (:body resp) true)}))

;; -----------------------------------------------------------------------------
;; Test Commands
;; -----------------------------------------------------------------------------

(defn cmd-health
  "Test health endpoint."
  []
  (println "=== Health Check ===")
  (let [result (fetch-health)]
    (println (json/generate-string result {:pretty true}))
    (if (= "ok" (:status result))
      (do (println "✓ Server healthy") true)
      (do (println "✗ Server unhealthy") false))))

(defn cmd-init
  "Test MCP initialize handshake."
  []
  (println "=== Initialize ===")
  (let [{:keys [status body]} (send-rpc 0 "initialize"
                                        {:protocolVersion "2024-11-05"
                                         :capabilities {}
                                         :clientInfo {:name "bb-test" :version "1.0"}})]
    (println (json/generate-string body {:pretty true}))
    (if (and (= 200 status) (:result body))
      (do (println "✓ Initialize successful") true)
      (do (println "✗ Initialize failed") false))))

(defn cmd-tools
  "Test tools/list endpoint."
  []
  (println "=== List Tools ===")
  (cmd-init) ;; Must initialize first
  (let [{:keys [status body]} (send-rpc 1 "tools/list" {})]
    (println (json/generate-string body {:pretty true}))
    (if (and (= 200 status) (get-in body [:result :tools]))
      (do (println (str "✓ Found " (count (get-in body [:result :tools])) " tools")) true)
      (do (println "✗ Failed to list tools") false))))

(defn cmd-call
  "Test tools/call with given tool and arguments."
  [tool-name args-json]
  (println (str "=== Call: " tool-name " ==="))
  (cmd-init) ;; Must initialize first
  (let [args (if (string? args-json)
               (json/parse-string args-json true)
               args-json)
        {:keys [status body]} (send-rpc 2 "tools/call" {:name tool-name :arguments args})]
    (println (json/generate-string body {:pretty true}))
    (cond
      (and (= 200 status) (get-in body [:result :content]))
      (do (println "✓ Tool call successful") true)

      (:error body)
      (do (println (str "✗ Error: " (get-in body [:error :message]))) false)

      :else
      (do (println "✗ Unexpected response") false))))

(defn cmd-hello
  "Shortcut to test hello tool."
  [name]
  (cmd-call "hello" {:name name}))

(defn cmd-add
  "Shortcut to test add tool."
  [a b]
  (cmd-call "add" {:a (parse-long a) :b (parse-long b)}))

(defn cmd-concat
  "Shortcut to test concat tool."
  [& strings]
  (cmd-call "concat" {:strings (vec strings) :separator " "}))

(defn cmd-all
  "Run all tests and report summary."
  []
  (println "=== Running All Tests ===\n")
  (let [results [(cmd-health)
                 (do (println) (cmd-init))
                 (do (println) (cmd-tools))
                 (do (println) (cmd-hello "TestUser"))
                 (do (println) (cmd-add "100" "23"))
                 (do (println) (cmd-concat "foo" "bar" "baz"))]]
    (println "\n=== Summary ===")
    (let [passed (count (filter true? results))
          total (count results)]
      (println (str passed "/" total " tests passed"))
      (if (= passed total)
        (do (println "✓ All tests passed!") (System/exit 0))
        (do (println "✗ Some tests failed") (System/exit 1))))))

;; -----------------------------------------------------------------------------
;; CLI
;; -----------------------------------------------------------------------------

(defn print-usage
  "Print CLI usage information."
  []
  (println "Usage: bb scripts/http_test.clj [command] [args...]")
  (println)
  (println "Commands:")
  (println "  health              Check server health")
  (println "  init                Initialize MCP session")
  (println "  tools               List available tools")
  (println "  call <tool> <json>  Call a tool with JSON arguments")
  (println "  hello <name>        Shortcut for hello tool")
  (println "  add <a> <b>         Shortcut for add tool")
  (println "  concat <str>...     Shortcut for concat tool")
  (println "  all                 Run all tests")
  (println)
  (println "Environment:")
  (println "  MCP_HTTP_URL        Base URL (default: http://localhost:3000)"))

(let [[cmd & args] *command-line-args*]
  (case cmd
    "health" (cmd-health)
    "init" (cmd-init)
    "tools" (cmd-tools)
    "call" (cmd-call (first args) (second args))
    "hello" (cmd-hello (or (first args) "World"))
    "add" (cmd-add (or (first args) "1") (or (second args) "2"))
    "concat" (apply cmd-concat (or (seq args) ["hello" "world"]))
    "all" (cmd-all)
    (nil "-h" "--help") (print-usage)
    (do (println (str "Unknown command: " cmd))
        (print-usage)
        (System/exit 1))))
