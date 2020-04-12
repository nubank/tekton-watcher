(ns tekton-watcher.service
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [tekton-watcher.api :as api]
            [tekton-watcher.config :as config]
            [tekton-watcher.health :as health]
            [tekton-watcher.http-server :as http-server]
            [tekton-watcher.pulls :as pulls]
            [tekton-watcher.runs :as runs]))

(def publishers
  "Publisher components."
  [runs/watch-running-tasks
   runs/watch-completed-tasks])

(def subscribers
  "Subscriber components."
  [runs/run-started
   runs/run-succeeded
   runs/run-failed
   pulls/run-started
   pulls/run-succeeded
   pulls/run-failed])

(defn start
  "Starts the tekton-watcher service.

  Once started, tekton-watcher watches pipeline and task runs,
  dispatches events according to the status of those resources and
  performs actions based on the triggered events. A simple HTTP server
  starts to listen on the port configured through the
  key :tekton-watcher.server/port in the config map. This HTTP server
  exposes some endpoints that reveal information about the internal
  status of the service. For instance, the route /health/live returns
  detailed information about the health status of the service.

  Returns a 0-arity function that stops the service when
  invoked. Useful for iterating on the REPL."
  []
  (let [config-data (config/read-config)
        channels    #:channel {:liveness (async/chan)}
        _           (api/start-messaging publishers subscribers channels config-data)
        _           (health/start-to-observe-heartbeats (:channel/liveness channels))
        stop-server (http-server/start-server config-data)]
    (fn []
      (api/stop-messaging publishers subscribers)
      (stop-server)
      (run! async/close! (vals channels)))))

(defn -main
  [& _]
  (log/info "Starting tekton-watcher...")
  (start)
  (.. Thread currentThread join))
