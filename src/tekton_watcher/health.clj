(ns tekton-watcher.health
  "Health check API for tekton-watcher."
  (:require [clojure.tools.logging :as log]
            [tekton-watcher.http-client :as http-client]
            [compojure.core :refer [GET routes]]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [ring.middleware.json :as middleware.json]
            [clojure.core.async :as async])
  (:import clojure.lang.Keyword))

(defn check-api-server-connection
  "Checks if the Kubernetes API server can be reached."
  [{:api.server/keys [url]}]
  (let [{:http.error/keys [category throwable] :as response}
        (http-client/send-and-await #:http{:url (str url "/apis")})]
    (if (http-client/error? response)
      (do
        (log/error throwable :health-check :api-server-couldnt-be-reached)
        {:component :http-client
         :status    :failed
         :message   (str "API server couldn't be reached. Cause:" (.getMessage throwable))})
      {:component :http-client
       :status    :ok
       :message   "API server is accessible"})))

(def hearthbeats
  "Stores heartbeat messages sent through async channels."
  (atom {}))

(def threshold-interval-in-mins-of-missing-heartbeats
  "Threshold in minutes to consider a heartbeat as missed. In other
  words, if a component passes more than this value without sending
  heartbeat messages, it will be considered unhealthy."
  1)

(defn- threshold-exceeded?
  "Returns true if the timestamp of the last heartbeat sent exceeds the threshold."
  [^Long threshold-interval-in-mins-of-missing-heartbeats ^Long now ^Long timestamp-of-last-heartbeat-in-nsecs]
  (let [delta (- now timestamp-of-last-heartbeat-in-nsecs)]
    (> (* 60 1.0E9)
       threshold-interval-in-mins-of-missing-heartbeats)))

(defn check-heartbeats
  "Checks the heartbeat messages to determine if all components are healthy."
  [hearthbeats ^Long timestamp-in-nsecs]
  (->> hearthbeats
           vals
           (map (fn [{:keys [component timestamp]}]
                  (if (threshold-exceeded? threshold-interval-in-mins-of-missing-heartbeats timestamp-in-nsecs timestamp)
                    {:component component
                     :status    :down
                     :message   (format "Component hasn't sent heartbeat messages for more than %s minute(s)" threshold-interval-in-mins-of-missing-heartbeats)}
                    {:component component
                     :status    :ok
                     :message   "Alive"})))))

(defn alive
  "Sends a heartbeat message through the supplied channel indicating
  that the component in question is healthy."
  [channel ^Keyword component]
  (async/put! channel {:component component
                       :status    :ok
                       :message   "Alive"
                       :timestamp (System/nanoTime)}))

(defn- healthy?
  "True when all checks contain the status :ok."
  [checks]
  (every? #(= :ok (:status %))
          checks))

(defn liveness-check
  "Returns a Ring compatible HTTP response data identifying whether the
  service is healthy.

  Since Kubernetes liveness probe expects a failure status code to
  indicate an unhealthy state, returns 200 when the service can be
  considered healthy and 503 otherwise."
  [config]
  (let [checks (into [(check-api-server-connection config)]
                     (check-heartbeats @hearthbeats (System/nanoTime)))]
    (if (healthy? checks)
      {:status 200
       :body   {:status :healthy
                :checks checks}}
      {:status 503
       :body   {:status  :unhealthy
                :message "Some components are unhealthy"
                :checks  checks}})))

(def not-found
  "Not found response."
  {:status 404
   :body   {:message "Not found"}})

(def app
  (middleware.json/wrap-json-response
   (routes
    (GET "/health/live" [] (liveness-check {:api.server/url "http://localhost:8080"}))
    (route/not-found not-found))))

(defn start
  []
  (server/run-server #'app {:port               9000
                            :worker-name-prefix "tekton-watcher-worker-"
                            :error-logger       (fn [message exception]
                                                  (log/error exception :msg message))
                            :warn-logger        (fn [message exception]
                                                  (log/warn exception :msg message))}))
