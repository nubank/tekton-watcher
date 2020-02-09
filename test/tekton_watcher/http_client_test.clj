(ns tekton-watcher.http-client-test
  (:require [clojure.core.async :as async]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :refer [for-all]]
            [matcher-combinators.standalone :as standalone]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [calling providing]]
            [org.httpkit.client :as httpkit-client]
            [tekton-watcher.http-client :as http-client]
            [tekton-watcher.misc :as misc])
  (:import java.io.ByteArrayInputStream))

(def custom-keyword-gen
  (gen/fmap #(keyword (string/lower-case (apply str (flatten (interleave (partition 3
                                                                                    %)
                                                                         (repeat "-"))))))
            (gen/vector (gen/char-alpha) 10)))

(deftest json-request-parser-test
  (testing "converts the Clojure data structure to a string in the JSON format"
    (is (= "{\"first-name\":\"John\",\"last-name\":\"Doe\"}"
           (http-client/json-request-parser {:first-name "John"
                                             :last-name  "Doe"})))))

(def to-bytes #(ByteArrayInputStream. (.getBytes %)))

(deftest json-response-parser-test
  (testing "consumes a JSON string and converts it back to a Clojure data
  structure"
    (is (= {:first-name "John"
            :last-name  "Doe"}
           (http-client/json-response-parser
            (to-bytes "{\"first-name\":\"John\",\"last-name\":\"Doe\"}")))))

  (testing "preserves keys as strings when they contain slashes"
    (is (= {"tekton.dev/task" "task1"}
           (http-client/json-response-parser
            (to-bytes "{\"tekton.dev/task\":\"task1\"}"))))))

(defspec json-symmetry-parsers-test
  {:num-tests 50}
  (for-all [data (gen/map
                  custom-keyword-gen
                  (gen/one-of [(gen/string-alphanumeric) (gen/boolean) (gen/int)]))]
           (is (= data
                  (http-client/json-response-parser
                   (to-bytes (http-client/json-request-parser data)))))))

(deftest form-encoder-test
  (testing "converts a Clojure map to a string in the x-www-form-urlencoded
  format"
    (is (= "first-name=John"
           (http-client/form-encoder {:first-name "John"})))

    (is (= "first-name=John&last-name=Doe&some-rate=58%25"
           (http-client/form-encoder {:first-name "John"
                                      :last-name  "Doe"
                                      :some-rate  "58%"})))))

(def req-data #:http{:verb     :post
                     :url      "/apis/namespaces/{namespace}/taskruns/{task-name}"
                     :consumes "application/json; charset=utf-8"
                     :produces "application/json; charset=utf-8"})

(deftest build-http-request-test
  (testing "the returned request includes some default options"
    (is (match? http-client/http-defaults
                (http-client/build-http-request req-data))))

  (testing "adds the HTTP method and the endpoint's URL to the request"
    (is (match? {:method :post
                 :url    "/apis/namespaces/{namespace}/taskruns/{task-name}"}
                (http-client/build-http-request req-data))))

  (testing "adds the content-type and accept headers to the request"
    (is (match? {:headers {"content-type" "application/json; charset=utf-8"
                           "accept"       "application/json; charset=utf-8"}}
                (http-client/build-http-request req-data))))

  (testing "adds the authorization header to the request"
    (is (match? {:headers {"authorization" "Bearer token"}}
                (http-client/build-http-request
                 (assoc req-data :http/oauth-token "token")))))

  (testing "replaces placeholders in the URL with values supplied in the path params"
    (is (match? {:url "/apis/namespaces/default/taskruns/task1"}
                (http-client/build-http-request
                 (assoc req-data :http/path-params {:namespace "default"
                                                    :task-name "task1"})))))

  (testing "appends a query string to the request's URL"
    (is (match?  {:url "/apis/namespaces/{namespace}/taskruns/{task-name}?limit=1&active=true"}
                 (http-client/build-http-request (assoc req-data
                                                        :http/query-params {:limit  1
                                                                            :active true})))))

  (testing "assoc's a parsed body into the request"
    (is (match? {:body "{\"value\":\"foo\"}"}
                (http-client/build-http-request (assoc req-data
                                                       :http/payload {:value "foo"}))))))

(deftest send-requests-test
  (let [channel         (async/chan)
        fake-request-fn (fn [_ callback]
                          (callback {:status  200
                                     :headers {:Content-Type "application/json; charset=UTF-8"}
                                     :body    (to-bytes "{\"message\":\"Hello!\"}")
                                     :opts    {:cid        (misc/correlation-id)
                                               :started-at (System/nanoTime)
                                               :url        (req-data :url)}}))]
    (providing [(httpkit-client/request (standalone/match? map?) (standalone/match? fn?))
                (calling fake-request-fn)]

               (testing "sends an asynchronous request and returns a channel
               containing the response"
                 (is (= {:message "Hello!"}
                        (async/<!! (http-client/send-async req-data)))))

               (testing "sends the request and awaits until the response returns"
                 (is (= {:message "Hello!"}
                        (http-client/send-and-await req-data)))))))
