(ns tekton-watcher.http-server
  "Lightweight HTTP server to expose health checks and observability
  features."
  (:require [clojure.tools.logging :as log]
            [compojure.core :refer [GET routes]]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [ring.middleware.json :as middleware.json]
            [tekton-watcher.health :as health]))

(def not-found
  "Not found response."
  {:status 404
   :body   {:message "Not found"}})

(defn- app-routes
  "Creates the application's routes.

  config is a map returned by tekton-watcher.config/read-config."
  [config]
  (middleware.json/wrap-json-response
   (routes
    (GET "/health/live" [] (health/liveness-check config))
    (route/not-found not-found))))

(defn start-server
  "Starts the HTTP server.

  config is a map returned by tekton-watcher.config/read-config."
  [config]
  (let [routes (app-routes config)]
    (server/run-server routes {:port               (get config :tekton-watcher.server/port)
                               :worker-name-prefix "tekton-watcher-worker-"
                               :error-logger       (fn [message exception]
                                                     (log/error exception :msg message))
                               :warn-logger        (fn [message exception]
                                                     (log/warn exception :msg message))})))
