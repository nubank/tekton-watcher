(ns tekton-watcher.health-test
  (:require [clojure.core.async :as async :refer [<!!]]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [providing]]
            [tekton-watcher.health :as health]
            [tekton-watcher.http-client :as http-client]))

(use-fixtures :each (fn [test]
                      (reset! health/heartbeats {})
                      (test)))

(deftest check-api-server-connection-test
  (let [config {:api.server/url "url"}]
    (testing "returns the status `:failed` when the http client cannot reach the
      API server"
      (providing [(http-client/send-and-await {:http/url "url/apis"})
                  #:http.error{:category  :http-error
                               :throwable (Exception. "connection refused")}]
                 (is (match? (m/equals {:component :http-client
                                        :status    :failed
                                        :message   #"Cause: connection refused$"})
                             (health/check-api-server-connection config)))))

    (testing "returns the status `:ok` when the http client reaches the API
  server"
      (providing [(http-client/send-and-await {:http/url "url/apis"})
                  {:apis []}]
                 (is (match? (m/equals {:component :http-client
                                        :status    :ok
                                        :message   string?})
                             (health/check-api-server-connection config)))))))

(deftest check-heartbeats-test
  (testing "all components are alive"
    (is (= [{:component :component-a
             :status    :ok
             :message   "Alive"}
            {:component :component-b
             :status    :ok
             :message   "Alive"}]
           (health/check-heartbeats {:component-a {:component :component-a
                                                   :timestamp (health/timestamp)}
                                     :component-b {:component :component-b
                                                   :timestamp (health/timestamp)}} (health/timestamp)))))

  (testing "one component is down"
    (let [two-minutes-ago (- (health/timestamp) (* 2 60 1E9))]
      (is (match? (m/equals [{:component :component-a
                              :status    :ok
                              :message   "Alive"}
                             {:component :component-b
                              :status    :down
                              :message   #"hasn't sent heartbeat messages"}])
                  (health/check-heartbeats {:component-a {:component :component-a
                                                          :timestamp (health/timestamp)}
                                            :component-b {:component :component-b
                                                          :timestamp two-minutes-ago}} (health/timestamp)))))))

(deftest alive-test
  (testing "sends a heartbeat message through the channel"
    (let [channel (async/chan)]
      (health/alive channel :component-a)
      (is (match? (m/equals {:component :component-a
                             :timestamp number?})
                  (<!! channel))))))

(deftest start-to-observe-heartbeats-test
  (let [channel (async/chan)]
    (health/start-to-observe-heartbeats channel)

    (testing "the heartbeats atom contains the new heartbeat sent"
      (providing [(health/timestamp) 1]
                 (health/alive channel :component-a)
                 (<!! (async/timeout 100))
                 (is (= {:component-a {:component :component-a
                                       :timestamp 1}}
                        @health/heartbeats))))

    (testing "a new heartbeat message has been sent"
      (health/alive channel :component-b)
      (<!! (async/timeout 100))
      (is (match? (m/equals {:component-a {:component :component-a
                                           :timestamp 1}
                             :component-b {:component :component-b
                                           :timestamp number?}})
                  @health/heartbeats)))

    (testing "updates the heartbeats atom when the component sends a new
    heartbeat message"
      (providing [(health/timestamp) 2]
                 (health/alive channel :component-a)
                 (<!! (async/timeout 100))
                 (is (match? {:component-a {:component :component-a
                                            :timestamp 2}
                              :component-b {:component :component-b
                                            :timestamp number?}}
                             @health/heartbeats))))

    (async/close! channel)))

(deftest liveness-check-test)
