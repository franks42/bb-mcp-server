#!/usr/bin/env bb
;; Lint file(s), auto-fix paren errors if found, re-lint
;; Usage: bb lint-fix <file> [file2 ...]
;;
;; Workflow:
;;   1. Run clj-kondo on file(s)
;;   2. If paren/bracket mismatch detected, run parmezan to fix
;;   3. Re-run clj-kondo and show final result
;;
;; Exit code: from final clj-kondo run

(ns lint-fix
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn- paren-error?
  "Check if output contains paren/bracket mismatch errors."
  [output]
  (or (str/includes? output "Mismatched bracket")
      (str/includes? output "Found an opening")
      (str/includes? output "Expected a")
      (str/includes? output "Unmatched bracket")
      (str/includes? output "Missing delimiter")))

(defn- run-lint
  "Run clj-kondo on files. Returns {:exit, :output}."
  [files]
  (let [result (p/shell {:out :string
                         :err :string
                         :continue true}
                        "clj-kondo" "--lint" (str/join " " files))]
    {:exit (:exit result)
     :output (str (:out result) (:err result))}))

(defn- run-fix-parens
  "Run parmezan on a single file. Returns true if fixed."
  [file]
  (let [result (p/shell {:continue true} "parmezan" "--file" file "--write")]
    (= 1 (:exit result))))  ;; exit 1 = fixed

(defn- lint-fix!
  "Lint files, fix parens if needed, re-lint."
  [files]
  (println (str "Linting: " (str/join ", " files)))
  (println "")

  ;; First lint
  (let [{:keys [exit output]} (run-lint files)]
    (println output)

    (if (paren-error? output)
      ;; Found paren errors - try to fix
      (do
        (println "─── Paren/bracket errors detected, attempting fix... ───")
        (println "")
        (let [fixed-files (filter run-fix-parens files)]
          (if (seq fixed-files)
            (do
              (println (str "Fixed: " (str/join ", " fixed-files)))
              (println "")
              (println "─── Re-linting... ───")
              (println "")
              (let [{:keys [exit output]} (run-lint files)]
                (println output)
                (when (paren-error? output)
                  (println "")
                  (println "⚠ Still has paren errors - manual fix needed"))
                (System/exit exit)))
            (do
              (println "No files could be auto-fixed - manual fix needed")
              (System/exit exit)))))

      ;; No paren errors - just return lint result
      (System/exit exit))))

;; Main
(let [files *command-line-args*]
  (if (empty? files)
    (do
      (println "Usage: bb lint-fix <file> [file2 ...]")
      (println "")
      (println "Lints file(s), auto-fixes paren errors if found, re-lints.")
      (System/exit 1))
    (lint-fix! files)))
