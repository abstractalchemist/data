(ns data.handler
  (:import [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdTokenVerifier GoogleIdTokenVerifier$Builder]
           [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [com.google.api.client.json.jackson2 JacksonFactory])
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware [cors :refer [wrap-cors]]]
            [clojure.pprint]
            [clj-http.lite.client :as client]
            [clj-json [core :as json]]
            [data.sample]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(defonce CLIENT_ID "281796100165-8fjodck6rd1rp95c28ms79jq2ka2i6jg.apps.googleusercontent.com")

(def log
  (let [a (agent "")]
    (fn[& msgs]
      (send a #(println "******* " (clojure.string/join "" %2) " **********") msgs)
      nil)))

(def validate-token
  ""
  (let [verifier (-> (GoogleIdTokenVerifier$Builder. (GoogleNetHttpTransport/newTrustedTransport) (JacksonFactory.))
                           (. setAudience [CLIENT_ID])
                           (. setIssuer "accounts.google.com")
                           (. build))]
    (fn [idtoken]
      {:pre [(seq idtoken)]}
      (when-let [token (. verifier verify idtoken)]
        (let [payload (. token getPayload)]
          (log "Logging in : " (. payload getSubject))
          {:subject (. payload getSubject)
           :email (. payload getEmail)})))))

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

(defn frc?[{:keys [email]}]
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

(def animedb "http://localhost:5984/animedb")
(def programmingdb "http://localhost:5984/programmingdb")

(defroutes app-routes
  (POST "/register" { {:strs [authorization]} :headers}
        "This is the registration endpoint;  it requires a google JWT token and we'll contact you later")
  (context "/frc" { {:strs [authorization] :as h} :headers body :body}
          (GET "/authorized" []
               (letlogin [login :idtoken] authorization
                         {:status (if (frc? login) 200 401)})))
  (context "/anime" { {:strs [authorization] :as h} :headers body :body}
           (PUT "/samples" [] (do
                                (data.sample/populate-sample-data)
                                {:status 200}))
           (GET "/images/:id" [id]
                "Retriveing image id")
           (GET "/images" []
                (log "Grabbing images for gallery")
                (letlogin [login :idtoken] authorization
                          (if (and login (registered? login))
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body (json/generate-string [{ :id 0 :img "img1.jpg"}
                                                          { :id 0 :img "img2.jpg"}
                                                          { :id 0 :img "img3.jpg"}
                                                          { :id 0 :img "img4.jpg"}
                                                          { :id 0 :img "img5.jpg"}])}
                            {:status 401})))
           (GET "/:id" [id]
                (letlogin [login :idtoken
                           admin (admin? login)] authorization
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (let [{:keys [body]} (client/get (str animedb "/" id))]
                                    body)}))
                                  
                                  
           (POST "/:id" [id]
                 (letlogin [login :idtoken
                            admin (admin? login)] authorization))
           (GET "/" []
                (letlogin [login :idtoken
                           admin (admin? login)] authorization
                           (log "getting anime related info with auth <" login ">")
                           
                           {:status 200
                            :headers {"Content-Type" "application/json"}
                            :body (let [{:keys [body]} (client/get (str animedb "/_all_docs"))
                                        {:strs [rows]} (json/parse-string body)]
                                    (letfn [(retrieve [{:strs [id]}] (let [{:keys [body]} (client/get (str animedb "/" id))]
                                                                       (assoc (json/parse-string body) :editable admin)))]
                                      (json/generate-string
                                       (map retrieve rows))))}))
                                    
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
