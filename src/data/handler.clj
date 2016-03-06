(ns data.handler
  (:import [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdTokenVerifier GoogleIdTokenVerifier$Builder]
           [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [com.google.api.client.json.jackson2 JacksonFactory])
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware [cors :refer [wrap-cors]]]
            [clojure.pprint]
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
        {:subject (. payload getSubject)
         :email (. payload getEmail)}))))

(defn resolve-binding[val auth-str]
  (condp = val
    :idtoken
    `(if (seq ~auth-str)
       (let [[~'_ idtoken#] (clojure.string/split ~auth-str #"\s")]
         (if (seq idtoken#) (validate-token idtoken#) {}))
       {})
    val))
         
(defn registered?[{:keys [email]}]
  (#{"abstractalchemist@gmail.com"} email))
                 

(defn admin?[{:keys [email]}]
  (#{"abstractalchemist@gmail.com"} email))
             

(defn m-login[[binding val & other] auth-str body]
  (if (seq other)
    `((fn[~binding]
        ~(m-login other auth-str body))
      ~(resolve-binding val auth-str))
    `((fn[~binding]
        ~@body)
      ~(resolve-binding val auth-str))))

(defmacro letlogin[bindings auth-str & body]
  (m-login bindings auth-str body))

(defroutes app-routes
  (POST "/register" { {:strs [authorization]} :headers}
        "This is the registration endpoint;  it requires a google JWT token and we'll contact you later")
  (context "/anime" { {:strs [authorization] :as h} :headers body :body}
           (GET "/images" []
                (log "Grabbing images for gallery")
                (letlogin [login :idtoken] authorization
                          (if (and login (registered? login))
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body (json/generate-string [{ :img "img1.jpg"}
                                                          { :img "img2.jpg"}
                                                          { :img "img3.jpg"}
                                                          { :img "img4.jpg"}
                                                          { :img "img5.jpg"}])}
                            {:status 401})))
                             
           (GET "/" []
         ;;       (let [[_ idtoken] (when (seq authorization) (clojure.string/split authorization #"\s"))
                ;;             login (if (seq idtoken) (validate-token idtoken) {})]
                (letlogin [login :idtoken
                           admin (admin? login)] authorization
                          (log "getting anime related info with auth <" login ">")
                          
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (json/generate-string [{:title "Getting Started" :entry "" :img "getting-started.jpg" :editable  admin }
                                                        {:title "The Perfect Insider"  :entry "" :img "the_perfect_insider.jpg"  :editable  admin}
                                                        {:title "Tesagure! Bukatsumono" :entry "" :img "Tesagure-Bukatsumono.jpg"  :editable  admin}
                                                        {:title "Gate" :entry "" :img "Gate-opens.png"  :editable  admin}])}))
           (POST "/" {})
           (PUT "/" {}))
  (context "/programming" { {:keys [Authorization]} :headers}
           (GET "/" []))
  (context "/frc" { {:keys [Authorization]} :headers}
           (GET "/" []))
  (GET "/" [] "Hello World")
  (route/not-found "Not Found"))

(def app
  (wrap-cors 
   (wrap-defaults app-routes (assoc-in site-defaults [:security :anti-forgery] false))
   :access-control-allow-origin [#"http://localhost:8000"]
   :access-control-allow-methods [:get :put :post :delete]))
