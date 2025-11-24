(ns rest-api.docs
    "HTML documentation generator for REST API.

   Generates a human-readable HTML page from OpenAPI spec."
    (:require [clojure.string :as str]))

(defn- escape-html
  "Escape HTML special characters."
  [s]
  (when s
    (-> (str s)
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;"))))

(defn- schema-to-html
  "Convert JSON schema to readable HTML."
  [schema indent]
  (let [pad (apply str (repeat indent "&nbsp;&nbsp;"))]
    (cond
      (nil? schema) "<span class=\"type\">any</span>"

      (= "object" (:type schema))
      (let [props (:properties schema)
            required (set (:required schema []))]
        (if (empty? props)
          "<span class=\"type\">object</span>"
          (str "<span class=\"type\">object</span> {\n"
               (str/join ",\n"
                         (for [[k v] props]
                              (str pad "&nbsp;&nbsp;<span class=\"prop\">" (name k) "</span>"
                                   (when (required (name k)) "<span class=\"required\">*</span>")
                                   ": " (schema-to-html v (inc indent)))))
               "\n" pad "}")))

      (= "array" (:type schema))
      (str "<span class=\"type\">array</span>&lt;"
           (schema-to-html (:items schema) indent) "&gt;")

      (:enum schema)
      (str "<span class=\"type\">" (:type schema "string") "</span> "
           "<span class=\"enum\">[" (str/join " | " (map #(str "\"" % "\"") (:enum schema))) "]</span>")

      :else
      (str "<span class=\"type\">" (or (:type schema) "any") "</span>"))))

(defn- tool-card-html
  "Generate HTML card for a single tool."
  [tool]
  (let [{:keys [name description inputSchema href]} tool]
    (str "
    <div class=\"tool-card\" id=\"" (escape-html name) "\">
      <div class=\"tool-header\">
        <h3>" (escape-html name) "</h3>
        <span class=\"method get\">GET</span>
        <span class=\"method post\">POST</span>
      </div>
      <p class=\"description\">" (escape-html description) "</p>

      <div class=\"endpoints\">
        <div class=\"endpoint\">
          <code class=\"get\">GET " (escape-html href) "</code>
          <span class=\"hint\">Get tool metadata</span>
        </div>
        <div class=\"endpoint\">
          <code class=\"post\">POST " (escape-html href) "</code>
          <span class=\"hint\">Call this tool</span>
        </div>
      </div>

      " (when (and inputSchema (seq (:properties inputSchema)))
          (str "<div class=\"schema\">
            <h4>Input Schema</h4>
            <pre>" (schema-to-html inputSchema 0) "</pre>
          </div>")) "

      <div class=\"try-it\">
        <h4>Try it</h4>
        <pre class=\"curl\">curl -X POST " (escape-html href) " \\
  -H \"Content-Type: application/json\" \\
  -d '{}'</pre>
      </div>
    </div>")))

(defn- group-by-module
  "Group tools by module, returning sorted map."
  [tools]
  (let [grouped (group-by #(or (:module %) "other") tools)]
    (into (sorted-map) grouped)))

(defn- module-toc-html
  "Generate TOC section for a module."
  [module-name tools]
  (str "<div class=\"toc-module\">
    <h3><a href=\"#module-" (escape-html module-name) "\">" (escape-html module-name) "</a> <span class=\"count\">(" (count tools) ")</span></h3>
    <ul class=\"toc-list\">"
       (str/join ""
                 (for [tool tools]
                      (str "<li><a href=\"#" (escape-html (:name tool)) "\">" (escape-html (:name tool)) "</a></li>")))
       "</ul></div>"))

(defn- module-section-html
  "Generate HTML section for a module's tools."
  [module-name tools]
  (str "<section class=\"module-section\" id=\"module-" (escape-html module-name) "\">
    <h2 class=\"module-heading\">" (escape-html module-name) " <span class=\"tool-count\">(" (count tools) " tools)</span></h2>
    <p class=\"module-endpoint\"><code>GET /api/modules/" (escape-html module-name) "/tools</code></p>"
       (str/join "" (map tool-card-html tools))
       "</section>"))

(defn generate-html
  "Generate complete HTML documentation page.

   Arguments:
     tools - Sequence of tool definitions
     opts  - Options map:
             :title      - Page title
             :server-url - Base URL for examples"
  [tools & [{:keys [title]
             :or {title "bb-mcp-server REST API"}}]]
  (let [by-module (group-by-module tools)
        module-count (count by-module)]
    (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
  <meta charset=\"UTF-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <title>" (escape-html title) "</title>
  <style>
    :root {
      --bg: #1a1a2e;
      --card-bg: #16213e;
      --text: #eee;
      --text-dim: #888;
      --accent: #0f3460;
      --get: #61affe;
      --post: #49cc90;
      --required: #f93e3e;
      --type: #e9c46a;
      --enum: #a8dadc;
      --module: #9b59b6;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: var(--bg);
      color: var(--text);
      line-height: 1.6;
      padding: 2rem;
    }
    .container { max-width: 900px; margin: 0 auto; }
    header {
      text-align: center;
      margin-bottom: 2rem;
      padding-bottom: 1rem;
      border-bottom: 1px solid var(--accent);
    }
    h1 { font-size: 2rem; margin-bottom: 0.5rem; }
    .subtitle { color: var(--text-dim); }
    .quick-links {
      display: flex;
      gap: 1rem;
      justify-content: center;
      margin-top: 1rem;
      flex-wrap: wrap;
    }
    .quick-links a {
      color: var(--get);
      text-decoration: none;
      padding: 0.5rem 1rem;
      border: 1px solid var(--get);
      border-radius: 4px;
      font-size: 0.9rem;
    }
    .quick-links a:hover { background: var(--get); color: var(--bg); }

    .toc {
      background: var(--card-bg);
      padding: 1.5rem;
      border-radius: 8px;
      margin-bottom: 2rem;
    }
    .toc > h2 { font-size: 1rem; margin-bottom: 1rem; color: var(--text-dim); }
    .toc-modules { display: grid; gap: 1rem; }
    .toc-module h3 {
      font-size: 0.95rem;
      color: var(--module);
      margin-bottom: 0.5rem;
    }
    .toc-module h3 a { color: var(--module); text-decoration: none; }
    .toc-module h3 a:hover { text-decoration: underline; }
    .toc-module .count { color: var(--text-dim); font-weight: normal; font-size: 0.85rem; }
    .toc-list {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      list-style: none;
    }
    .toc-list a {
      color: var(--type);
      text-decoration: none;
      font-family: monospace;
      font-size: 0.85rem;
    }
    .toc-list a:hover { text-decoration: underline; }

    .module-section {
      margin-bottom: 2rem;
    }
    .module-heading {
      font-size: 1.5rem;
      color: var(--module);
      margin-bottom: 0.5rem;
      padding-bottom: 0.5rem;
      border-bottom: 2px solid var(--module);
    }
    .module-heading .tool-count {
      font-size: 0.9rem;
      font-weight: normal;
      color: var(--text-dim);
    }
    .module-endpoint {
      margin-bottom: 1.5rem;
      color: var(--text-dim);
    }
    .module-endpoint code {
      background: var(--accent);
      padding: 0.25rem 0.5rem;
      border-radius: 4px;
      font-family: monospace;
    }

    .tool-card {
      background: var(--card-bg);
      border-radius: 8px;
      padding: 1.5rem;
      margin-bottom: 1.5rem;
    }
    .tool-header {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-bottom: 0.75rem;
    }
    .tool-header h3 {
      font-family: monospace;
      font-size: 1.25rem;
    }
    .method {
      font-size: 0.7rem;
      font-weight: bold;
      padding: 0.2rem 0.5rem;
      border-radius: 3px;
    }
    .method.get { background: var(--get); color: #000; }
    .method.post { background: var(--post); color: #000; }
    .description {
      color: var(--text-dim);
      margin-bottom: 1rem;
      font-size: 0.95rem;
    }
    .endpoints { margin-bottom: 1rem; }
    .endpoint {
      display: flex;
      align-items: center;
      gap: 1rem;
      margin-bottom: 0.5rem;
    }
    .endpoint code {
      font-family: monospace;
      padding: 0.25rem 0.5rem;
      border-radius: 4px;
      background: var(--accent);
    }
    .endpoint code.get { border-left: 3px solid var(--get); }
    .endpoint code.post { border-left: 3px solid var(--post); }
    .hint { color: var(--text-dim); font-size: 0.85rem; }

    .schema, .try-it {
      margin-top: 1rem;
      padding-top: 1rem;
      border-top: 1px solid var(--accent);
    }
    .schema h4, .try-it h4 {
      font-size: 0.85rem;
      color: var(--text-dim);
      margin-bottom: 0.5rem;
    }
    pre {
      background: var(--bg);
      padding: 1rem;
      border-radius: 4px;
      overflow-x: auto;
      font-size: 0.85rem;
      line-height: 1.5;
    }
    .type { color: var(--type); }
    .prop { color: var(--get); }
    .required { color: var(--required); }
    .enum { color: var(--enum); font-size: 0.85em; }
    .curl { color: var(--text-dim); }

    footer {
      text-align: center;
      margin-top: 2rem;
      padding-top: 1rem;
      border-top: 1px solid var(--accent);
      color: var(--text-dim);
      font-size: 0.85rem;
    }
  </style>
</head>
<body>
  <div class=\"container\">
    <header>
      <h1>" (escape-html title) "</h1>
      <p class=\"subtitle\">REST API Documentation &bull; " module-count " modules, " (count tools) " tools</p>
      <div class=\"quick-links\">
        <a href=\"/api/modules\">Modules (JSON)</a>
        <a href=\"/api/openapi.json\">OpenAPI Spec</a>
        <a href=\"/api/tools\">All Tools (JSON)</a>
        <a href=\"/health\">Health</a>
      </div>
    </header>

    <nav class=\"toc\">
      <h2>Modules &amp; Tools</h2>
      <div class=\"toc-modules\">"
         (str/join "\n"
                   (for [[module-name mod-tools] by-module]
                        (module-toc-html module-name mod-tools)))
         "
      </div>
    </nav>

    <main>"
         (str/join "\n"
                   (for [[module-name mod-tools] by-module]
                        (module-section-html module-name mod-tools)))
         "
    </main>

    <footer>
      <p>Generated from <a href=\"/api/openapi.json\" style=\"color: var(--get)\">OpenAPI specification</a></p>
      <p>bb-mcp-server &bull; REST API</p>
    </footer>
  </div>
</body>
</html>")))
