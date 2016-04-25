(ns data.server
  (:refer-clojure)
  (:require [ring.adapter.jetty]
            [data.handler]))

(defn -main[& args]
  (ring.adapter.jetty/run-jetty
   data.handler/app
   {:port 3000
    :join? true}))
