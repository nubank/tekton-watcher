(ns tekton-watcher.service
  (:require [clojure.tools.logging :as log]
            [tekton-watcher.api :as api]
            [tekton-watcher.config :as config]
            [tekton-watcher.runs :as runs]
            [tekton-watcher.status-checks :as status-checks]))

(def publishers
  [runs/watch-running-tasks
   runs/watch-completed-tasks])

(def subscribers
  [runs/run-started
   runs/run-succeeded
   runs/run-failed
   status-checks/run-started
   status-checks/run-succeeded
   status-checks/run-failed])

(defn -main
  [& _]
  (log/info "Starting tekton-watcher...")
  (api/start-messaging publishers subscribers (config/read-config))
  (log/info "tekton-watcher started")
  (.. Thread currentThread join))
