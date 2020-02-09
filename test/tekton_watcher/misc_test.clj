(ns tekton-watcher.misc-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [tekton-watcher.misc :as misc])
  (:import java.io.StringReader))

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
