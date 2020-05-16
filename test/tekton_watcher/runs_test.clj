(ns tekton-watcher.runs-test
  (:require [clojure.test :refer :all]
            [mockfn.macros :refer [providing]]
            [tekton-watcher.http-client :as http-client]
            [tekton-watcher.runs :as runs]))

(def in-progress-pipelinerun {:kind "PipelineRun"
                              :status
                              {:taskRuns
                               {:test-pipeline-run-1-lint-mb9g5
                                {:pipelineTaskName "lint"
                                 :status
                                 {:conditions
                                  [{:reason "Failed"
                                    :status "False"
                                    :type   "Succeeded"}]}}
                                :test-pipeline-run-1-unit-tests-r9kn5
                                {:status
                                 {:conditions
                                  [{:reason "Running"
                                    :status "Unknown"
                                    :type   "Succeeded"}]}}}}})

(def succeeded-pipelinerun
  {:kind "PipelineRun"
   :status
   {:conditions
    [{:reason "Succeeded"
      :status "True"
      :type   "Succeeded"}]
    :taskRuns
    {:test-pipeline-run-1-lint-mb9g5
     {:pipelineTaskName "lint"
      :status
      {:conditions
       [{:reason "Succeeded"
         :status "True"
         :type   "Succeeded"}]}}
     :test-pipeline-run-1-unit-tests-r9kn5
     {:status
      {:conditions
       [{:reason "Succeeded"
         :status "True"
         :type   "Succeeded"}]}}}}})

(def failed-pipelinerun
  {:kind "PipelineRun"
   :status
   {:conditions
    [{:reason "Failed"
      :status "False"
      :type   "Succeeded"}]
    :taskRuns
    {:test-pipeline-run-1-lint-mb9g5
     {:pipelineTaskName "lint"
      :status
      {:conditions
       [{:reason "Failed"
         :status "False"
         :type   "Succeeded"}]}}
     :test-pipeline-run-1-unit-tests-r9kn5
     {:status
      {:conditions
       [{:reason "Succeeded"
         :status "True"
         :type   "Succeeded"}]}}}}})

(deftest pipelinerun-completed?-test
  (are [pipelinerun result] (= result (runs/pipelinerun-completed? pipelinerun))
    in-progress-pipelinerun false
    succeeded-pipelinerun   true
    failed-pipelinerun      true))

(def in-progress-taskrun
  {:kind "TaskRun"
   :status
   {:conditions
    [{:reason "Running"
      :status "Unknown"
      :type   "Succeeded"}]}})

(def succeeded-taskrun
  {:kind "TaskRun"
   :status
   {:conditions
    [{:reason "Succeeded"
      :status "True"
      :type   "Succeeded"}]}})

(def failed-taskrun
  {:kind "TaskRun"
   :status
   {:conditions
    [{:reason "Failed"
      :status "False"
      :type   "Succeeded"}]}})

(deftest taskrun-completed?-test
  (are [taskrun result] (= result (runs/taskrun-completed? taskrun))
    in-progress-taskrun false
    succeeded-taskrun   true
    failed-taskrun      true))

(deftest get-pipelineruns-test
  (testing "returns in-progress pipelineruns"
    (providing [(http-client/send-and-await #:http{:url          "{url}/{resource-kind}"
                                                   :path-params  {:url           "url"
                                                                  :resource-kind "pipelineruns"}
                                                   :query-params {:labelSelector "!tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired"}})
                {:items [in-progress-pipelinerun succeeded-pipelinerun failed-pipelinerun]}]
               (is (= [in-progress-pipelinerun]
                      (runs/get-running-pipelineruns {:tekton.api/url "url"})))))

  (testing "returns completed pipelineruns"
    (providing [(http-client/send-and-await #:http{:url          "{url}/{resource-kind}"
                                                   :path-params  {:url           "url"
                                                                  :resource-kind "pipelineruns"}
                                                   :query-params {:labelSelector "tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired"}})
                {:items [in-progress-pipelinerun succeeded-pipelinerun failed-pipelinerun]}]
               (is (= [succeeded-pipelinerun failed-pipelinerun]
                      (runs/get-completed-pipelineruns {:tekton.api/url "url"}))))))

(deftest get-taskruns-test
  (testing "returns in-progress taskruns"
    (providing [(http-client/send-and-await #:http{:url          "{url}/{resource-kind}"
                                                   :path-params  {:url           "url"
                                                                  :resource-kind "taskruns"}
                                                   :query-params {:labelSelector "!tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired"}})
                {:items [in-progress-taskrun succeeded-taskrun failed-taskrun]}]
               (is (= [in-progress-taskrun]
                      (runs/get-running-taskruns {:tekton.api/url "url"})))))

  (testing "returns completed taskruns"
    (providing [(http-client/send-and-await #:http{:url          "{url}/{resource-kind}"
                                                   :path-params  {:url           "url"
                                                                  :resource-kind "taskruns"}
                                                   :query-params {:labelSelector "tekton-watcher/running-event-fired,!tekton-watcher/completed-event-fired"}})
                {:items [in-progress-taskrun succeeded-taskrun failed-taskrun]}]
               (is (= [succeeded-taskrun failed-taskrun]
                      (runs/get-completed-taskruns {:tekton.api/url "url"}))))))

(deftest message-topic-test
  (testing "determines the topic that each pipeline and task run belongs to"
    (are [run topic]
         (= topic (runs/message-topic run))
      succeeded-pipelinerun :pipeline-run/succeeded
      failed-pipelinerun    :pipeline-run/failed
      succeeded-taskrun     :task-run/succeeded
      failed-taskrun        :task-run/failed)))
