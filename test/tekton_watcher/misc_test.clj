(ns tekton-watcher.misc-test
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [tekton-watcher.misc :as misc])
  (:import java.io.StringReader
           [java.time Duration LocalDateTime]))

(deftest correlation-id-test
  (is (every? #(re-find #"[\da-zA-Z]{7}" %)
              (repeatedly 50 misc/correlation-id))))

(deftest file-exists-test
  (is (true? (misc/file-exists? (io/file "deps.edn"))))
  (is (false? (misc/file-exists? (io/file "unknown.edn")))))

(deftest map-vals-test
  (are [map result] (= result (misc/map-vals inc map))
    {}          {}
    {:a 1}      {:a 2}
    {:a 1 :b 2} {:a 2 :b 3}))

(deftest read-edn-test
  (is (= {:greeting "Hello!"}
         (misc/read-edn (StringReader. "{:greeting \"Hello!\"}")))))

(deftest render-test
  (testing "replaces the placeholders with the values supplied in the context map"
    (are [template context result] (= result (misc/render template context))
      "/namespaces/{namespace}/taskruns"           {:namespace "default"}               "/namespaces/default/taskruns"
      "/namespaces/{namespace}/taskruns/{task}"    {:namespace "default" :task "task1"} "/namespaces/default/taskruns/task1"
      "/namespaces/{namespace}/taskruns"           {}                                   "/namespaces/{namespace}/taskruns"
      "/namespaces/{namespace}/taskruns/{task-id}" {:namespace "default" :task-id 1}    "/namespaces/default/taskruns/1")))

(deftest parse-input-test
  (testing "returns the conformed data structure"
    (is (= [:a {:x 1}]
           (misc/parse-input (s/alt :a (s/cat :x int?)
                                    :b (s/cat :y string?))
                             [1]))))

  (testing "throws an exception when the supplied data doesn't conform to the
  spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"^Data doesn't conform to the spec$"
                          (misc/parse-input (s/cat :x int?)
                                            [true])))))

(deftest duration-test
  (testing "determines the duration of a task run"
    (is (= (Duration/between
            (LocalDateTime/of 2020 4 9 16 21 46)
            (LocalDateTime/of 2020 4 9 16 23 9))
           (misc/duration {:status
                           {:startTime      "2020-04-09T16:21:46Z"
                            :completionTime "2020-04-09T16:23:09Z"}})))))

(deftest display-duration-test
  (are [start-time completion-time result] (= result (misc/display-duration {:status
                                                                             {:startTime start-time
                                                                              :completionTime completion-time}}))
    "2020-04-09T16:23:09Z" "2020-04-09T16:23:09Z" "0 seconds"
    "2020-04-09T16:23:12Z" "2020-04-09T16:23:13Z" "1 second"
    "2020-04-09T16:23:00Z" "2020-04-09T16:23:59Z" "59 seconds"
    "2020-04-09T16:00:00Z" "2020-04-09T16:01:00Z" "1 minute"
    "2020-04-09T16:05:00Z" "2020-04-09T16:09:00Z" "4 minutes"
    "2020-04-09T16:23:00Z" "2020-04-09T16:29:11Z" "6.18 minutes"
    "2020-04-09T16:23:00Z" "2020-04-09T17:23:00Z" "1 hour"
    "2020-04-09T16:11:00Z" "2020-04-09T18:21:28Z" "2.17 hours"))
