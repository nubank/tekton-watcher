(ns tekton-watcher.health-check
  (:require [clojure.tools.logging :as log]
            [tekton-watcher.http-client :as http-client]
            [compojure.core :refer [GET routes]]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [ring.middleware.json :as middleware.json]))

(defn- liveness-check*
  [{:api.server/keys [url]}]
  (let [{:keys [category throwable] :as response}
        (http-client/send-and-await #:http{:url (str url "/apis")})]
    (if (http-client/error? response)
      {:status "unhealthy"
       :checks [{:component "http-client"
                 :status    "failed"
                 :message   "Canot access the Kubernetes API server"
                 :details   (.getMessage throwable)}]}
      {:status "healthy"
       :checks [{:component "http-client"
                 :status    "ok"}]})))

(defn liveness-check
  [config]
  (fn [_]
    (let [{:keys [status] :as result} (liveness-check* config)]
      {:status (if (= status "healthy")
                 200
                 500)
       :body   result})))

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
