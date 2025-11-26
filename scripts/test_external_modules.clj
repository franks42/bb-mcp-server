#!/usr/bin/env bb

;; Test script for external module loading
;; Tests the add-external! function in bootstrap.clj

(require '[bb-mcp-server.bootstrap :as boot])

(println "\n=== Testing External Module Loading ===\n")

;; First, ensure internal module paths are loaded
(println "1. Loading internal module paths...")
(let [result (boot/ensure-module-paths!)]
  (println "   Added:" (:added result) "paths"))

;; Test 1: Load a single external module
(println "\n2. Testing single module load (echo)...")
(let [result (boot/add-external! "/tmp/external-modules/echo")]
  (println "   Result:" result)
  (if (= :single (:type result))
    (println "   ✓ Single module detected correctly")
    (println "   ✗ Expected :single type")))

;; Test 2: Load a modules collection
(println "\n3. Testing modules collection load...")
(let [result (boot/add-external! "/tmp/external-modules")]
  (println "   Result type:" (:type result))
  (if (= :collection (:type result))
    (do
      (println "   ✓ Collection detected correctly")
      (println "   Modules:" (mapv :name (:modules result))))
    (println "   ✗ Expected :collection type")))

;; Test 3: List external modules
(println "\n4. Listing external modules...")
(let [externals (boot/list-external-modules)]
  (println "   External modules:" (count externals))
  (doseq [{:keys [name path]} externals]
    (println "   -" name "at" path)))

;; Test 4: Try to require a namespace from external module
(println "\n5. Testing namespace require from external module...")
(try
  (require '[echo.core :as echo])
  (println "   ✓ Successfully required echo.core")
  ;; Try to get the module definition
  (when-let [module-var (resolve 'echo.core/module)]
    (let [module @module-var]
      (println "   Module name:" (:name module))
      (println "   Tools:" (mapv :name (:tools module)))))
  (catch Exception e
    (println "   ✗ Failed to require:" (.getMessage e))))

;; Test 5: Error handling - non-existent path
(println "\n6. Testing error handling (non-existent path)...")
(let [result (boot/add-external! "/tmp/does-not-exist")]
  (if (:error result)
    (println "   ✓ Error detected:" (:error result))
    (println "   ✗ Expected error")))

;; Test 6: Error handling - invalid path (no module.edn)
(println "\n7. Testing error handling (no module.edn)...")
(let [result (boot/add-external! "/tmp")]
  (if (:error result)
    (println "   ✓ Error detected:" (:error result))
    (println "   ✗ Expected error")))

(println "\n=== External Module Loading Tests Complete ===\n")
