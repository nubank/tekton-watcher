(ns tekton-watcher.runs
  "Core module responsible for watching pipelineruns and taskruns,
  publishing events based on their status and implementing a simple
  de-duplication strategy to avoid repeating actions."
  (:require [tekton-watcher.api :refer [defpub defsub]]
            [tekton-watcher.http-client :as http-client]))

(defn taskrun-completed?
  "Given a taskrun, returns true if it is completed (all steps have
  terminated) or false otherwise."
  [taskrun]
  (->> taskrun
       :status
       :conditions
       (every? #(not= "Running" (:reason %)))))

(defn pipelinerun-completed?
  "Given a pipelinerun, returns true if it is completed (all taskruns
  therein contained have terminated) or false otherwise."
  [pipelinerun]
  (->> pipelinerun
       :status
       :taskRuns
       vals
       (every? taskrun-completed?)))

(defn- list-runs
  "List pipeline or task runs matching the supplied label selectors and
  predicate."
  [{:tekton.api/keys [url]} resource-kind label-selectors predicate]
  (->> (http-client/send-and-await #:http{:url          "{url}/{resource-kind}"
                                          :path-params  {:url           url
                                                         :resource-kind resource-kind}
                                          :query-params {:labelSelector label-selectors}})
       :items
       (filter predicate)))

(defn get-running-pipelineruns
  "Gets all in-progress pipelineruns that haven't watched yet."
  [config]
  (list-runs config
             "pipelineruns"
             "!tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired"
             (complement pipelinerun-completed?)))

(defn get-completed-pipelineruns
  "Gets all completed pipelineruns (either succeeded or failed ones)
  that haven't marked yet as completed by the watcher."
  [config]
  (list-runs config
             "pipelineruns"
             "!tekton-watcher/completed-event-fired"
             pipelinerun-completed?))

(defn get-running-taskruns
  "Gets all in-progress taskruns that haven't watched yet."
  [config]
  (list-runs config
             "taskruns"
             "!tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired"
             (complement taskrun-completed?)))

(defn get-completed-taskruns
  "Gets all completed taskruns (either succeeded or failed ones)
  that haven't marked yet as completed by the watcher."
  [config]
  (list-runs config
             "taskruns"
             "!tekton-watcher/completed-event-fired"
             taskrun-completed?))

(defn- add-label
  "Patches a run object (either a pipelinerun or a taskrun) by adding
  the supplied label key with the value true."
  [run {:api.server/keys [url]} label-key]
  (http-client/send-async #:http{:verb        :patch
                                 :url         "{url}{link}"
                                 :produces    "application/json-patch+json"
                                 :path-params {:url  url
                                               :link (get-in run [:metadata :selfLink])}
                                 :payload     [{:op    "add"
                                                :path  (str "/metadata/labels/tekton-watcher~1" label-key)
                                                :value "true"}]}))

;; Publishers

(defn message-topic
  "Given a run object (either a pipelinerun or a taskrun), determines
  the topic (e.g. :pipeline-run/succeeded, :task-run/failed) that it
  belongs to."
  [run]
  (let [kind   (get run :kind)
        status (->> run
                    :status
                    :conditions
                    (map :status)
                    first)]
    (if (= status "True")
      (if (= kind "PipelineRun") :pipeline-run/succeeded :task-run/succeeded)
      (if (= kind "PipelineRun") :pipeline-run/failed :task-run/failed))))

(defpub watch-running-pipelineruns
  #{:pipeline-run/running}
  [config]
  (->> config
       get-running-pipelineruns
       (map #(assoc {:message/topic :pipeline-run/running}
                    :message/resource %))))

(defpub watch-running-taskruns
  #{:task-run/running}
  [config]
  (->> config
       get-running-taskruns
       (map #(assoc {:message/topic :task-run/running}
                    :message/resource %))))

(defpub watch-completed-pipelineruns
  #{:pipeline-run/succeeded :pipeline-run/failed}
  [config]
  (->> config
       get-completed-pipelineruns
       (map #(assoc {:message/topic (message-topic %)}
                    :message/resource %))))

(defpub watch-completed-taskruns
  #{:task-run/succeeded :task-run/failed}
  [config]
  (->> config
       get-completed-taskruns
       (map #(assoc {:message/topic (message-topic %)}
                    :message/resource %))))

;; Subscribers

(defsub pipelinerun-started :pipeline-run/running
  [pipeline-run config]
  (add-label pipeline-run config "running-event-fired"))

(defsub taskrun-started :task-run/running
  [task-run config]
  (add-label task-run config "running-event-fired"))

(defsub pipelinerun-succeeded :pipeline-run/succeeded
  [pipeline-run config]
  (add-label pipeline-run config "completed-event-fired"))

(defsub taskrun-succeeded :task-run/succeeded
  [task-run config]
  (add-label task-run config "completed-event-fired"))

(defsub pipelinerun-failed :pipeline-run/failed
  [pipeline-run config]
  (add-label pipeline-run config "completed-event-fired"))

(defsub taskrun-failed :task-run/failed
  [task-run config]
  (add-label task-run config "completed-event-fired"))
