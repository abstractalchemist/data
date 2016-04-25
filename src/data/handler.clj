(ns data.handler
  (:import [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdTokenVerifier GoogleIdTokenVerifier$Builder]
           [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [java.util Calendar]
           [io.jsonwebtoken.impl.crypto MacProvider]
           [io.jsonwebtoken SignatureAlgorithm]
           [io.jsonwebtoken Jwts]
           [org.jsoup Jsoup]
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


(def animedb "http://localhost:5984/animedb")
(def programmingdb "http://localhost:5984/programmingdb")
(def scheduledb "http://localhost:5984/scheduledb")
(def authorizationdb "http://localhost:5984/authorizationdb")

(def config
  (let [c (try
            (let [c (slurp "/usr/local/etc/app_cfg.json")
                  json-config (json/parse-string c)]
              json-config)
            (catch Throwable t
              {"img_loc" "/home/jmhirata/Pictures"
               "img_sched" "/home/jmhirata/Pictures/2014 - 9.jpg"}))]
    (fn[] c)))
      
(defn image-sched[]
  (let [{:strs [img_sched]} (config)]
    img_sched))

(defn image-location[]
  (let [{:strs [img_loc]} (config)]
    img_loc))

(defmulti number? class)

(defmethod number? String [s] (try (Integer/parseInt s) (catch Throwable t)))
(defmethod number? Number [s] true)
(defmethod number? nil [s] false)

(defn account [{:keys [email]}]
  (let [{:keys [body]} (client/get (str authorizationdb "/_all_docs"))
        {:strs [rows]} (json/parse-string body)
        results (filter (fn[ {:strs [id]}] (number? id)) rows)]
    (filter (fn[{e "email"}] (= e email)) results)))

(defn registered?[r]
  (account r))


(defn admin?[r]
  (account r))

(defn frc?[r]
  (account r))

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

(defn m-get-next-id
  "gets the next available id in the couchdb"
  [] 
  (let [{:keys [body]} (client/get (str animedb "/_all_docs"))
        {:strs [total_rows] :as query} (json/parse-string body)]
    (clojure.pprint/pprint query)
    total_rows))

(defn m-get-next-schedule-id
  "gets the next schedule id"
  []
  (let [{:keys [body]} (client/get (str scheduledb "/_all_docs"))
        {:strs [total_rows] :as query} (json/parse-string body)]
    total_rows))

(def get-images
  (if (= (System/getProperty "production") "1")
    (fn[]
      (map (comp  (fn[d] {:id (. (java.net.URLEncoder/encode (. d replace (str (image-location) "/") "") "UTF-8") replace "+" "%20")  :path d}) str)
           (java.nio.file.Files/newDirectoryStream (java.nio.file.Paths/get "" (into-array String [(image-location)])) "*.{png,jpg}")))
    (fn[]
      (map (comp  (fn[d] {:id (. (java.net.URLEncoder/encode d "UTF-8") replace "+" "%20")  :path d}) str)
           (java.nio.file.Files/newDirectoryStream (java.nio.file.Paths/get "" (into-array String [(image-location)])) "*.{png,jpg}")))))
  
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
           (context "/schedule/:id" []
                    (GET "/" [])
                    (DELETE "/" []))
           
           (GET "/schedule" []
                (log "checking watch schedule")
                {:status 200
                 :headers {"content-type" "application/json"}
                 :body (json/generate-string {:img (. (java.net.URLEncoder/encode (image-sched) "UTF-8") replace "+" "%20")
                                              :schedule (let [{:keys [status body]} (client/get (str scheduledb "/_all_docs"))
                                                              {input "rows"} (json/parse-string body)]
                                                          (map (fn[{:strs [id]}] (let [{:keys [body]} (client/get (str scheduledb "/" id))
                                                                                       {:strs [title link]} (json/parse-string body)]
                                                                                   {:title title :link link})) input))})})
                                                          
           (PUT "/schedule" {:keys [body]}
                (log "adding new item to schedule")
                (let [next-id (m-get-next-schedule-id)
                      input (slurp body)]
                  (client/put (str scheduledb "/" next-id)
                              {:headers {"content-type" "application/json"}
                               :body input})))
                                      
                
           (GET "/authorized" []
                (log "checking authorization")
                (letlogin [login :idtoken
                           admin (admin? login)] authorization
                           {:status (if admin 200 404)}))
           (PUT "/samples" [] (do
                                (data.sample/populate-sample-data)
                                {:status 200}))
           (context "/images" []
                    (GET "/authorized" []
                         (letlogin [login :idtoken] authorization
                                   (if (and login (registered? login))
                                     {:status 200
                                      :body "authorized"}
                                     {:status 403
                                      :body "unauthorized"})))
                    (GET "/:id" {{token "token"} :query-params {id :id} :params}
                                   
                         (log "retriving " (java.net.URLDecoder/decode id))
                         {:status 200
                          :headers { "content-type" "application/octet-stream" }
                          :body (clojure.java.io/input-stream (java.net.URLDecoder/decode id))})
                          
                    
                    (GET "/" []
                         (log "Grabbing images for gallery")
                         (letlogin [login :idtoken] authorization
                                   (if (and login (registered? login))
                                     (try
                                       {:status 200
                                        :headers {"Content-Type" "application/json"}
                                      :body (json/generate-string (get-images))}
                                       (catch Throwable t
                                         {:status 500
                                          :body "Error getting images"}))
                                     {:status 403
                                      :body "Cannot access image gallery"}))))
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
                                                     ;;(clojure.pprint/pprint res)
                                                     res)
                                  {:strs [_rev] :as res} (json/parse-string get-body)
                                  [{:strs [entry tags links]}] (json/parsed-seq (clojure.java.io/reader body))]
                              (client/put (str animedb "/" id "?rev=" _rev) {:headers {"content-type" "application/json"}
                                                                             :body (json/generate-string (-> res
                                                                                                             (assoc "tags" tags)
                                                                                                             (assoc "entry" entry)
                                                                                                             (assoc "links" (if links
                                                                                                                              (map (fn[l] {:title (let [content (. (Jsoup/connect l) get)]
                                                                                                                                                    (. content title))
                                                                                                                                           :link l})
                                                                                                                                   links)
                                                                                                                              []))))}))))
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
                                       (map retrieve (filter (fn[{:strs [id]}] (try (Integer/parseInt id) (catch Throwable t))) rows)))))}))
                                    
           (PUT "/" {:keys [body]}
                (letlogin [login :idtoken
                           admin (admin? login)] authorization
                           (if admin
                             (let [next-id (m-get-next-id)
                                   {:strs [links] :as input} (json/parse-string (slurp (clojure.java.io/reader body)))]
                               (log "body: " input)
                               (let [{:keys [status body]} (client/put
                                                            (str animedb "/" next-id)
                                                            {:headers {"content-type" "application/json"}
                                                             :body (json/generate-string
                                                                    (-> input
                                                                        (assoc "id" next-id)
                                                                        (assoc "links" (if links
                                                                                        (map (fn[l] {:title (let [content (. (Jsoup/connect l) get)]
                                                                                                              (. content title))
                                                                                                     :link l})
                                                                                             links)
                                                                                        []))))})]
                                 ;;(log "result: " status " with body " body)
                                 {:status status}))
                             {:status 400}))))
  
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
