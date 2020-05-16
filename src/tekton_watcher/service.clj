(ns tekton-watcher.service
  (:gen-class)
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [tekton-watcher.api :as api]
            [tekton-watcher.config :as config]
            [tekton-watcher.health :as health]
            [tekton-watcher.http-server :as http-server]
            [tekton-watcher.runs :as runs]
            [tekton-watcher.slack-notifications :as slack-notifications]
            [tekton-watcher.status-checks :as status-checks]))

(def publishers
  "Publisher components."
  [runs/watch-running-pipelineruns
   runs/watch-running-taskruns
   runs/watch-completed-pipelineruns
   runs/watch-completed-taskruns])

(def subscribers
  "Subscriber components."
  [runs/pipelinerun-started
   runs/taskrun-started
   runs/pipelinerun-succeeded
   runs/taskrun-succeeded
   runs/pipelinerun-failed
   runs/taskrun-failed
   slack-notifications/pipeline-run-succeeded
   slack-notifications/pipeline-run-failed
   status-checks/taskrun-started
   status-checks/taskrun-succeeded
   status-checks/taskrun-failed])

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

(def uncaught-exception-handler
  "Handler for uncaught exceptions."
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [this  thread exception]
      (log/error  exception :uncaught-exception :on-thread (.getName thread)))))

(defn -main
  [& _]
  (Thread/setDefaultUncaughtExceptionHandler uncaught-exception-handler)
  (log/info "Starting tekton-watcher...")
  (start)
  (.. Thread currentThread join))
