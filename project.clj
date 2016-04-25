(defproject data "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.4.0"]
                 [org.jsoup/jsoup "1.9.1"]
                 [ring-cors "0.1.7"]
                 [clj-http-lite "0.3.0"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [clj-json "0.5.3"]
                 [io.jsonwebtoken/jjwt "0.6.0"]
                 [com.google.api-client/google-api-client "1.19.1"]]
  :main data.server
  :plugins [[lein-ring "0.9.7"]]
  :ring {:handler data.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.0"]]}})
