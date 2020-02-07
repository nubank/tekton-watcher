(ns tekton-watcher.runs
  (:require [tekton-watcher.api :refer [defpub defsub]]
            [tekton-watcher.http-client :as http-client]))

(defn- get-topic
  [run]
  (let [reason (->> run
                    :status
                    :conditions
                    (map :reason)
                    first)]
    (case reason
      "Succeeded" :task-run/succeeded
      "Failed" :task-run/failed)))

(defn- contains-reasons?
  [run reasons]
  (->> run
       :status
       :conditions
       (some #(reasons (:reason %)))))

(defn- list-runs
  [{:tekton.api/keys [url]} label-selectors reasons]
  (->> (http-client/send-and-await #:http{:url          "{url}/taskruns"
                                          :path-params  {:url url}
                                          :query-params {:labelSelector label-selectors}})
       :items
       (filter #(contains-reasons? % reasons))))

(defn get-running-tasks
  [config]
  (list-runs config "!tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired" #{"Running"}))

(defn get-completed-tasks
  [config]
  (list-runs config "tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired" #{"Succeeded" "Failed"}))

(defn- add-label
  [run {:api.server/keys [url]} label-key]
  (http-client/send-async #:http{:verb        :patch
                                 :url         "{url}{link}"
                                 :produces    "application/json-patch+json"
                                 :path-params {:url  url
                                               :link (get-in run [:metadata :selfLink])}
                                 :payload     [{:op    "add"
                                                :path  (str "/metadata/labels/tekton-watcher~1" label-key)
                                                :value "true"}]}))

(defpub watch-running-tasks
  #{:task-run/running}
  [config]
  (->> config
       get-running-tasks
       (map #(assoc {:message/topic :task-run/running}
                    :message/resource %))))

(defpub watch-completed-tasks
  #{:task-run/succeeded :task-run/failed}
  [config]
  (->> config
       get-completed-tasks
       (map #(assoc {:message/topic (get-topic %)}
                    :message/resource %))))

(defsub run-started :task-run/running
  [task-run config]
  (add-label task-run config "running-event-fired"))

(defsub run-succeeded :task-run/succeeded
  [task-run config]
  (add-label task-run config "completed-event-fired"))

(defsub run-failed :task-run/failed
  [task-run config]
  (add-label task-run config "completed-event-fired"))
