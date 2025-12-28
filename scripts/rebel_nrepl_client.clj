#!/usr/bin/env bb

;; Open iTerm2 with rebel-readline connected to an nREPL server
;;
;; Usage: bb rebel-nrepl-client [port]
;;   port - nREPL port (default: 7888)
;;
;; Examples:
;;   bb rebel-nrepl-client          # Connect to nrepl-test-server on port 7888
;;   bb rebel-nrepl-client 1667     # Connect to nrepl-proxy-server
;;
;; First start an nREPL server:
;;   bb server --http --config bb-nrepl-server-system.edn --nickname nrepl-server

(require '[clojure.java.io :as io])

(defn open-iterm-with-command!
  "Open a new iTerm2 window and execute a command."
  [command]
  (let [script (str "tell application \"iTerm2\"\n"
                    "    create window with default profile\n"
                    "    tell current session of current window\n"
                    "        write text \"" command "\"\n"
                    "    end tell\n"
                    "end tell\n")
        script-file (java.io.File/createTempFile "iterm_cmd" ".scpt")]
    (spit script-file script)
    (let [result (-> (ProcessBuilder. ["osascript" (.getAbsolutePath script-file)])
                     (.inheritIO)
                     (.start)
                     (.waitFor))]
      (.delete script-file)
      result)))

(defn -main []
  (let [args *command-line-args*
        port (if (seq args)
               (first args)
               "7888")
        cmd (str "clojure -T:nrebel :port " port)]
    (println (str "Opening iTerm2 with rebel-readline on port " port "..."))
    (open-iterm-with-command! cmd)
    (println "Done. Check your iTerm2 window.")))

(-main)
