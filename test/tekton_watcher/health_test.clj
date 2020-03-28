(ns tekton-watcher.health-test
  (:require [tekton-watcher.health :as health]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [providing calling]]
            [clojure.test :refer :all]
            [tekton-watcher.http-client :as http-client]
            [clojure.core.async :as async]
            [matcher-combinators.matchers :as m]))

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

(deftest threshold-exceeded?-test
  (let [now             (System/nanoTime)
        two-minutes-ago (- now (* 60 1E9))]
    (are [threshold result] (health/threshold-exceeded? threshold now two-minutes-ago)
      1 true
      2 false
      3 false)))

(deftest check-heartbeats-test)
