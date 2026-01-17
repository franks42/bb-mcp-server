(ns code-browser.uri-test
    "Unit tests for code-browser.uri module."
    (:require [clojure.test :refer [deftest testing is]]
              [code-browser.uri :as uri]))

;;; ---------------------------------------------------------------------------
;;; Parse Tests
;;; ---------------------------------------------------------------------------

(deftest parse-valid-uris-test
         (testing "Parse project-level URIs"
                  (let [result (uri/parse "dir://bb-mcp-server@abc123")]
                    (is (= "dir://bb-mcp-server@abc123" (:uri/string result)))
                    (is (= :dir (:uri/source result)))
                    (is (= "bb-mcp-server" (:uri/project result)))
                    (is (= "abc123" (:uri/version result)))
                    (is (= :static (:uri/version-type result)))
                    (is (nil? (:uri/namespace result)))
                    (is (nil? (:uri/symbol result)))))

         (testing "Parse namespace-level URIs"
                  (let [result (uri/parse "jar://taoensso.trove@1.0.0/taoensso.trove")]
                    (is (= "jar://taoensso.trove@1.0.0/taoensso.trove" (:uri/string result)))
                    (is (= :jar (:uri/source result)))
                    (is (= "taoensso.trove" (:uri/project result)))
                    (is (= "1.0.0" (:uri/version result)))
                    (is (= :static (:uri/version-type result)))
                    (is (= "taoensso.trove" (:uri/namespace result)))
                    (is (nil? (:uri/symbol result)))))

         (testing "Parse symbol-level URIs"
                  (let [result (uri/parse "github://taoensso/trove@v1.2.0/taoensso.trove.core/init!")]
                    (is (= "github://taoensso/trove@v1.2.0/taoensso.trove.core/init!" (:uri/string result)))
                    (is (= :github (:uri/source result)))
                    (is (= "taoensso/trove" (:uri/project result)))
                    (is (= "v1.2.0" (:uri/version result)))
                    (is (= :static (:uri/version-type result)))
                    (is (= "taoensso.trove.core" (:uri/namespace result)))
                    (is (= "init!" (:uri/symbol result)))))

         (testing "Parse nREPL URIs (temporal)"
                  (let [result (uri/parse "nrepl://localhost:7888@01950a3b-1234-7def/user/my-fn")]
                    (is (= :nrepl (:uri/source result)))
                    (is (= "localhost:7888" (:uri/project result)))
                    (is (= "01950a3b-1234-7def" (:uri/version result)))
                    (is (= :temporal (:uri/version-type result)))
                    (is (= "user" (:uri/namespace result)))
                    (is (= "my-fn" (:uri/symbol result))))))

(deftest parse-invalid-uris-test
         (testing "Invalid URIs return nil"
                  (is (nil? (uri/parse "")))
                  (is (nil? (uri/parse "invalid")))
                  (is (nil? (uri/parse "http://example.com")))
                  (is (nil? (uri/parse "dir://project")))  ; missing version
                  (is (nil? (uri/parse "unknown://proj@v1")))))  ; unknown source

(deftest valid?-test
         (testing "valid? returns true for valid URIs"
                  (is (uri/valid? "dir://proj@v1"))
                  (is (uri/valid? "jar://lib@1.0/ns"))
                  (is (uri/valid? "github://org/repo@sha/ns/sym")))

         (testing "valid? returns false for invalid URIs"
                  (is (not (uri/valid? "")))
                  (is (not (uri/valid? "invalid")))
                  (is (not (uri/valid? "http://example.com")))))

;;; ---------------------------------------------------------------------------
;;; Build Tests
;;; ---------------------------------------------------------------------------

(deftest build-test
         (testing "Build project-level URI"
                  (is (= "dir://foo@abc"
                         (uri/build {:source :dir :project "foo" :version "abc"}))))

         (testing "Build namespace-level URI"
                  (is (= "jar://lib@1.0/lib.core"
                         (uri/build {:source :jar :project "lib" :version "1.0"
                                     :namespace "lib.core"}))))

         (testing "Build symbol-level URI"
                  (is (= "github://org/repo@v1/ns/sym"
                         (uri/build {:source :github :project "org/repo" :version "v1"
                                     :namespace "ns" :symbol "sym"})))))

;;; ---------------------------------------------------------------------------
;;; Navigation Helper Tests
;;; ---------------------------------------------------------------------------

(deftest project-uri-test
         (testing "Extract project URI from parsed URI"
                  (let [parsed (uri/parse "dir://proj@v1/ns/sym")]
                    (is (= "dir://proj@v1" (uri/project-uri parsed)))))

         (testing "Build project URI from components"
                  (is (= "jar://lib@2.0"
                         (uri/project-uri {:source :jar :project "lib" :version "2.0"
                                           :namespace "ignored" :symbol "ignored"})))))

(deftest namespace-uri-test
         (testing "Extract namespace URI from parsed URI"
                  (let [parsed (uri/parse "github://org/repo@sha/ns.core/func")]
                    (is (= "github://org/repo@sha/ns.core" (uri/namespace-uri parsed)))))

         (testing "Returns nil when no namespace"
                  (let [parsed (uri/parse "dir://proj@v1")]
                    (is (nil? (uri/namespace-uri parsed))))))

(deftest symbol-uri-test
         (testing "Build symbol URI from components"
                  (is (= "dir://proj@v1/ns/sym"
                         (uri/symbol-uri {:source :dir :project "proj" :version "v1"
                                          :namespace "ns" :symbol "sym"}))))

         (testing "Returns nil when missing namespace or symbol"
                  (is (nil? (uri/symbol-uri {:source :dir :project "proj" :version "v1"})))
                  (is (nil? (uri/symbol-uri {:source :dir :project "proj" :version "v1"
                                             :namespace "ns"})))))

(deftest parent-uri-test
         (testing "Symbol → Namespace"
                  (is (= "dir://proj@v1/ns"
                         (uri/parent-uri "dir://proj@v1/ns/sym"))))

         (testing "Namespace → Project"
                  (is (= "dir://proj@v1"
                         (uri/parent-uri "dir://proj@v1/ns"))))

         (testing "Project → nil"
                  (is (nil? (uri/parent-uri "dir://proj@v1"))))

         (testing "Invalid URI → nil"
                  (is (nil? (uri/parent-uri "invalid")))))

;;; ---------------------------------------------------------------------------
;;; Comparison Tests
;;; ---------------------------------------------------------------------------

(deftest same-project?-test
         (testing "Same project different versions"
                  (is (uri/same-project? "dir://proj@v1" "dir://proj@v2")))

         (testing "Different projects"
                  (is (not (uri/same-project? "dir://proj1@v1" "dir://proj2@v1"))))

         (testing "Different sources"
                  (is (not (uri/same-project? "dir://proj@v1" "jar://proj@v1")))))

(deftest same-namespace?-test
         (testing "Same namespace different symbols"
                  (is (uri/same-namespace? "dir://proj@v1/ns/sym1" "dir://proj@v1/ns/sym2")))

         (testing "Different namespaces"
                  (is (not (uri/same-namespace? "dir://proj@v1/ns1/sym" "dir://proj@v1/ns2/sym")))))

;;; ---------------------------------------------------------------------------
;;; Source Type Tests
;;; ---------------------------------------------------------------------------

(deftest static-source?-test
         (is (uri/static-source? :dir))
         (is (uri/static-source? :jar))
         (is (uri/static-source? :github))
         (is (not (uri/static-source? :nrepl))))

(deftest temporal-source?-test
         (is (uri/temporal-source? :nrepl))
         (is (not (uri/temporal-source? :dir)))
         (is (not (uri/temporal-source? :jar)))
         (is (not (uri/temporal-source? :github))))
