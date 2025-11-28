(ns webserver.core-test
    "Unit tests for webserver module."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [clojure.string :as str]
              [webserver.core :as ws]
              [webserver.handler :as handler]
              [webserver.hiccup :as hiccup]
              [babashka.fs :as fs]
              [babashka.http-client :as http]))

(def ^:private test-port 19876)

(def ^:private fixtures-path
     (str (fs/path (fs/parent (fs/parent *file*)) "fixtures")))

(defn- cleanup-servers
  "Test fixture to clean up servers before and after each test."
  [f]
  (ws/stop-all!)
  (f)
  (ws/stop-all!))

(use-fixtures :each cleanup-servers)

;; =============================================================================
;; Handler Tests
;; =============================================================================

(deftest test-mime-types
         (testing "Common MIME types resolved correctly"
                  (is (= "text/html; charset=utf-8" (handler/get-mime-type "page.html")))
                  (is (= "text/css; charset=utf-8" (handler/get-mime-type "style.css")))
                  (is (= "application/javascript; charset=utf-8" (handler/get-mime-type "app.js")))
                  (is (= "application/json; charset=utf-8" (handler/get-mime-type "data.json")))
                  (is (= "image/png" (handler/get-mime-type "image.png")))
                  (is (= "image/svg+xml" (handler/get-mime-type "icon.svg")))))

(deftest test-unknown-mime-type
         (testing "Unknown extension returns octet-stream"
                  (is (= "application/octet-stream" (handler/get-mime-type "file.xyz")))))

(deftest test-resolve-path
         (testing "Path resolution prevents directory traversal"
                  (let [root "/var/www"]
                    (is (some? (handler/resolve-path root "/index.html")))
                    (is (some? (handler/resolve-path root "/")))
                    (is (some? (handler/resolve-path root "")))
      ;; Traversal attempts should be blocked
                    (is (nil? (handler/resolve-path root "/../etc/passwd"))))))

(deftest test-text-file-detection
         (testing "Text files detected correctly"
                  (is (handler/text-file? "page.html"))
                  (is (handler/text-file? "style.css"))
                  (is (handler/text-file? "app.js"))
                  (is (handler/text-file? "data.json"))
                  (is (not (handler/text-file? "image.png")))
                  (is (not (handler/text-file? "font.woff2")))))

(deftest test-reload-script-injection
         (testing "Reload script injected before </body>"
                  (let [html "<html><body><p>test</p></body></html>"
                        result (handler/inject-reload-script html)]
                    (is (str/includes? result "WebSocket"))
                    (is (str/includes? result "_reload"))
                    (is (str/includes? result "</body>")))))

;; =============================================================================
;; Hiccup Tests
;; =============================================================================

(deftest test-hiccup-render
         (testing "Hiccup rendering works"
                  (let [result (hiccup/render-hiccup [:html [:body [:h1 "Hello"]]])]
                    (is (:success result))
                    (is (str/includes? (:html result) "<h1>Hello</h1>")))))

(deftest test-hiccup-render-from-string
         (testing "Hiccup rendering from EDN string"
                  (let [result (hiccup/render-hiccup "[:p \"Test paragraph\"]")]
                    (is (:success result))
                    (is (str/includes? (:html result) "<p>Test paragraph</p>")))))

(deftest test-hiccup-error-handling
         (testing "Invalid Hiccup returns error"
                  (let [result (hiccup/render-hiccup "[invalid ::syntax")]
                    (is (not (:success result)))
                    (is (some? (:error result))))))

;; =============================================================================
;; Server Lifecycle Tests
;; =============================================================================

(deftest test-start-stop-server
         (testing "Server starts and stops"
                  (let [info (ws/start! {:port test-port
                                         :root fixtures-path
                                         :open-browser false})]
                    (is (= test-port (:port info)))
                    (is (= (str (fs/absolutize fixtures-path)) (:root info)))

      ;; Server should be listed
                    (is (= 1 (count (ws/list-servers))))

      ;; Stop server
                    (ws/stop! test-port)
                    (is (= 0 (count (ws/list-servers)))))))

(deftest test-server-by-name
         (testing "Server can be stopped by name"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :name "test-server"
                              :open-browser false})

                  (let [servers (ws/list-servers)]
                    (is (= "test-server" (:name (first servers)))))

                  (ws/stop! "test-server")
                  (is (= 0 (count (ws/list-servers))))))

(deftest test-multiple-servers
         (testing "Multiple servers on different ports"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :name "server-1"
                              :open-browser false})
                  (ws/start! {:port (inc test-port)
                              :root fixtures-path
                              :name "server-2"
                              :open-browser false})

                  (is (= 2 (count (ws/list-servers))))

                  (ws/stop! "server-1")
                  (is (= 1 (count (ws/list-servers))))

                  (ws/stop-all!)
                  (is (= 0 (count (ws/list-servers))))))

(deftest test-duplicate-port-error
         (testing "Starting on same port throws error"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :open-browser false})
                  (is (thrown? clojure.lang.ExceptionInfo
                               (ws/start! {:port test-port
                                           :root fixtures-path
                                           :open-browser false})))))

(deftest test-invalid-root-error
         (testing "Non-existent root throws error"
                  (is (thrown? clojure.lang.ExceptionInfo
                               (ws/start! {:port test-port
                                           :root "/nonexistent/path"
                                           :open-browser false})))))

;; =============================================================================
;; HTTP Request Tests
;; =============================================================================

(deftest ^:integration test-serve-index
         (testing "Serves index.html"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :open-browser false})

                  (let [resp (http/get (str "http://localhost:" test-port "/"))]
                    (is (= 200 (:status resp)))
                    (is (str/includes? (:body resp) "Test Page"))
                    (is (str/includes? (:body resp) "Hello from Webserver")))))

(deftest ^:integration test-serve-css
         (testing "Serves CSS with correct MIME type"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :open-browser false})

                  (let [resp (http/get (str "http://localhost:" test-port "/css/style.css"))]
                    (is (= 200 (:status resp)))
                    (is (str/includes?
                         (get-in resp [:headers "content-type"])
                         "text/css"))
                    (is (str/includes? (:body resp) "background-color")))))

(deftest ^:integration test-serve-js
         (testing "Serves JavaScript with correct MIME type"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :open-browser false})

                  (let [resp (http/get (str "http://localhost:" test-port "/js/app.js"))]
                    (is (= 200 (:status resp)))
                    (is (str/includes?
                         (get-in resp [:headers "content-type"])
                         "javascript"))
                    (is (str/includes? (:body resp) "console.log")))))

(deftest ^:integration test-404
         (testing "Returns 404 for missing files"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :open-browser false})

                  (let [resp (http/get (str "http://localhost:" test-port "/nonexistent.html")
                                       {:throw false})]
                    (is (= 404 (:status resp))))))

(deftest ^:integration test-health-endpoint
         (testing "Health endpoint works"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :open-browser false})

                  (let [resp (http/get (str "http://localhost:" test-port "/_health"))]
                    (is (= 200 (:status resp)))
                    (is (str/includes? (:body resp) "ok")))))

(deftest ^:integration test-serve-hiccup
         (testing "Serves Hiccup files as HTML when enabled"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :hiccup true
                              :open-browser false})

                  (let [resp (http/get (str "http://localhost:" test-port "/page.hiccup"))]
                    (is (= 200 (:status resp)))
                    (is (str/includes?
                         (get-in resp [:headers "content-type"])
                         "text/html"))
                    (is (str/includes? (:body resp) "Rendered from Hiccup")))))

(deftest ^:integration test-reload-script-injected
         (testing "Reload script injected when enabled"
                  (ws/start! {:port test-port
                              :root fixtures-path
                              :reload true
                              :open-browser false})

                  (let [resp (http/get (str "http://localhost:" test-port "/"))]
                    (is (str/includes? (:body resp) "WebSocket"))
                    (is (str/includes? (:body resp) "_reload")))))
