(ns data.handler
  (:import [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdTokenVerifier GoogleIdTokenVerifier$Builder]
           [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [com.google.api.client.json.jackson2 JacksonFactory])
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [clj-json [core :as json]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(defonce CLIENT_ID "281796100165-8fjodck6rd1rp95c28ms79jq2ka2i6jg.apps.googleusercontent.com")

(def log
  (let [a (agent "")]
    (fn[& msgs]
      (send a #(println "******* " (clojure.string/join "" %2) " **********") msgs)
      nil)))

(defn validate-token
  ""
  [idtoken]
  {:pre [(seq idtoken)]}
  (let [verifier (-> (GoogleIdTokenVerifier$Builder. (GoogleNetHttpTransport/newTrustedTransport) (JacksonFactory.))
                           (. setAudience [CLIENT_ID])
                           (. setIssuer "accounts.google.com")
                           (. build))]
    (when-let [token (. verifier verify idtoken)]
      (let [payload (. token getPayload)]
        (log "Logging in : " (. payload getSubject))
        payload))))
  

(defroutes app-routes
  (GET "/" [] "Hello World")
  (POST "/signin" {:keys [body]}
        (let [{:keys [idtoken]} (json/parsed-seq body)]          
          ))
  (POST "/signout" [] {:status 500})
  (route/not-found "Not Found"))

(defn check-signed-in
  ""
  [handler]
  (fn[{{} :cookies :as req}]
    (handler req)))

(def app
  (wrap-defaults app-routes site-defaults))
