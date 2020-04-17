(ns tekton-watcher.api
  (:require [clojure.core.async :as async :refer [<! >! go-loop]]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [tekton-watcher.health :as health]
            [tekton-watcher.misc :as misc])
  (:import clojure.lang.Keyword))

(defn- ^Keyword qualified-name
  "Returns a keyword consisting of ns/name."
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
  [{:channel/keys [out liveness]} {:keys [publisher-name] :as options}]
  (go-loop []
    (health/alive liveness publisher-name)
    (let [messages (get-messages options)]
      (doseq [message messages]
        (let [cid                              (misc/correlation-id)
              {:message/keys [topic resource]} message]
          (log/info :out-message :publisher publisher-name :cid cid :topic topic :resource-name (get-in resource [:metadata :name]))
          (>! out (assoc message :message/cid cid))))
      (when-not (async.impl/closed? out)
        (<! (async/timeout 500))
        (recur)))))

(defn- start-publisher
  [{:channel/keys [out] :as channels} {:keys [publisher-name topics] :as options}]
  (log/info :event :starting-publisher :name publisher-name :topics topics)
  (start-publisher* channels options)
  (async/pub out :message/topic))

(defn publisher
  [pub-name topics handler-fn]
  (let [out-channel (async/chan)]
    #:publisher{:topics topics
                :start  (fn [channels config]
                          (start-publisher (assoc channels :channel/out out-channel)
                                           {:config         config
                                            :handler        handler-fn
                                            :publisher-name pub-name
                                            :topics         topics}))
                :stop   (stop-fn :stopping-publisher pub-name out-channel)}))

(defn- start-subscriber*
  [{:channel/keys [in]} {:keys [config handler subscriber-name]}]
  (go-loop []
    (when-let [{:message/keys [cid topic resource]
                :or           {cid "default"}
                :as           message} (<! in)]
      (let [resource-name (get-in resource [:metadata :name])]
        (try
          (log/info :in-message :subscriber subscriber-name :cid cid :topic topic :resource-name resource-name)
          (handler resource config)
          (catch Throwable t
            (log/error t :in-message-error :subscriber subscriber-name :cid cid :topic topic :resource-name resource-name)))))
    (if (async.impl/closed? in)
      (log/info :event :subscriber-stopped :name subscriber-name)
      (recur))))

(defn- start-subscriber
  [{:channel/keys [in] :as channels} {:keys [publisher subscriber-name topic] :as options}]
  (log/info :event :starting-subscriber :name subscriber-name :topic topic)
  (start-subscriber* channels options)
  (async/sub publisher topic in))

(defn subscriber
  [sub-name topic handler-fn]
  (let [in-channel (async/chan)]
    #:subscriber    {:topic topic
                     :start (fn        [publisher channels config]
                              (start-subscriber (assoc channels :channel/in in-channel)
                                                {:config          config
                                                 :handler         handler-fn
                                                 :publisher       publisher
                                                 :subscriber-name sub-name
                                                 :topic           topic}))
                     :stop  (stop-fn :stopping-subscriber sub-name in-channel)}))

(defn start-messaging
  "Wires up publishers and subscribers, starting up the messaging
  system."
  [publishers subscribers channels config]
  (letfn [(start-subscribers [publisher topics]
            (run! (fn [{:subscriber/keys [start]}]
                    (start publisher channels config))
                  (filter #(topics (:subscriber/topic %)) subscribers)))]
    (doseq [{:publisher/keys [topics start]} publishers]
      (let [publisher (start channels config)]
        (start-subscribers publisher topics)))))

(defn stop-messaging
  "Stops all publishers and subscribers, terminating the messaging
  system."
  [publishers subscribers]
  (letfn [(stop-all [stop-fn-key workers]
            (run! #(apply (get % stop-fn-key) []) workers))]
    (stop-all :publisher/stop publishers)
    (stop-all :subscriber/stop subscribers)))

(s/def ::doc string?)

(s/def ::topic #{:pipeline-run/running :pipeline-run/succeeded :pipeline-run/failed
                 :task-run/running :task-run/succeeded :task-run/failed})

(s/def ::topics (s/coll-of ::topic :kind set? :min-count 1))

(s/def ::args (s/coll-of symbol? :kind vector?))

(s/def ::body (s/+ any?))

(def pub-args (s/alt :docstring+args (s/cat :topics ::topics :doc ::doc :args ::args :body ::body)
                     :args (s/cat :topics ::topics :args ::args :body ::body)))

(defmacro
  ^{:arglists '([name topics docstring args & body]
                [name topics args & body])}
  defpub
  "Defines a new publisher. Topics is a set of topics to which this
  publisher publishes messages. Implementers must return a seq of
  messages within the body. The application's config is available as
  the sole argument to the resulting function.

  Example:

  (defpub my-publisher
  #{:task-run/running}
  [config]
    [#:message{:topic :task-run/running :resource ...}])

  Publisher is a persistent map containing the following keys:

  :publisher/name Keyword

  Identifies this publisher. It's a keyword made up of the namespace
  where this publisher was defined, plus the supplied name.

  :publisher/topics Set of keywords

  The topics that this publisher publishes messages to.

  :publisher/start IFn

  A function that takes two arguments: channels and config. Channels
  is a persistent map containing async channels passed during the
  initialization of the application. For instance: :channel/liveness,
  to report heartbeats. Config is a persistent map returned by
  tekton-watcher.config/read-config.

  :publisher/stop IFn

  A no arguments function that may be used to stop this publisher."
  [name & forms]
  (let [{:keys [doc topics args body]
         :or   {doc ""}} (second (misc/parse-input pub-args forms))]
    `(def ~name
       ~doc
       (publisher ~(qualified-name *ns* (clojure.core/name name))
                  ~topics
                  (fn ~args
                    ~@body)))))

(def sub-args (s/alt :docstring+args (s/cat :topic ::topic :doc ::doc :args ::args :body ::body)
                     :args (s/cat :topic ::topic :args ::args :body ::body)))

(defmacro
  ^{:arglists '([name topic docstring args & body]
                [name topic args & body])}
  defsub
  "Defines a new subscriber. Topic is a keyword that represents the
  topic from which this subscriber consumes messages. The resource
  sent along with the message consumed and the application's config
  are available within the supplied body.

  Example:

  (defsub my-subscriber :task-run/running
  [task-run config]
  (printf \"Task %s is running\" (get-in task-run [:metadata :name])))
  Subscriber is a persistent map containing the following keys:

  :subscriber/name Keyword

  Identifies this subscriber. It's a keyword made up of the namespace
  where this subscriber was defined, plus the supplied name.

  :subscriber/topic Keyword

  The topic from which this subscriber consumes messages.

  :subscriber/start IFn

  A function that takes three arguments: publisher, channels and
  config. Publisher is a persistent map produced by defpub. Channels
  is a persistent map containing async channels passed during the
  initialization of the application. For instance: :channel/liveness,
  to report heartbeats. Config is a persistent map returned by
  tekton-watcher.config/read-config.

  :subscriber/stop IFn

  A no arguments function that may be used to stop this subscriber."
  [name & forms]
  (let [{:keys [doc topic args body]
         :or   {doc ""}} (second (misc/parse-input sub-args forms))]
    `(def ~name
       ~doc
       (subscriber ~(qualified-name *ns* (clojure.core/name name))
                   ~topic
                   (fn ~args
                     ~@body)))))
