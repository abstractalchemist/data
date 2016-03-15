(ns data.sample
  (:refer-clojure)
  (:require [clj-http.lite.client :as client]
            [clj-json.core :as json]))

(def animedb "http://localhost:5984/animedb")

(def sample-data
  [{:id 0 :title "Getting Started" :entry "" :img "getting-started.jpg"}
   {:id 1 :title "The Perfect Insider"  :entry "" :img "the_perfect_insider.jpg"}
   {:id 2 :title "Tesagure! Bukatsumono" :entry "" :img "Tesagure-Bukatsumono.jpg"}
   {:id 3 :title "Gate" :entry "" :img "Gate-opens.png"}])

(def populate-sample-data
  "Populate couchdb with sample data"
  (fn([] (populate-sample-data sample-data))
    ([data]
     (doseq [{:keys [id] :as item} data]
       (client/put (str animedb "/" id) {:headers {"content-type" "application/json"}
                                         :body (json/generate-string item)})))))
  
  
