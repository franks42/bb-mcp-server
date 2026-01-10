#!/usr/bin/env bb

;; LSP Stress Test Script
;;
;; Tests the clojure-lsp integration by rapidly clicking around the code browser
;; to trigger many workspace/symbol and other LSP requests, verifying no NUL
;; byte corruption occurs even under heavy load.
;;
;; Usage:
;;   bb scripts/stress_test_lsp.clj [iterations] [--playwright|--chrome-devtools]
;;
;; Prerequisites:
;;   - Code browser server running: bb server --http --config bb-code-browser-dev-system.edn
;;   - Browser open to http://localhost:8091
;;   - MCP server available for Playwright or Chrome DevTools
;;
;; The test clicks on various UI elements to trigger LSP requests and monitors
;; for any errors or NUL byte corruption warnings in the server logs.

(ns stress-test-lsp
    "LSP stress test - clicks around the code browser to verify no NUL byte corruption."
    (:require [babashka.process :as p]
              [cheshire.core :as json]
              [clojure.string :as str]))

(def default-iterations
     "Default number of iterations if not specified on command line."
     1000)

(defn mcp-call
  "Call an MCP tool and return the result."
  [server tool-name args]
  (let [args-json (json/generate-string args)
        result (p/shell {:out :string :err :string}
                        "bb" "mcp" "call" tool-name args-json "--mcp" server)]
    (when (zero? (:exit result))
      (json/parse-string (:out result) true))))

(defn playwright-snapshot
  "Get page snapshot using Playwright MCP."
  []
  (mcp-call "playwright" "browser_snapshot" {}))

(defn playwright-click
  "Click an element using Playwright MCP."
  [ref element-desc]
  (mcp-call "playwright" "browser_click" {:ref ref :element element-desc}))

(defn playwright-navigate
  "Navigate to URL using Playwright MCP."
  [url]
  (mcp-call "playwright" "browser_navigate" {:url url}))

(defn chrome-snapshot
  "Get page snapshot using Chrome DevTools MCP."
  []
  (mcp-call "chrome-devtools" "take_snapshot" {}))

(defn chrome-click
  "Click an element using Chrome DevTools MCP."
  [uid]
  (mcp-call "chrome-devtools" "click" {:uid uid}))

(defn chrome-navigate
  "Navigate to URL using Chrome DevTools MCP."
  [url]
  (mcp-call "chrome-devtools" "navigate_page" {:url url :type "url"}))

(defn extract-clickable-elements
  "Extract clickable element refs/uids from a snapshot."
  [snapshot mode]
  (cond
    ;; Playwright mode - extract refs from snapshot text
    (= mode :playwright)
    (let [text (or (:content snapshot) (str snapshot))]
      (->> (re-seq #"\[ref=([^\]]+)\]" text)
           (map second)
           (filter #(or (str/includes? % "link")
                        (str/includes? % "button")
                        (str/includes? % "listitem")
                        (str/includes? % "treeitem")))
           vec))

    ;; Chrome DevTools mode - extract uids
    (= mode :chrome-devtools)
    (let [text (or (:content snapshot) (str snapshot))]
      (->> (re-seq #"uid=\"([^\"]+)\"" text)
           (map second)
           vec))

    :else []))

(defn check-server-logs
  "Check server logs for NUL byte warnings."
  []
  (let [result (p/shell {:out :string :err :string :continue true}
                        "grep" "-c" "nul-bytes-detected\\|NUL" "/tmp/lsp-raw-debug.txt")]
    (if (zero? (:exit result))
      (parse-long (str/trim (:out result)))
      0)))

(defn run-stress-test
  "Run the stress test with specified mode and iterations."
  [mode iterations]
  (println (str "Starting LSP stress test with " iterations " iterations using " (name mode)))
  (println "=" (apply str (repeat 60 "=")))

  (let [snapshot-fn (if (= mode :playwright) playwright-snapshot chrome-snapshot)
        click-fn (if (= mode :playwright)
                   (fn [ref] (playwright-click ref "clickable element"))
                   chrome-click)
        navigate-fn (if (= mode :playwright) playwright-navigate chrome-navigate)
        start-time (System/currentTimeMillis)
        initial-nul-count (check-server-logs)]

    ;; Navigate to code browser
    (println "Navigating to code browser...")
    (navigate-fn "http://localhost:8091")
    (Thread/sleep 2000)

    ;; Main test loop
    (loop [i 0
           clicks 0
           errors 0]
          (if (>= i iterations)
        ;; Done - report results
            (let [end-time (System/currentTimeMillis)
                  duration-sec (/ (- end-time start-time) 1000.0)
                  final-nul-count (check-server-logs)
                  new-nul-warnings (- final-nul-count initial-nul-count)]
              (println)
              (println "=" (apply str (repeat 60 "=")))
              (println "STRESS TEST COMPLETE")
              (println "=" (apply str (repeat 60 "=")))
              (println (format "Iterations: %d" iterations))
              (println (format "Successful clicks: %d" clicks))
              (println (format "Errors: %d" errors))
              (println (format "Duration: %.1f seconds" duration-sec))
              (println (format "Clicks/second: %.1f" (/ clicks duration-sec)))
              (println (format "New NUL byte warnings: %d" new-nul-warnings))
              (println)
              (if (zero? new-nul-warnings)
                (do (println "SUCCESS: No NUL byte corruption detected!")
                    {:success true :clicks clicks :errors errors :nul-warnings new-nul-warnings})
                (do (println "FAILURE: NUL byte corruption detected!")
                    {:success false :clicks clicks :errors errors :nul-warnings new-nul-warnings})))

        ;; Continue testing
            (do
             (when (zero? (mod i 100))
               (println (format "Progress: %d/%d iterations, %d clicks, %d errors"
                                i iterations clicks errors)))

          ;; Get snapshot and find clickable elements
             (let [snapshot (snapshot-fn)
                   elements (extract-clickable-elements snapshot mode)]
               (if (seq elements)
              ;; Click a random element
                 (let [elem (rand-nth elements)]
                   (try
                    (click-fn elem)
                    (Thread/sleep (+ 50 (rand-int 100))) ;; 50-150ms between clicks
                    (recur (inc i) (inc clicks) errors)
                    (catch Exception _e
                           (recur (inc i) clicks (inc errors)))))
              ;; No elements found, wait and retry
                 (do
                  (Thread/sleep 500)
                  (recur (inc i) clicks errors)))))))))

(defn -main
  "Entry point for LSP stress test script."
  [& args]
  (let [iterations (if (first args)
                     (parse-long (first args))
                     default-iterations)
        mode (cond
               (some #{"--playwright"} args) :playwright
               (some #{"--chrome-devtools"} args) :chrome-devtools
               :else :playwright)]

    (println "LSP Stress Test")
    (println "===============")
    (println (format "Mode: %s" (name mode)))
    (println (format "Iterations: %d" iterations))
    (println)

    ;; Check prerequisites
    (println "Checking prerequisites...")
    (let [servers (p/shell {:out :string :err :string :continue true}
                           "bb" "mcp" "servers")]
      (when-not (str/includes? (:out servers) "code-browser")
        (println "ERROR: code-browser-dev server not running!")
        (println "Start with: bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev")
        (System/exit 1)))

    (println "Prerequisites OK")
    (println)

    ;; Run the test
    (let [result (run-stress-test mode iterations)]
      (System/exit (if (:success result) 0 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
