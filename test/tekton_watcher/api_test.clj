(ns tekton-watcher.api-test
  (:require [clojure.core.async :as async :refer [<!!]]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [tekton-watcher.api :as api :refer [defpub defsub]]
            [tekton-watcher.misc :as misc]))

(def resources (atom {}))

(defn reset-resources!
  []
  (reset! resources {}))

(use-fixtures :each (fn [test]
                      (reset-resources!)
                      (test)))

(defn sleep-for
  [^Long seconds]
  (<!! (async/timeout (* 1000 seconds))) ())

(defn watch-running-tasks*
  [_]
  [#:message{:topic    :task-run/running
             :resource {:metadata {:name   (str "task-run-" (misc/correlation-id))
                                   :labels {"a" "b"}}}}])

(defn watch-succeeded-tasks*
  [_]
  [#:message{:topic    :task-run/succeeded
             :resource {:metadata {:name   (str "task-run-" (misc/correlation-id))
                                   :labels {"c" "d"}}}}])

(defn update-resources!
  [topic resource]
  (swap! resources update
         topic #(conj % resource)))

(defn task-run-running*
  [task-run _]
  (update-resources! :task-run/running task-run))

(defn task-run-succeeded*
  [task-run _]
  (update-resources! :task-run/succeeded task-run))

(deftest publisher-test
  (testing "returns a map with the expected values"
    (is (match? #:publisher{:topics #{:task-run/running}
                            :start  fn?
                            :stop   fn?}
                (api/publisher :pub1 #{:task-run/running} identity)))))

(deftest subscriber-test
  (testing "returns a map with the expected values"
    (is (match? #:subscriber{:topic :task-run/running
                             :start fn?
                             :stop  fn?}
                (api/subscriber :sub1 :task-run/running identity)))))

(def config {})

(deftest messaging-test
  (testing "wires up publishers and subscribers"
    (let [publishers  [(api/publisher :pub1 #{:task-run/running} watch-running-tasks*)]
          subscribers [(api/subscriber :sub1 :task-run/running task-run-running*)]]
      (api/start-messaging publishers subscribers config)
      (sleep-for 2)
      (api/stop-messaging publishers subscribers)

      (is (= [:task-run/running]
             (keys @resources))
          "only the expected events have been published and subscribed")

      (is (match? {:task-run/running
                   (m/prefix [{:metadata
                               {:name #"^task-run-.*"}}])}
                  @resources)
          "the correct data structure has been consumed by the subscriber")

      (is (> (count (:task-run/running @resources))
             2)
          "more than one message has been produced within the interval"))

    (reset-resources!))

  (testing "publishers only dispatch messages to the correct subscribers"
    (let [publishers  [(api/publisher :pub1 #{:task-run/running} watch-running-tasks*)]
          subscribers [(api/subscriber :sub1 :task-run/succeeded task-run-succeeded*)]]
      (api/start-messaging publishers subscribers config)
      (sleep-for 1)
      (api/stop-messaging publishers subscribers)

      (is (= {}
             @resources)
          "no messages have been consumed because the subscriber was expecting a
      `:task-run/succeeded` event"))

    (reset-resources!))

  (testing "multiple publishers and subscribers running together"
    (let [publishers  [(api/publisher :pub1 #{:task-run/running} watch-running-tasks*)
                       (api/publisher :pub2 #{:task-run/succeeded} watch-succeeded-tasks*)]
          subscribers [(api/subscriber :sub1 :task-run/running task-run-running*)
                       (api/subscriber :sub2 :task-run/succeeded task-run-succeeded*)]]
      (api/start-messaging publishers subscribers config)
      (sleep-for 2)
      (api/stop-messaging publishers subscribers)

      (is (= [:task-run/running :task-run/succeeded]
             (keys @resources))
          "only the expected events have been published and subscribed")

      (is (match? {:task-run/running
                   (m/prefix [{:metadata
                               {:name   #"^task-run-.*"
                                :labels {"a" "b"}}}])
                   :task-run/succeeded
                   (m/prefix [{:metadata
                               {:name   #"^task-run-.*"
                                :labels {"c" "d"}}}])}
                  @resources)
          "both subscribers have received their messages")

      (is (> (count (:task-run/running @resources))
             2)
          "more than one message has been produced to the `:task-run/running`
          topic within the interval")

      (is (> (count (:task-run/succeeded @resources))
             2)
          "more than one message has been produced to the `:task-run/succeeded`
          topic within the interval"))

    (reset-resources!)))

;; Macros

(defpub watch-running-tasks
  #{:task-run/running}
  [config]
  (watch-running-tasks* config))

(defsub task-run-running
  :task-run/running
  [task-run config]
  (task-run-running* task-run config))

(deftest macros-test
  (testing "wires up publishers and subscribers"
    (let [publishers  [watch-running-tasks]
          subscribers [task-run-running]]
      (api/start-messaging publishers subscribers config)
      (sleep-for 1)
      (api/stop-messaging publishers subscribers)

      (is (match? {:task-run/running
                   (m/prefix [{:metadata
                               {:name #"^task-run-.*"}}])}
                  @resources)
          "the correct data structure has been consumed by the subscriber"))))
