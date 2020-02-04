(ns tekton-watcher.service
  (:require [clojure.tools.logging :as log]
            [tekton-watcher.api :as api]
            [tekton-watcher.config :as config]
            [tekton-watcher.pulls :as pulls]
            [tekton-watcher.runs :as runs]))

(def publishers
  [runs/watch-running-tasks
   runs/watch-completed-tasks])

(def subscribers
  [runs/run-started
   runs/run-succeeded
   pulls/run-started
   pulls/run-succeeded])

(defn -main
  [& _]
  (log/info "Starting tekton-watcher...")
  (api/start-messaging publishers subscribers (config/read-config))
  (log/info "tekton-watcher started")
  (.. Thread currentThread join))
