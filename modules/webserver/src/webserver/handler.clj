(ns webserver.handler
    "HTTP request handler for static file serving."
    (:require [babashka.fs :as fs]
              [clojure.string :as str]
              [taoensso.trove :as log]
              [webserver.hiccup :as hiccup]))

;; =============================================================================
;; MIME Types
;; =============================================================================

(def mime-types
     "Common MIME types for static files."
     {"html"  "text/html; charset=utf-8"
      "htm"   "text/html; charset=utf-8"
      "css"   "text/css; charset=utf-8"
      "js"    "application/javascript; charset=utf-8"
      "mjs"   "application/javascript; charset=utf-8"
      "json"  "application/json; charset=utf-8"
      "png"   "image/png"
      "jpg"   "image/jpeg"
      "jpeg"  "image/jpeg"
      "gif"   "image/gif"
      "svg"   "image/svg+xml"
      "ico"   "image/x-icon"
      "webp"  "image/webp"
      "woff"  "font/woff"
      "woff2" "font/woff2"
      "ttf"   "font/ttf"
      "eot"   "application/vnd.ms-fontobject"
      "otf"   "font/otf"
      "txt"   "text/plain; charset=utf-8"
      "md"    "text/markdown; charset=utf-8"
      "xml"   "application/xml"
      "pdf"   "application/pdf"
      "zip"   "application/zip"
      "mp3"   "audio/mpeg"
      "mp4"   "video/mp4"
      "webm"  "video/webm"})

(defn get-mime-type
  "Get MIME type for file extension."
  [path]
  (let [ext (some-> path fs/extension str/lower-case)]
    (get mime-types ext "application/octet-stream")))

;; =============================================================================
;; Live Reload Script
;; =============================================================================

(def reload-script
     "JavaScript snippet for live reload via WebSocket."
     "<script>
(function() {
  var ws = new WebSocket('ws://' + location.host + '/_reload');
  ws.onmessage = function(e) { if (e.data === 'reload') location.reload(); };
  ws.onclose = function() { setTimeout(function() { location.reload(); }, 2000); };
  ws.onerror = function() { ws.close(); };
})();
</script>")

(defn inject-reload-script
  "Inject live reload script before </body> or at end of HTML."
  [html]
  (if (str/includes? html "</body>")
    (str/replace html "</body>" (str reload-script "</body>"))
    (str html reload-script)))

;; =============================================================================
;; File Serving
;; =============================================================================

(defn resolve-path
  "Resolve request path to file path, preventing directory traversal."
  [root path]
  (let [;; Normalize path - remove leading slash, handle empty
        clean-path (-> path
                       (str/replace #"^/+" "")
                       (str/replace #"\.\." "")  ; prevent traversal
                       (str/replace #"/+" "/")) ; collapse slashes
        ;; Default to index.html for empty path
        file-path (if (or (str/blank? clean-path) (= clean-path "/"))
                    "index.html"
                    clean-path)
        ;; Resolve against root
        full-path (fs/path root file-path)]
    ;; Verify path is under root (security)
    (when (str/starts-with? (str (fs/absolutize full-path))
                            (str (fs/absolutize root)))
      full-path)))

(defn serve-file
  "Serve a file with appropriate headers."
  [path reload?]
  (let [content (slurp (str path))
        mime-type (get-mime-type (str path))
        ;; Inject reload script for HTML if reload enabled
        body (if (and reload?
                      (str/starts-with? mime-type "text/html"))
               (inject-reload-script content)
               content)]
    {:status 200
     :headers {"Content-Type" mime-type
               "Cache-Control" "no-cache"}
     :body body}))

(defn serve-file-binary
  "Serve a binary file."
  [path]
  (let [bytes (fs/read-all-bytes path)
        mime-type (get-mime-type (str path))]
    {:status 200
     :headers {"Content-Type" mime-type
               "Cache-Control" "max-age=3600"}
     :body (java.io.ByteArrayInputStream. bytes)}))

(defn text-file?
  "Check if file should be served as text."
  [path]
  (let [ext (some-> path str fs/extension str/lower-case)]
    (contains? #{"html" "htm" "css" "js" "mjs" "json" "txt" "md" "xml" "svg" "hiccup"} ext)))

(defn hiccup-file?
  "Check if file is a Hiccup template."
  [path]
  (let [ext (some-> path str fs/extension str/lower-case)]
    (= ext "hiccup")))

(defn serve-hiccup
  "Render and serve a Hiccup file."
  [path reload?]
  (let [{:keys [html]} (hiccup/render-file path)
        body (if reload?
               (inject-reload-script html)
               html)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"
               "Cache-Control" "no-cache"}
     :body body}))

;; =============================================================================
;; Request Handler
;; =============================================================================

(defn not-found-response
  "404 response."
  []
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body "<html><body><h1>404 Not Found</h1></body></html>"})

(defn health-response
  "Health check response."
  []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"status\":\"ok\"}"})

(defn create-handler
  "Create request handler for given root directory."
  [{:keys [root reload hiccup]}]
  (fn [req]
    (let [uri (:uri req)
          method (:request-method req)]
      (log/log! {:level :debug
                 :id ::request
                 :msg "HTTP request"
                 :data {:method method :uri uri}})
      (cond
        ;; Health check
        (= uri "/_health")
        (health-response)

        ;; Skip WebSocket upgrade requests (handled elsewhere)
        (= uri "/_reload")
        nil

        ;; Serve static files
        (= method :get)
        (if-let [path (resolve-path root uri)]
                (cond
            ;; Directory - try index.html
                  (fs/directory? path)
                  (let [index-path (fs/path path "index.html")]
                    (if (fs/exists? index-path)
                      (serve-file index-path reload)
                      (not-found-response)))

            ;; File exists
                  (fs/exists? path)
                  (cond
              ;; Hiccup file (only if enabled)
                    (and hiccup (hiccup-file? path))
                    (serve-hiccup path reload)

              ;; Text file
                    (text-file? path)
                    (serve-file path reload)

              ;; Binary file
                    :else
                    (serve-file-binary path))

            ;; Not found
                  :else
                  (not-found-response))
                (not-found-response))

        ;; Method not allowed
        :else
        {:status 405
         :headers {"Content-Type" "text/plain"}
         :body "Method Not Allowed"}))))
