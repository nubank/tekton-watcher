(ns tekton-watcher.api
  (:require [clojure.core.async :as async :refer [<! >! go-loop]]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.tools.logging :as log]
            [tekton-watcher.misc :as misc])
  (:import clojure.lang.Keyword))

(defn- ^Keyword qualified-name
  [ns name]
  (keyword (str ns "/" name)))

(defn stop-fn
  "Returns a no args function that closes the supplied channel when called."
  [event name channel]
  (fn []
    (log/info :event event :name name)
    (async/close! channel)))

(defn- get-messages
  "Attempts to get messages produced by the publisher.

  Returns a (possibly empty) sequence of published messages. Catches
  any errors thrown by the underwing handler."
  [{:keys [config handler publisher-name]}]
  (try
    (let [messages (handler config)]
      (when-not (seq messages)
        (log/info :out-message :publisher publisher-name :no-resources-found))
      messages)
    (catch Throwable t
      (log/error t :out-message-error :publisher publisher-name))))

(defn- start-publisher*
  [{:keys [channel publisher-name] :as options}]
  (go-loop []
    (let [messages (get-messages options)]
      (doseq [message messages]
        (let [cid                              (misc/correlation-id)
              {:message/keys [topic resource]} message]
          (log/info :out-message :publisher publisher-name :cid cid :topic topic :resource-name (get-in resource [:metadata :name]))
          (>! channel (assoc message :message/cid cid))))
      (when-not (async.impl/closed? channel)
        (<! (async/timeout 500))
        (recur)))))

(defn- start-publisher
  [{:keys [channel publisher-name topics] :as options}]
  (log/info :event :starting-publisher :name publisher-name :topics topics)
  (start-publisher* options)
  (async/pub channel :message/topic))

(defn publisher
  [pub-name topics handler-fn]
  (let [channel (async/chan)]
    #:publisher{:topics topics
                :start  (fn [config]
                          (start-publisher {:channel        channel
                                            :config         config
                                            :handler        handler-fn
                                            :publisher-name pub-name
                                            :topics         topics}))
                :stop   (stop-fn :stopping-publisher pub-name channel)}))

(defmacro defpub
  [name topics args & body]
  `(def ~name
     (publisher ~(qualified-name *ns* (clojure.core/name name))
                ~topics
                (fn ~args
                  ~@body))))

(defn- start-subscriber*
  [{:keys [config channel handler subscriber-name]}]
  (go-loop []
    (when-let [{:message/keys [cid topic resource]
                :or           {cid "default"}
                :as           message} (<! channel)]
      (let [resource-name (get-in resource [:metadata :name])]
        (try
          (log/info :in-message :subscriber subscriber-name :cid cid :topic topic :resource-name resource-name)
          (handler resource config)
          (catch Throwable t
            (log/error t :in-message-error :subscriber subscriber-name :cid cid :topic topic :resource-name resource-name)))))
    (if (async.impl/closed? channel)
      (log/info :event :subscriber-stopped :name subscriber-name)
      (recur))))

(defn- start-subscriber
  [{:keys [channel publisher subscriber-name topic] :as options}]
  (log/info :event :starting-subscriber :name subscriber-name :topic topic)
  (start-subscriber* options)
  (async/sub publisher topic channel))

(defn subscriber
  [sub-name topic handler-fn]
  (let [channel (async/chan)]
    #:subscriber    {:topic topic
                     :start (fn        [publisher config]
                              (start-subscriber {:channel         channel
                                                 :config          config
                                                 :handler         handler-fn
                                                 :publisher       publisher
                                                 :subscriber-name sub-name
                                                 :topic           topic}))
                     :stop  (stop-fn :stopping-subscriber sub-name channel)}))

(defmacro defsub
  [name topic args & body]
  `(def ~name
     (subscriber ~(qualified-name *ns* (clojure.core/name name))
                 ~topic
                 (fn ~args
                   ~@body))))

(defn start-messaging
  [publishers subscribers config]
  (letfn [(start-subscribers [topics publisher]
            (run! #(apply (:subscriber/start %) [publisher config])
                  (filter #(topics (:subscriber/topic %)) subscribers)))]
    (doseq [{:publisher/keys [topics start]} publishers]
      (let [publisher (start config)]
        (start-subscribers topics publisher)))))

(defn stop-messaging
  [publishers subscribers]
  (letfn [(stop-all [key workers]
            (run! #(apply (get % key) []) workers))]
    (stop-all :publisher/stop publishers)
    (stop-all :subscriber/stop subscribers)))
