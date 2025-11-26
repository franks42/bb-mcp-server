#!/usr/bin/env bb

;; Test script for dynamic module loading at runtime
;; Tests the load-new-module! function

(require '[bb-mcp-server.bootstrap :as boot])
(require '[bb-mcp-server.module.system :as system])
(require '[bb-mcp-server.module.ns-loader :as loader])
(require '[bb-mcp-server.registry :as registry])

(println "\n=== Testing Dynamic Module Loading ===\n")

;; First, ensure internal module paths are loaded
(println "1. Loading internal module paths...")
(let [result (boot/ensure-module-paths!)]
  (println "   Added:" (:added result) "paths"))

;; Create a minimal system (without echo or hello)
(println "\n2. Starting minimal system...")

;; Reset loader state and load only strings and math
(loader/reset-state!)

(println "   Loading strings module...")
(loader/load-module "modules/strings")

(println "   Loading math module...")
(loader/load-module "modules/math")

(println "   Starting modules...")
(loader/start-module! "strings" {} {})
(loader/start-module! "math" {} {})

;; Check initial tool count
(println "\n3. Initial state:")
(let [tools (registry/list-tools)]
  (println "   Tools registered:" (count tools))
  (println "   Tool names:" (mapv :name tools)))

;; Now dynamically load external modules (echo and hello)
(println "\n4. Dynamically loading external echo module at runtime...")
(println "   Path: /tmp/external-modules/echo")

(let [result (system/load-new-module! "/tmp/external-modules/echo")]
  (if (:success result)
    (do
     (println "   ✓ Echo module loaded successfully!")
     (println "   Module name:" (get-in result [:success :module-name])))
    (do
     (println "   ✗ Failed to load echo module")
     (println "   Error:" (:error result)))))

;; Now load hello module (which is NOT in system.edn)
(println "\n5. Dynamically loading external hello module at runtime...")
(println "   Path: /tmp/external-modules/hello")

(let [result (system/load-new-module! "/tmp/external-modules/hello")]
  (if (:success result)
    (do
     (println "   ✓ Hello module loaded successfully!")
     (println "   Module name:" (get-in result [:success :module-name])))
    (do
     (println "   ✗ Failed to load hello module")
     (println "   Error:" (:error result)))))

;; Check final tool count
(println "\n6. Final state:")
(let [tools (registry/list-tools)]
  (println "   Tools registered:" (count tools))
  (println "   Tool names:" (mapv :name tools)))

;; Try to call the dynamically loaded echo tool
(println "\n7. Calling dynamically loaded echo tool...")
(let [echo-tool (registry/get-tool "echo.echo")]
  (if echo-tool
    (let [result ((:handler echo-tool) {:message "Dynamic loading works!"})]
      (println "   Result:" result))
    (println "   ✗ Tool not found")))

;; Try to call the dynamically loaded hello tool
(println "\n8. Calling dynamically loaded hello tool...")
(let [hello-tool (registry/get-tool "hello.hello")]
  (if hello-tool
    (let [result ((:handler hello-tool) {:name "External Module"})]
      (println "   Result:" result))
    (println "   ✗ Tool not found")))

(println "\n=== Dynamic Loading Test Complete ===\n")
