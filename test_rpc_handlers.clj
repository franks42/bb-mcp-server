#!/usr/bin/env bb

(require '[bb-mcp-server.test-harness :as th])

(println "\n=== Testing MCP RPC Handlers ===\n")

(let [result (th/run-full-test!)]
  (println "\n=== Test Results ===")
  (println "Success:" (:success result))
  (println "\n1. Initialize Response:")
  (clojure.pprint/pprint (:initialize result))
  (println "\n2. Tools/List Response:")
  (clojure.pprint/pprint (:tools-list result))
  (println "\n3. Tools/Call Response (World):")
  (clojure.pprint/pprint (:tools-call-1 result))
  (println "\n4. Tools/Call Response (Alice):")
  (clojure.pprint/pprint (:tools-call-2 result))

  (if (:success result)
    (do
      (println "\n✅ All RPC handlers working correctly!")
      (System/exit 0))
    (do
      (println "\n❌ Some tests failed!")
      (System/exit 1))))
