(ns tekton-watcher.health
  "Health check API for tekton-watcher."
  (:require [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.tools.logging :as log]
            [tekton-watcher.http-client :as http-client])
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
         :message   (str "API server couldn't be reached. Cause: " (.getMessage throwable))})
      {:component :http-client
       :status    :ok
       :message   "API server is accessible"})))

(def heartbeats
  "Stores heartbeat messages sent through async channels."
  (atom {}))

(def threshold-interval-in-mins-of-missing-heartbeats
  "Threshold in minutes to consider a heartbeat as missed. In other
  words, if a component passes more than this value without sending
  heartbeat messages, it will be considered unhealthy."
  1)

(defn threshold-exceeded?
  "Returns true if the timestamp of the last heartbeat sent exceeds the threshold."
  [^Long threshold-interval-in-mins-of-missing-heartbeats ^Long now-in-ns ^Long timestamp-of-last-heartbeat-in-ns]
  (let [delta (- now-in-ns timestamp-of-last-heartbeat-in-ns)]
    (> delta
       (* threshold-interval-in-mins-of-missing-heartbeats 60 1E9))))

(defn check-heartbeats
  "Checks the heartbeat messages to determine if all components are healthy."
  [hearthbeats ^Long now]
  (->> hearthbeats
       vals
       (map (fn [{:keys [component timestamp]}]
              (if (threshold-exceeded? threshold-interval-in-mins-of-missing-heartbeats now timestamp)
                {:component component
                 :status    :down
                 :message   (format "Component hasn't sent heartbeat messages for more than %d minute(s)" threshold-interval-in-mins-of-missing-heartbeats)}
                {:component component
                 :status    :ok
                 :message   "Alive"})))))

(defn ^Long timestamp
  "Timestamp in nanoseconds representing the current instant."
  []
  (System/nanoTime))

(defn alive
  "Sends a heartbeat message through the supplied channel indicating
  that the component in question is healthy."
  [channel ^Keyword component]
  (async/put! channel {:component component
                       :timestamp (timestamp)}))

(defn start-to-observe-heartbeats
  "Starts a go block that observes incoming heartbeat messages sent
  through the supplied channel and stores them in the heartbeats
  atom."
  [liveness-channel]
  (go-loop []
    (when-let [{:keys [component] :as heartbeat} (<! liveness-channel)]
      (swap! heartbeats assoc component heartbeat))
    (when-not (async.impl/closed? liveness-channel)
      (recur))))

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
                     (check-heartbeats @heartbeats (System/nanoTime)))]
    (if (healthy? checks)
      {:status 200
       :body   {:status :healthy
                :checks checks}}
      {:status 503
       :body   {:status  :unhealthy
                :message "Some components are unhealthy"
                :checks  checks}})))
