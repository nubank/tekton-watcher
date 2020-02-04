(ns tekton-watcher.api
  (:require [clojure.core.async :as async :refer [<! >! go-loop]]
            [clojure.tools.logging :as log]))

(def ^:private digits-and-letters
  (keep (comp #(when (Character/isLetterOrDigit %)
                 %) char) (range 48 123)))

(defn correlation-id
  "Returns a random string composed of numbers ([0-9]) and
  letters ([a-zA-Z]) to be used as a correlation id."
  []
  (apply str (repeatedly 7 #(rand-nth digits-and-letters))))

(defn qualified-name
  [ns name]
  (keyword (str ns "/" name)))

(defn- get-messages
  [{:keys [config handler publisher-name]}]
  (try
    (let [messages (handler config)]
      (if (seq messages)
        messages
        (log/info :out-message :publisher publisher-name :no-resources-found)))
    (catch Throwable t
      (log/error t :out-message-error :publisher publisher-name))))

(defn- publisher*
  [{:keys [channel config handler publisher-name]}]
  (go-loop []
    (let [messages (handler config)]
      (doseq [message messages]
        (let [cid                              (correlation-id)
              {:message/keys [topic resource]} message]
          (log/info :out-message :publisher publisher-name :cid cid :topic topic :resource-name (get-in resource [:metadata :name]))
          (>! channel (assoc message :message/cid cid))))
      (<! (async/timeout 500))
      (recur))))

(defn publisher
  [{:keys [publisher-name topics] :as options}]
  (let [channel (async/chan)]
    (log/info :starting-publisher :publisher publisher-name :topics topics)
    (publisher* (assoc options :channel channel))
    (async/pub channel :message/topic)))

(defmacro defpub
  [name topics args & body]
  (let [pub-name (qualified-name *ns* (clojure.core/name name))]
    `(def ~name
       #:publisher{:topics ~topics
                   :start  (fn [config#]
                             (publisher {:config         config#
                                         :handler        (fn ~args
                                                           ~@body)
                                         :publisher-name ~pub-name
                                         :topics         ~topics}))})))

(defn- subscriber*
  [{:keys [config channel handler subscriber-name]}]
  (go-loop []
    (let [{:message/keys [cid topic resource]
           :or           {cid "default"}
           :as           message} (<! channel)
          resource-name           (get-in resource [:metadata :name])]
      (try
        (log/info :in-message :subscriber subscriber-name :cid cid :topic topic :resource-name resource-name)
        (handler resource config)
        (catch Throwable t
          (log/error t :in-message-error :subscriber subscriber-name :cid cid :topic topic :resource-name resource-name)))
      (recur))))

(defn subscriber
  [{:keys [publisher subscriber-name topic] :as options}]
  (let [channel (async/chan)]
    (log/info :starting-subscriber :subscriber subscriber-name :topic topic)
    (subscriber* (assoc options :channel channel))
    (async/sub publisher topic channel)))

(defmacro defsub
  [name topic args & body]
  (let [sub-name (qualified-name *ns* (clojure.core/name name))]
    `(def ~name
       #:subscriber{:topic ~topic
                    :start (fn        [publisher# config#]
                             (subscriber {:config          config#
                                          :handler         (fn ~args
                                                             ~@body)
                                          :publisher       publisher#
                                          :subscriber-name ~sub-name
                                          :topic           ~topic}))})))

(defn start-messaging
  [publishers subscribers config]
  (letfn [(start-subscribers [topics publisher]
            (run! #(apply (:subscriber/start %) [publisher config])
                  (filter #(topics (:subscriber/topic %)) subscribers)))]
    (doseq [{:publisher/keys [topics start]} publishers]
      (let [publisher (start config)]
        (start-subscribers topics publisher)))))
