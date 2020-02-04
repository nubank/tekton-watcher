(ns tekton-watcher.http-client
  (:require [clojure.core.async :as async :refer [<!!]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as httpkit-client]
            [tekton-watcher.misc :as misc])
  (:import java.net.URLEncoder))

(def ^:private http-defaults
  {:as               :stream
   :follow-redirects false
   :user-agent       "tekton-watcher"
   :keepalive        120000
   :timeout          15000})

(def ^:private default-mime-type "application/json; charset=utf-8")

(defn- log-out-request
  [{:keys [cid method url] :as request}]
  (log/info :out-request :cid cid :method method :url url)
  request)

(defn- log-out-response
  [{:keys [status opts]}]
  (let [{:keys [cid started-at url]} opts
        elapsed-time                 (/ (double (- (. System (nanoTime)) started-at)) 1000000.0)]
    (log/info :out-response :cid cid
              :url url
              :status (or status :unknown)
              :elapsed-time-ms elapsed-time)))

(def json-request-parser
  "JSON parser for request's bodies."
  #(json/write-str % :key-fn name))

(defn json-response-parser
  "JSON parser for response's bodies."
  [body]
  (when body
    (json/read (io/reader body)
               :key-fn #(if (re-find #"[/\.]" %)
                          %
                          (keyword %)))))

(defn- error
  [{:keys [status body opts error]}]
  (let [cid (opts :cid)]
    (when (or error (>= status 400))
      #:http{:category :http/error
             :cid      cid
             :error    (or error
                           (ex-info "http error" {:status status
                                                  :body   (json-response-parser body)}))})))

(defn handle-http-response
  [{:keys [status body] :as response}]
  (log-out-response response)
  (or (error response)
      (json-response-parser body)))

(defn- parse-request-body
  "If `:http/payload` is given, parses it according to the supplied
  content-type and assoc's the parsed value as `:body` into the
  request."
  [request {:http/keys [payload]}]
  (if-not payload
    request
    (assoc request :body
           (json-request-parser payload))))

(defn- form-encoder
  "Turns a Clojure map into a string in the x-www-form-urlencoded
  format."
  [data]
  (string/join "&"
               (map (fn [[k v]]
                      (str (name k)
                           "=" (URLEncoder/encode (str v) "UTF-8")))
                    data)))

(defn- add-query-string
  "If `:http/query-params` is given, appends the query string to the
  request url."
  [request {:http/keys [query-params]}]
  (if-not query-params
    request
    (update request :url #(str % "?" (form-encoder query-params)))))

(defn- expand-path-params
  [request {:http/keys [url path-params]}]
  (assoc request :url
         (misc/render url path-params)))

(defn- add-oauth-token
  [request {:http/keys [oauth-token]}]
  (if oauth-token
    (assoc-in request [:headers "authorization"] (str "Bearer " oauth-token))
    request))

(defn build-http-request
  "Returns a suited HTTP request map to be sent by the client."
  [{:http/keys                                                      [verb url cid consumes produces]
    :or                                                             {cid      "default"
                                                                     consumes default-mime-type
                                                                     produces default-mime-type
                                                                     verb     :get} :as req-data}]
  (-> {:method     verb
       :url        url
       :cid        cid
       :started-at (System/nanoTime)
       :headers    {"content-type" produces
                    "accept"       consumes}}
      (merge http-defaults)
      (add-oauth-token req-data)
      (expand-path-params req-data)
      (add-query-string req-data)
      (parse-request-body req-data)))

(defn send-async
  "Sends an asynchronous HTTP request and returns a core.async channel
  filled out with the response."
  [req-data]
  (let [channel (async/chan)]
    (-> (build-http-request req-data)
        log-out-request
        (httpkit-client/request #(async/put! channel (handle-http-response %))))
    channel))

(defn send-and-await
  [req-data]
  (<!! (send-async req-data)))
