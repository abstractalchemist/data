(ns data.handler
  (:import [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdTokenVerifier GoogleIdTokenVerifier$Builder]
           [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [java.util Calendar]
           [io.jsonwebtoken.impl.crypto MacProvider]
           [io.jsonwebtoken SignatureAlgorithm]
           [io.jsonwebtoken Jwts]
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

(def JWT_KEY (MacProvider/generateKey))

(defn expire-in[exp-sec]
  (let [c (doto (Calendar/getInstance)
            (.add Calendar/SECOND exp-sec))]
    (. c getTime)))

(defn jwt-token[{:keys [iss exp] :as claims}]
  (letfn [(keyword-str [v] (. (str v) substring 1))
          (process-claims [jwt c]
            (reduce-kv (fn[i k v] (. i claim (keyword-str k) v)) jwt c))]
    (-> (Jwts/builder)
        (.setIssuer iss)
        (process-claims claims)
        (.setExpiration (expire-in exp))
        (.signWith SignatureAlgorithm/HS512 JWT_KEY)
        (.compact))))

(defn jwt-verify[s]
  (try
    (reduce (fn[i [k v]] (assoc i (keyword k) v)) {}
            (-> (Jwts/parser)
                (.setSigningKey JWT_KEY)
                (.parseClaimsJws s)
                (.getBody)))
    (catch Exception ex)))
      

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
    (fn [token-type idtoken]
      {:pre [(seq idtoken)]}
      (condp = token-type
        "GoogleSignIn"
        (when-let [token (. verifier verify idtoken)]
          (let [payload (. token getPayload)
                claims {:subject (. payload getSubject)
                        :email (. payload getEmail)}]
            (log "Logging in : " (. payload getSubject))
            (assoc claims :token (jwt-token (assoc claims :iss "abstractalchemist@gmail.com" :exp (* 60 60))))))
        "Bearer"
        (jwt-verify idtoken)))))

(defn resolve-binding[val auth-str]
  (condp = val
    :idtoken
    `(if (seq ~auth-str)
       (let [[token-type# idtoken#] (clojure.string/split ~auth-str #"\s")]
         (if (seq idtoken#) (validate-token token-type# idtoken#) {}))
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

(defn m-get-next-id
  "gets the next available id in the couchdb"
  [] 
  (let [{:keys [body]} (client/get (str animedb "/_all_docs"))
        {:strs [total_rows] :as query} (json/parse-string body)]
    (clojure.pprint/pprint query)
    total_rows))

(defroutes app-routes
  (POST "/authorize" {{:strs [authorization]} :headers}
        (try
          (letlogin [login :idtoken] authorization
                    (if login
                      {:status 200
                       :headers {"content-type" "application/json"}
                       :body (json/generate-string login)}
                      {:status 404}))))

  (POST "/register" { {:strs [authorization]} :headers}
        "This is the registration endpoint;  it requires a google JWT token and we'll contact you later")
  (context "/frc" { {:strs [authorization] :as h} :headers body :body}
          (GET "/authorized" []
               (letlogin [login :idtoken] authorization
                         {:status (if (frc? login) 200 401)})))
  (context "/anime" { {:strs [authorization] :as h} :headers body :body}
           (POST "/authorized" [])
           (PUT "/samples" [] (do
                                (data.sample/populate-sample-data)
                                {:status 200}))
           (GET "/images/:id" [id]
                {:status 200
                 :headers { "content-type" "application/octet-stream" }
                 :body (clojure.java.io/input-stream (java.net.URLDecoder/decode id))})
           (GET "/images" []
                (log "Grabbing images for gallery")
                (letlogin [login :idtoken] authorization
                          (if (and login (registered? login))
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body (json/generate-string (map (comp (fn[d] {:id (java.net.URLEncoder/encode d "UTF-8") :path d}) str) (java.nio.file.Files/newDirectoryStream
                                                                                                                                       (java.nio.file.Paths/get "/" (into-array String ["home" "jmhirata" "Pictures"]))
                                                                                                                                       "*.{png,jpg}")))}
                            {:status 401})))
           (GET "/:id" [id]
                (letlogin [login :idtoken
                           admin (admin? login)] authorization
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (let [{:keys [body]} (client/get (str animedb "/" id))]
                                    body)}))
                                  
                                  
           (POST "/:id" {:keys [body] {:keys [id]} :params}
                 (letlogin [login :idtoken
                            admin (admin? login)] authorization
                            (log "posting with id " id " and body " body)
                            (let [{get-body :body} (let [res (client/get (str animedb "/" id))]
                                                     (clojure.pprint/pprint res)
                                                     res)
                                  res (json/parse-string get-body)
                                  [{:strs [entry]}] (json/parsed-seq (clojure.java.io/reader body))]
                              (client/put (str animedb "/" id) {:headers {"content-type" "application/json"}
                                                                :body (json/generate-string (assoc res :entry entry))}))))
           (GET "/" []
                (letlogin [login :idtoken
                           admin (admin? login)] authorization
                           (log "getting anime related info with auth <" login ">;  has admin privileges? " admin)
                           
                           {:status 200
                            :headers {"Content-Type" "application/json"}
                            :body (let [{:keys [body]} (client/get (str animedb "/_all_docs"))
                                        {:strs [rows]} (json/parse-string body)]
                                    (letfn [(retrieve [{:strs [id]}] (let [{:keys [body]} (client/get (str animedb "/" id))]
                                                                       (assoc (json/parse-string body) :editable admin)))]
                                      (json/generate-string
                                       (map retrieve rows))))}))
                                    
           (PUT "/" {:keys [body]}
                (letlogin [login :idtoken
                           admin (admin? login)] authorization

                           (let [next-id (m-get-next-id)
                                 input (slurp (clojure.java.io/reader body))]
                             (log "body: " input)
                             (let [{:keys [status body]} (client/put (str animedb "/" next-id) {:headers {"content-type" "application/json"}
                                                                                                :body input})]
                               (log "result: " status " with body " body)
                               {:status status})))))
  
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
