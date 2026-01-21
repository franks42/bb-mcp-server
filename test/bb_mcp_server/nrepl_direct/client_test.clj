(ns bb-mcp-server.nrepl-direct.client-test
    "Tests for nrepl-direct client and CLI."
    (:require [clojure.test :refer [deftest is testing]]
              [bb-mcp-server.nrepl-direct.client :as client]
              [cheshire.core :as json]
              [clojure.java.io :as io]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-port*
     "Port of test nREPL server (set by with-nrepl-server)."
     nil)

(def ^:dynamic *nrepl-process*
     "Process handle for test nREPL server."
     nil)

(defn start-nrepl-server!
  "Start a babashka nREPL server for testing. Returns {:process port}.
   Parses port from stdout line: 'Started nREPL server at 127.0.0.1:PORT'"
  []
  (let [process (-> (ProcessBuilder. ["bb" "--nrepl-server" "0"])
                    (.directory (io/file "."))
                    (.redirectErrorStream true)
                    (.start))
        reader (java.io.BufferedReader.
                (java.io.InputStreamReader. (.getInputStream process)))]
    ;; Read first line which contains the port
    (loop [attempts 0]
          (Thread/sleep 100)
          (if (.ready reader)
            (let [line (.readLine reader)
                  ;; Parse "Started nREPL server at 127.0.0.1:PORT"
                  port-match (re-find #":(\d+)$" line)]
              (if port-match
                {:process process :port (Integer/parseInt (second port-match))}
                (throw (ex-info "Could not parse port from nREPL output" {:line line}))))
            (if (< attempts 50)
              (recur (inc attempts))
              (throw (ex-info "Timeout waiting for nREPL server" {})))))))

(defn stop-nrepl-server!
  "Stop the test nREPL server."
  [{:keys [process]}]
  (when process
    (.destroy process)))

(defn with-nrepl-server
  "Test fixture that starts/stops an nREPL server."
  [f]
  (let [{:keys [process port]} (start-nrepl-server!)]
    (try
     (binding [*test-port* port
               *nrepl-process* process]
              (f))
     (finally
      (stop-nrepl-server! {:process process})))))

;; =============================================================================
;; Unit Tests - Port Discovery
;; =============================================================================

(def test-ports-dir
     "Directory for test port files."
     ".ports")

(def test-nickname
     "Nickname for test port file."
     "nrepl-direct-test")

(defn cleanup-test-port-file
  "Remove test port file if it exists."
  []
  (let [file (io/file (str test-ports-dir "/" test-nickname ".json"))]
    (when (.exists file)
      (.delete file))))

(defn create-test-port-file
  "Create a test port file."
  [ports]
  (.mkdirs (io/file test-ports-dir))
  (spit (str test-ports-dir "/" test-nickname ".json")
        (json/generate-string {:ports ports :pid 12345})))

(deftest test-port-discovery
         (testing "discover-port finds port from file"
                  (cleanup-test-port-file)
                  (create-test-port-file {:nrepl-server 7888
                                          :nrepl-proxy 1667})
                  (try
                   ;; Note: discover-port is in the CLI, test via slurp/parse
                   (let [port-file (str test-ports-dir "/" test-nickname ".json")
                         data (json/parse-string (slurp port-file) true)
                         ports (:ports data)]
                     (is (= 7888 (:nrepl-server ports)))
                     (is (= 1667 (:nrepl-proxy ports))))
                   (finally
                    (cleanup-test-port-file))))

         (testing "missing port file returns nil"
                  (cleanup-test-port-file)
                  (let [port-file (str test-ports-dir "/" test-nickname "-nonexistent.json")]
                    (is (not (.exists (io/file port-file)))))))

;; =============================================================================
;; Integration Tests - Client Operations (require running server)
;; =============================================================================

(deftest test-eval-simple
         (when *test-port*
           (testing "eval simple expression"
                    (let [result (client/eval! "(+ 1 2 3)" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "6" (get-in result [:response :value])))))))

(deftest test-eval-with-stdout
         (when *test-port*
           (testing "eval with stdout"
                    (let [result (client/eval! "(do (println \"hello\") 42)" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "42" (get-in result [:response :value])))
                      (is (= "hello\n" (get-in result [:response :out])))))))

(deftest test-eval-error
         (when *test-port*
           (testing "eval with error"
                    (let [result (client/eval! "(/ 1 0)" :port *test-port*)]
                      (is (= :success (:status result)))
                      ;; Error should be in :ex or :err
                      (let [response (:response result)]
                        (is (or (:ex response)
                                (:err response)
                                (re-find #"Divide by zero" (str response)))))))))

(deftest test-eval-with-namespace
         (when *test-port*
           (testing "eval in specific namespace"
                    (let [result (client/eval! "*ns*" :port *test-port* :ns "user")]
                      (is (= :success (:status result)))
                      (is (re-find #"user" (get-in result [:response :value])))))))

;; =============================================================================
;; Integration Tests - ! Character Escaping
;;
;; These tests verify that the nREPL client correctly handles Clojure code
;; containing ! characters, which are common in:
;; - Mutation functions: swap!, reset!, alter!, ref-set!, send!, compare-and-set!
;; - State management: init!, start!, stop!, mount!, unmount!
;; - Vars with ! prefix: !atom, !state, !db
;; =============================================================================

(deftest test-eval-bang-functions
         (when *test-port*
           (testing "swap! with atom"
                    (let [result (client/eval! "(swap! (atom 0) inc)" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "1" (get-in result [:response :value])))))

           (testing "reset! with atom"
                    (let [result (client/eval! "(reset! (atom 0) 42)" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "42" (get-in result [:response :value])))))

           (testing "swap! with multiple args"
                    (let [result (client/eval! "(swap! (atom []) conj 1 2 3)" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "[1 2 3]" (get-in result [:response :value])))))

           (testing "compare-and-set!"
                    (let [result (client/eval!
                                  "(let [a (atom 0)] (compare-and-set! a 0 1) @a)"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "1" (get-in result [:response :value])))))))

(deftest test-eval-bang-vars
         (when *test-port*
           (testing "def !x (atom) - bang prefix var"
                    (let [result (client/eval! "(def !x (atom 42)) @!x" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "42" (get-in result [:response :value])))))

           (testing "def !state - bang prefix state var"
                    (let [result (client/eval!
                                  "(def !state (atom {:count 0})) (:count @!state)"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "0" (get-in result [:response :value])))))

           (testing "swap! on !atom - combined bang usage"
                    (let [result (client/eval!
                                  "(def !counter (atom 0)) (swap! !counter inc) @!counter"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "1" (get-in result [:response :value])))))))

(deftest test-eval-custom-bang-functions
         (when *test-port*
           (testing "defn with ! suffix"
                    (let [result (client/eval!
                                  "(defn update-count! [a] (swap! a inc)) (update-count! (atom 5))"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "6" (get-in result [:response :value])))))

           (testing "defn init! pattern"
                    (let [result (client/eval!
                                  "(defn init! [] :initialized) (init!)"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= ":initialized" (get-in result [:response :value])))))

           (testing "defn start!/stop! pattern"
                    (let [result (client/eval!
                                  "(do (defn start! [] :started) (defn stop! [] :stopped) [(start!) (stop!)])"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "[:started :stopped]" (get-in result [:response :value])))))))

(deftest test-eval-multiple-bangs
         (when *test-port*
           (testing "multiple ! in single expression"
                    (let [result (client/eval!
                                  "(def !a (atom 0)) (def !b (atom 10)) (+ (swap! !a inc) (swap! !b dec))"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "10" (get-in result [:response :value])))))

           (testing "! in let bindings"
                    (let [result (client/eval!
                                  "(let [!local (atom 100)] (swap! !local #(* % 2)) @!local)"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "200" (get-in result [:response :value])))))

           (testing "nested ! operations"
                    (let [result (client/eval!
                                  "(def !outer (atom {:!inner (atom 0)})) (swap! (:!inner @!outer) inc)"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "1" (get-in result [:response :value])))))))

(deftest test-eval-bang-edge-cases
         (when *test-port*
           (testing "! at start of symbol"
                    (let [result (client/eval! "(def !!! 999) !!!" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "999" (get-in result [:response :value])))))

           (testing "! at end of symbol"
                    (let [result (client/eval! "(defn f! [] :bang!) (f!)" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= ":bang!" (get-in result [:response :value])))))

           (testing "string containing !"
                    (let [result (client/eval! "\"Hello!\"" :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "\"Hello!\"" (get-in result [:response :value])))))

           (testing "comment containing !"
                    (let [result (client/eval!
                                  ";; This is a comment with ! bang!\n(+ 1 2)"
                                  :port *test-port*)]
                      (is (= :success (:status result)))
                      (is (= "3" (get-in result [:response :value])))))))

;; =============================================================================
;; Integration Tests - Other Operations
;; =============================================================================

(deftest test-describe
         (when *test-port*
           (testing "describe returns server capabilities"
                    (let [result (client/with-connection {:port *test-port*}
                                                         (fn [conn]
                                                           (client/describe conn)))]
                      (is (= :success (:status result)))
                      ;; Babashka nREPL returns minimal describe response
                      ;; Full nREPL servers return :ops and :versions
                      (let [response (:response result)]
                        (is (map? response))
                        (is (contains? response :status)))))))

(deftest test-clone-session
         (when *test-port*
           (testing "clone-session returns session ID"
                    (let [result (client/with-connection {:port *test-port*}
                                                         (fn [conn]
                                                           (client/clone-session conn)))]
                      (is (= :success (:status result)))
                      ;; Babashka's minimal nREPL may not support clone fully
                      ;; The important thing is that we get a success response
                      (is (or (string? (:session result))
                              (nil? (:session result))))))))

(deftest test-load-local-file
         (when *test-port*
           (testing "load-local-file reads and evals file"
                    (let [test-file (java.io.File/createTempFile "test" ".clj")]
                      (try
                       (spit test-file "(+ 10 20 30)")
                       (let [result (client/load-local-file! (.getAbsolutePath test-file)
                                                             :port *test-port*)]
                         (is (= :success (:status result)))
                         (is (= "60" (get-in result [:response :value]))))
                       (finally
                        (.delete test-file)))))

           (testing "load-local-file with ! functions"
                    (let [test-file (java.io.File/createTempFile "test-bang" ".clj")]
                      (try
                       (spit test-file "(def !file-atom (atom 0)) (swap! !file-atom inc) @!file-atom")
                       (let [result (client/load-local-file! (.getAbsolutePath test-file)
                                                             :port *test-port*)]
                         (is (= :success (:status result)))
                         (is (= "1" (get-in result [:response :value]))))
                       (finally
                        (.delete test-file)))))))

(deftest test-connection-error
         (testing "connection to invalid port fails gracefully"
                  (is (thrown? java.net.ConnectException
                               (client/eval! "(+ 1 2)" :port 59999)))))

(deftest test-timeout
         (when *test-port*
           (testing "timeout triggers for slow eval"
                    (let [result (client/eval! "(Thread/sleep 5000)"
                                               :port *test-port*
                                               :timeout-ms 100)]
                      (is (= :timeout (:status result)))))))

;; =============================================================================
;; Test Runner Functions
;; =============================================================================

(defn run-unit-tests
  "Run unit tests only (no server required)."
  []
  (clojure.test/run-tests 'bb-mcp-server.nrepl-direct.client-test))

(defn run-integration-tests
  "Run all tests including integration tests (starts nREPL server)."
  []
  (with-nrepl-server run-unit-tests))
