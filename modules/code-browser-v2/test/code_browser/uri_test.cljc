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
;;; Special Character Tests
;;; ---------------------------------------------------------------------------

(deftest parse-project-with-spaces-test
         (testing "Project names with spaces parse correctly"
                  (let [result (uri/parse "dir://My Project@abc123")]
                    (is (= "My Project" (:uri/project result)))
                    (is (= "abc123" (:uri/version result)))))

         (testing "Round-trip: build then parse preserves spaces"
                  (let [uri (uri/build {:source :dir :project "My Project" :version "v1"
                                        :namespace "my.ns" :symbol "my-fn"})
                        parsed (uri/parse uri)]
                    (is (= "My Project" (:uri/project parsed)))
                    (is (= "my.ns" (:uri/namespace parsed)))
                    (is (= "my-fn" (:uri/symbol parsed))))))

(deftest parse-project-with-special-chars-test
         (testing "Project names with hyphens and dots"
                  (is (= "my-project.v2"
                         (:uri/project (uri/parse "dir://my-project.v2@abc")))))
         (testing "Project names with underscores"
                  (is (= "my_project"
                         (:uri/project (uri/parse "dir://my_project@abc"))))))

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

;;; ---------------------------------------------------------------------------
;;; Query Parameter Tests
;;; ---------------------------------------------------------------------------

(deftest parse-query-params-test
         (testing "Parse URI with single query param"
                  (let [result (uri/parse "dir://proj@v1?view=source")]
                    (is (= "dir://proj@v1?view=source" (:uri/string result)))
                    (is (= :dir (:uri/source result)))
                    (is (= "proj" (:uri/project result)))
                    (is (= "v1" (:uri/version result)))
                    (is (nil? (:uri/namespace result)))
                    (is (nil? (:uri/symbol result)))
                    (is (= {"view" "source"} (:uri/query result)))))

         (testing "Parse URI with multiple query params"
                  (let [result (uri/parse "dir://proj@v1/ns/sym?view=source&line=42")]
                    (is (= :dir (:uri/source result)))
                    (is (= "ns" (:uri/namespace result)))
                    (is (= "sym" (:uri/symbol result)))
                    (is (= {"view" "source" "line" "42"} (:uri/query result)))))

         (testing "Parse URI without query params has nil query"
                  (let [result (uri/parse "dir://proj@v1/ns")]
                    (is (nil? (:uri/query result)))))

         (testing "Parse namespace-level URI with query"
                  (let [result (uri/parse "dir://proj@v1/some.ns?view=aliases")]
                    (is (= "some.ns" (:uri/namespace result)))
                    (is (nil? (:uri/symbol result)))
                    (is (= {"view" "aliases"} (:uri/query result)))))

         (testing "valid? accepts URIs with query params"
                  (is (uri/valid? "dir://proj@v1?view=ns-list"))
                  (is (uri/valid? "jar://lib@1.0/ns?view=symbol-list"))
                  (is (uri/valid? "github://org/repo@sha/ns/sym?view=source&line=42"))))

(deftest build-with-query-test
         (testing "Build with query map"
                  (is (= "dir://foo@abc?view=ns-list"
                         (uri/build {:source :dir :project "foo" :version "abc"
                                     :query {"view" "ns-list"}}))))

         (testing "Build with multiple query params (sorted by key)"
                  (is (= "dir://foo@abc/ns/sym?line=42&view=source"
                         (uri/build {:source :dir :project "foo" :version "abc"
                                     :namespace "ns" :symbol "sym"
                                     :query {"view" "source" "line" "42"}}))))

         (testing "Build without query produces no question mark"
                  (is (= "dir://foo@abc"
                         (uri/build {:source :dir :project "foo" :version "abc"})))))

(deftest base-uri-test
         (testing "Strip query params from URI"
                  (is (= "dir://proj@v1"
                         (uri/base-uri "dir://proj@v1?view=ns-list"))))

         (testing "Strip query from symbol-level URI"
                  (is (= "dir://proj@v1/ns/sym"
                         (uri/base-uri "dir://proj@v1/ns/sym?view=source&line=42"))))

         (testing "No-op when URI has no query"
                  (is (= "dir://proj@v1/ns"
                         (uri/base-uri "dir://proj@v1/ns"))))

         (testing "Returns nil for invalid URI"
                  (is (nil? (uri/base-uri "invalid")))))

(deftest with-query-test
         (testing "Add query params to bare URI"
                  (is (= "dir://proj@v1?view=ns-list"
                         (uri/with-query "dir://proj@v1" {"view" "ns-list"}))))

         (testing "Add query params to namespace URI"
                  (is (= "dir://proj@v1/ns?view=aliases"
                         (uri/with-query "dir://proj@v1/ns" {"view" "aliases"}))))

         (testing "Merge query params onto existing"
                  (is (= "dir://proj@v1?line=42&view=source"
                         (uri/with-query "dir://proj@v1?view=source" {"line" "42"}))))

         (testing "Override existing query param"
                  (is (= "dir://proj@v1?view=doc"
                         (uri/with-query "dir://proj@v1?view=source" {"view" "doc"}))))

         (testing "Returns nil for invalid URI"
                  (is (nil? (uri/with-query "invalid" {"view" "source"})))))
