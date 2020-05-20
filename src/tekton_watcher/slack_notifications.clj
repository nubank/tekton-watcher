(ns tekton-watcher.slack-notifications
  "Notify Slack users and channels about pipelinerun status."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [slack-api.core :as slack]
            [tekton-watcher.api :refer [defsub]]
            [tekton-watcher.http-client :as http-client]
            [tekton-watcher.misc :as misc]))

(defn- taskrun-succeeded?
  "True if the taskrun in question succeeded or false otherwise."
  [taskrun]
  (every? #(= "Succeeded" (:reason %))
          (get-in taskrun [:status :conditions])))

(defn- render-taskrun
  "Given a config data and a tuple whose first element is a taskrun name
  and the second one is a taskrun data, returns a section containing a
  button to open the taskrun in the Tekton Dashboard, along with the
  task name and an emoji identifying its status."
  [config [task-run-name {:keys [pipelineTaskName] :as task-run}]]
  (let [hyperlink (misc/render (get config :tekton.dashboard/task-run) {:task-run (name task-run-name)})]
    {:type      "section"
     :text      {:type "mrkdwn"
                 :text (format "%s *%s*"
                               (if (taskrun-succeeded? task-run)
                                 ":thumbsup:" ":fire:")
                               pipelineTaskName)}
     :accessory {:type "button"
                 :text {:type "plain_text"
                        :text "Details"}
                 :url  hyperlink}}))

(defn render-taskruns
  "Given a composition context, returns a vector of Slack blocks that
  describe all taskruns associated to the pipelinerun in question.

  Taskruns are sorted by the corresponding task names and each one contains a
  button that may be used to open the taskrun in the Tekton
  Dashboard."
  [{:keys [config] :as context}]
  (->> (get-in context [:pipeline-run/data :status :taskRuns])
       (sort-by #(:pipelineTaskName (second %)))
       (map (partial render-taskrun config))))

(defn- ^String read-template
  "Reads the template in question from the location where they must be
  mounted and whose value is declared in the supplied config data."
  [{:slack.templates/keys [dir]} ^String template-name]
  (slurp (io/file dir template-name)))

(defn- render-message-blocks
  "Returns a vector representing the message blocks to be sent through
  chat.postMessage method."
  [{:keys [config template.blocks/name] :as context}]
  (let [template  (json/read-str (read-template config name))
        task-runs (render-taskruns context)]
    (into (walk/postwalk #(if (string? %)
                            (misc/render % context)
                            %)
                         template)
          task-runs)))

(defn render-message-text
  "Renders the message that will be sent through the attribute :text of
  the chat.postMessage payload."
  [{:keys [config template.text/name] :as context}]
  (misc/render (read-template config name) context))

(defn post-message
  "Given a context map containing relevant information to compose a
  message, renders the message's text and its blocks and send it to
  the channel given by :receiver/channel (either a regular channel or
  an user)."
  [{:receiver/keys [channel] :as context}]
  (slack/call {:slack/method      :chat/post-message
               :slack.client/opts {:oauth-token-fn (constantly (get-in context [:config :slack/oauth-token]))
                                   :throw-errors?  true}
               :slack.req/payload {:channel channel
                                   :blocks  (json/write-str (render-message-blocks context))
                                   :text    (render-message-text context)}}))

(defn- get-commit-data
  "Calls Github API to retrieve information about the commit that triggered
  the pipelinerun in question."
  [pipeline-run config]
  (http-client/send-and-await #:http{:url         (config :github.commits/url)
                                     :path-params {:repository-owner (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/repository-owner"])
                                                   :repository-name  (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/repository-name"])
                                                   :commit-sha       (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/commit-sha"])}
                                     :oauth-token (config :github/oauth-token)}))

(defn- lookup-user-by-email*
  "Attempts to lookup an user by the supplied e-mail.

  Returns the user data if succeeded or nil otherwise."
  [email {:slack/keys [oauth-token]}]
  (let [{:keys [ok error user] :as response}
        (slack/call {:slack/method      :users/lookup-by-email
                     :slack.client/opts {:oauth-token-fn (constantly oauth-token)}
                     :slack.req/query   {:email email}})]
    (cond
      (= error "users_not_found") (log/warn :slack-user-not-found :email email)
      (not ok)                    (log/error :failed-to-lookup-slack-user :details response))
    user))

(defn lookup-user-by-email
  "Attempts to lookup an user by e-mail by calling the Slack API.

  If the pipelinerun has an annotation
  tekton-watcher.slack/preferred-user-email, use the e-mail in
  question to lookup the user. If the annotation isn't present or if
  an user can't be found, attempts to use the e-mail in the Github
  commit to lookup the user in question.

  Returns the Slack user or nil if no one has been found."
  [pipeline-run commit config]
  (let [preferred-email   (get-in pipeline-run [:metadata :annotations "tekton-watcher.slack/preferred-user-email"])
        committer-email   (get-in commit [:commit :author :email])
        pipeline-run-name (get-in pipeline-run [:metadata :name])]
    (if preferred-email
      (or (lookup-user-by-email* preferred-email config)
          (lookup-user-by-email* committer-email config))
      (do (log/info :preferred-email-not-declared :pipeline-run pipeline-run-name)
          (lookup-user-by-email* committer-email config)))))

(defn- repository-data
  "Returns a map containing information about the Github repository's
  owner, name and HTML URL."
  [pipeline-run config]
  (let [owner (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/repository-owner"])
        name  (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/repository-name"])]
    {:owner    owner
     :name     name
     :html-url (misc/render (config :github.repositories/html-url)
                            {:repository-owner owner
                             :repository-name  name})}))

(defn make-composition-context
  "Given a pipelinerun and the config data, creates a context map with
  relevant information to be used in the message composition."
  [pipeline-run config]
  (let [pipeline-run-name (get-in pipeline-run [:metadata :name])
        hyperlink         (misc/render (get config :tekton.dashboard/pipeline-run) {:pipeline-run pipeline-run-name})
        commit            (get-commit-data pipeline-run config)
        slack-user        (lookup-user-by-email pipeline-run commit config)]
    {:config                 config
     :github.commit/data     commit
     :github.repository/data (repository-data pipeline-run config)
     :pipeline-run/data      pipeline-run
     :pipeline-run/link      hyperlink
     :slack/user             slack-user}))

(defn- notify-user
  "Given a context map containing relevant information to be used in the
  message composition, notifies a Slack user about the status of the
  pipelinerun in question."
  [context]
  (post-message (assoc context :receiver/channel (get-in context [:slack/user :id])
                       :receiver/name (get-in context [:slack/user :profile :first-name]))))

(defn- notify-channel
  "Given a context map containing relevant information to be used in the
  message composition, notifies a Slack channel about the status of
  the pipelinerun in question."
  [context]
  (if-let [channel (get-in context [:pipeline-run/data :metadata :annotations "tekton-watcher.slack/channel"])]
    (post-message (assoc context :receiver/channel channel
                         :receiver/name "peeps"))
    (log/warn :no-channel-to-notify-about-pipelinerun-status :pipeline-run (get-in context [:pipeline-run/data :metadata :name]))))

(defn- eligible-to-send-notifications?
  "True if the pipeline run is eligible to send notifications about its status.

  A pipeline run is considered eligible if it has the necessary annotations to configure the notification"
  [pipeline-run topic]
  (letfn [(notifications-turned-off? []
            (when (= :pipeline-run/succeeded topic)
              (= "true" (get-in pipeline-run [:metadata :annotations "tekton-watcher.slack.preferences/only-failed-runs"]))))]
    (and
     (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/repository-owner"])
     (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/repository-name"])
     (get-in pipeline-run [:metadata :annotations "tekton-watcher.github/commit-sha"])
     (not (notifications-turned-off?)))))

(def ^:private templates
  {:pipeline-run/succeeded {:template.blocks/name "pipeline-run-succeeded.json"
                            :template.text/name   "pipeline-run-succeeded.txt"}
   :pipeline-run/failed    {:template.blocks/name "pipeline-run-did-not-succeed.json"
                            :template.text/name   "pipeline-run-did-not-succeed.txt"}})

(defn send-notifications
  "Sends a notification to the user who made the commit that triggered
  the run and optionally, to a Slack channel configured in the
  pipelinerun."
  [pipelinerun topic config]
  (when (eligible-to-send-notifications? pipelinerun topic)
    (let [{:slack/keys [user] :as context} (merge (make-composition-context pipelinerun config)
                                                  (get templates topic))]
      (when user
        (let [output-channel (async/chan)]
          (async/pipeline-blocking 2
                                   output-channel
                                   (keep #(%))
                                   (async/to-chan  [(partial notify-channel context)
                                                    (partial notify-user context)]))
          (async/into [] output-channel))))))

(defsub pipeline-run-succeeded :pipeline-run/succeeded
  [pipeline-run config]
  (send-notifications pipeline-run :pipeline-run/succeeded config))

(defsub pipeline-run-failed :pipeline-run/failed
  [pipeline-run config]
  (send-notifications pipeline-run :pipeline-run/failed config))
