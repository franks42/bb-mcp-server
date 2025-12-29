#!/usr/bin/env bb

(ns pprint
    "Pretty-print EDN from stdin or file.

   Usage:
     bb pprint              # Read EDN from stdin
     bb pprint file.edn     # Read EDN from file
     bb pprint -            # Explicitly read from stdin

   Examples:
     echo '{:a 1 :b 2}' | bb pprint
     bb clojure-lsp status --pprint | bb pprint
     cat data.edn | bb pprint
     bb pprint data.edn"
    (:require [clojure.pprint :as pp]
              [clojure.edn :as edn]))

(defn read-input
  "Read input from file or stdin."
  [source]
  (if (or (nil? source) (= "-" source))
    (slurp *in*)
    (slurp source)))

(defn -main
  "Entry point."
  [& args]
  (try
   (let [source (first args)
         input (read-input source)
         data (edn/read-string input)]
     (pp/pprint data))
   (catch Exception e
          (binding [*out* *err*]
                   (println (format "Error: %s" (.getMessage e))))
          (System/exit 1))))

(apply -main *command-line-args*)
