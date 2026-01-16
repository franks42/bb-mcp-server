(in-ns 'bb-mcp-server.target-ns)

(defn test-fn-in-secondary
  "Function defined after in-ns."
  [x]
  (str "from secondary: " x))
