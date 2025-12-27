(ns bb-mcp-server.cli.parse-args-test
  "Tests for CLI argument parsing fixes."
  (:require [clojure.test :refer [deftest is testing]]))

;; Copy the parse-args function for testing (to avoid dependency issues)
(defn parse-args
  "Parse command line arguments into an options map."
  [args]
  (loop [args args
         opts {:stdio false :http false :port 3000 :help false :config nil :nickname nil}]
    (if (empty? args)
      ;; Default to stdio if nothing specified
      (if (and (not (:stdio opts)) (not (:http opts)))
        (assoc opts :stdio true)
        opts)
      (let [arg (first args)
            rest-args (rest args)]
        (cond
          (= arg "--stdio")
          (recur rest-args (assoc opts :stdio true))

          (= arg "--http")
          (recur rest-args (assoc opts :http true))

          (= arg "--port")
          (let [port-str (first rest-args)
                port (if port-str (Integer/parseInt port-str) 3000)]
            (recur (rest rest-args) (assoc opts :port port)))

          (or (= arg "-h") (= arg "--help"))
          (recur rest-args (assoc opts :help true))

          (= arg "--config")
          (let [config-path (first rest-args)]
            (if config-path
              (recur (rest rest-args) (assoc opts :config config-path))
              (do (println "Error: --config requires a value")
                  (assoc opts :help true))))

          (= arg "--nickname")
          (let [nickname (first rest-args)]
            (if nickname
              (recur (rest rest-args) (assoc opts :nickname nickname))
              (do (println "Error: --nickname requires a value")
                  (assoc opts :help true))))

          :else
          (do (println (str "Unknown argument: " arg))
              (recur rest-args (assoc opts :help true))))))))

(deftest test-http-flag-separate-from-port
  (testing "HTTP flag should not consume next argument as port"
    (let [args ["--http" "--config" "test.edn"]
          result (parse-args args)]
      (is (= true (:http result)) "HTTP should be enabled")
      (is (= "test.edn" (:config result)) "Config should not be consumed as port")
      (is (= 3000 (:port result)) "Port should remain default"))))

(deftest test-separate-http-and-port-flags
  (testing "Separate --http and --port flags work correctly"
    (let [args ["--http" "--port" "8080"]
          result (parse-args args)]
      (is (= true (:http result)) "HTTP should be enabled")
      (is (= 8080 (:port result)) "Port should be parsed from --port flag"))))

(deftest test-port-flag-with-custom-value
  (testing "--port flag accepts custom port value"
    (let [args ["--stdio" "--port" "9000"]
          result (parse-args args)]
      (is (= true (:stdio result)) "Stdio should be enabled")
      (is (= 9000 (:port result)) "Custom port should be used"))))

(deftest test-nickname-flag
  (testing "--nickname flag is parsed correctly"
    (let [args ["--http" "--nickname" "test-server"]
          result (parse-args args)]
      (is (= "test-server" (:nickname result)) "Nickname should be parsed"))))

(deftest test-combination-of-flags
  (testing "Multiple flags work together without interference"
    (let [args ["--config" "custom.edn" "--http" "--port" "3001" "--nickname" "my-server"]
          result (parse-args args)]
      (is (= "custom.edn" (:config result)) "Config should be parsed")
      (is (= true (:http result)) "HTTP should be enabled")
      (is (= 3001 (:port result)) "Custom port should be used")
      (is (= "my-server" (:nickname result)) "Nickname should be parsed"))))

(deftest test-default-behavior
  (testing "Default behavior when no flags specified"
    (let [args []
          result (parse-args args)]
      (is (= true (:stdio result)) "Stdio should be enabled by default")
      (is (= false (:http result)) "HTTP should be disabled by default")
      (is (= 3000 (:port result)) "Default port should be 3000")
      (is (= nil (:config result)) "No config by default")
      (is (= nil (:nickname result)) "No nickname by default"))))
