(ns webserver.hiccup
    "Hiccup template rendering support."
    (:require [clojure.edn :as edn]
              [hiccup2.core :as h]
              [taoensso.trove :as log]))

(defn render-hiccup
  "Render Hiccup data structure to HTML string.

   Input can be:
   - EDN string containing Hiccup
   - Clojure Hiccup data structure

   Returns HTML string or error map."
  [content]
  (try
   (let [data (if (string? content)
                (edn/read-string content)
                content)
         html (str (h/html data))]
     {:success true
      :html html})
   (catch Exception e
          (log/log! {:level :error
                     :id ::hiccup-error
                     :msg "Hiccup render failed"
                     :data {:error (.getMessage e)}})
          {:success false
           :error (.getMessage e)
           :html (str "<html><body><h1>Hiccup Render Error</h1>"
                      "<pre>" (.getMessage e) "</pre>"
                      "</body></html>")})))

(defn render-file
  "Read and render a .hiccup file."
  [path]
  (try
   (let [content (slurp (str path))]
     (render-hiccup content))
   (catch Exception e
          {:success false
           :error (.getMessage e)
           :html (str "<html><body><h1>File Read Error</h1>"
                      "<pre>" (.getMessage e) "</pre>"
                      "</body></html>")})))
