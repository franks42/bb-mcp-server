# Babashka Reference

## Overview
Babashka is a fast-starting Clojure scripting environment. All code in this project must be Babashka-compatible.

## Key Differences from JVM Clojure

### Supported
- Most clojure.core functions
- babashka.fs for filesystem operations
- babashka.process for shell commands
- http-kit for HTTP servers
- EDN/JSON parsing

### Not Supported
- Full Java interop (limited subset only)
- Some advanced macros
- JVM-specific libraries

## Common Patterns

### Script Template
```clojure
#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[taoensso.trove :as log])

(defn main []
  (log/log! {:level :info :msg "Starting task"})
  (try
    ;; work here
    (log/log! {:level :info :msg "Task completed"})
    (catch Exception e
      (log/log! {:level :error :msg "Task failed" :error e})
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (main))
```

### Process Execution
```clojure
(require '[babashka.process :as p])

;; Simple shell command
(p/shell "ls" "-la")

;; Capture output
(-> (p/shell {:out :string} "git" "status")
    :out)
```

### Filesystem Operations
```clojure
(require '[babashka.fs :as fs])

;; Check if file exists
(fs/exists? "path/to/file")

;; Create directory
(fs/create-dirs "path/to/dir")

;; List files
(fs/list-dir "path/to/dir")
```
