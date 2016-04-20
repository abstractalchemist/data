(ns data.sample
  (:refer-clojure)
  (:import [java.net URLEncoder])
  (:require [clj-http.lite.client :as client]
            [clj-json.core :as json]))

(defn encode
  "UTF encode a file path"
  [id]
  (URLEncoder/encode id "UTF-8"))

(def animedb "http://localhost:5984/animedb")

(def sample-data
  [{:id 0 :title "Getting Started" :entry "The first entry in this blog" :img (encode "/home/jmhirata/Pictures/BlogImages/getting-started.jpg") :content "What this section of the blog is all about." :tags  ["start" "information"]}
   {:id 1 :title "The Perfect Insider"  :entry "2016's mystery anime" :img (encode "/home/jmhirata/Pictures/BlogImages/the_perfect_insider.jpg") :content "A mystery show with an excellent plot, intersting animeation, and unique set of characters that you consistently question.", :tags ["anime" "review" "perfect insider"]}
   {:id 2 :title "Tesagure! Bukatsumono" :entry "Animated Japanese Variety Show" :img (encode "/home/jmhirata/Pictures/BlogImages/Tesagure-Bukatsumono.jpg") :content "The best show this season of stupid nothing-ness and brain emptying entertainment." :tags ["anime" "review" "tesaguri"]}
   {:id 3 :title "Gate" :entry "My vote for best show of the season" :img (encode "/home/jmhirata/Pictures/BlogImages/Gate-opens.png") :content "Otaku hero's have never been better than this show." :tags ["anime" "review" "gate"]}])

(def populate-sample-data
  "Populate couchdb with sample data"
  (fn([] (populate-sample-data sample-data))
    ([data]
     (doseq [{:keys [id] :as item} data]
       (println "adding " (json/generate-string item))
       (client/put (str animedb "/" id) {:headers {"content-type" "application/json"}
                                         :body (json/generate-string item)})))))
  
  
