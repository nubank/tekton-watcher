(ns tekton-watcher.pulls
  (:require [tekton-watcher.api :refer [defsub]]
            [tekton-watcher.http-client :as http-client]
            [tekton-watcher.misc :as misc]))

(defn- link-to-task-run-on-dashboard
  [task-run config]
  (misc/render (config :tekton.dashboard/task-run)
               {:task-run (get-in task-run [:metadata :name])}))

(defn- get-sha
  [git-resource]
  (first (keep #(when (= "revision" (:name %))
                  (:value %))
               (get-in git-resource [:spec :params]))))

(defn- get-git-resource
  [task-run {:tekton.api/keys [url]}]
  (->> task-run
       :spec
       :inputs
       :resources
       (map #(get-in % [:resourceRef :name]))
       (map #(http-client/send-and-await #:http{:url         "{url}/pipelineresources/{resource-name}"
                                                :path-params {:url           url
                                                              :resource-name %}}))
       (filter #(get-in % [:metadata :annotations "tekton-watcher/statuses-url"]))
       first))

(defn update-commit-status
  [task-run {:github/keys [oauth-token] :as config} {:status/keys [state description]}]
  (let [task-name    (get-in task-run [:metadata :labels "tekton.dev/pipelineTask"])
        git-resource (get-git-resource task-run config)]
    (when git-resource
      (let [sha          (get-sha git-resource)
            statuses-url (get-in git-resource [:metadata :annotations "tekton-watcher/statuses-url"])]
        (http-client/send-async #:http{:verb        :post
                                       :url         statuses-url
                                       :oauth-token oauth-token
                                       :path-params {:sha sha}
                                       :payload     {:state       state
                                                     :context     (str "nubank/ci-cd: " task-name)
                                                     :description description
                                                     :target_url  (link-to-task-run-on-dashboard task-run config)}})))))

(defsub run-started :task-run/running
  [task-run config]
  (update-commit-status task-run config #:status{:state       "pending"
                                                 :description "Tekton is running your tests..."}))

(defsub run-succeeded :task-run/succeeded
  [task-run config]
  (update-commit-status task-run config #:status{:state       "success"
                                                 :description "your tests passed on Tekton!"}))
