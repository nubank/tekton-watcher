(ns tekton-watcher.slack-notifications-test
  (:require [clojure.core.async :refer [<!!]]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.standalone :as standalone]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [providing]]
            [slack-api.core :as slack]
            [tekton-watcher.http-client :as http-client]
            [tekton-watcher.slack-notifications :as slack-notifications]))

(deftest render-taskruns-test
  (let [pipeline-run {:status
                      {:taskRuns
                       {:heimdall-pr-run-7j86z-unit-tests-jjhxf
                        {:pipelineTaskName "unit-tests"
                         :status
                         {:conditions
                          [{:reason "Succeeded"
                            :status "True"
                            :type   "Succeeded"}]}}
                        :heimdall-pr-run-7j86z-cljfmt-dpsd5
                        {:pipelineTaskName "cljfmt"
                         :status
                         {:conditions
                          [{:reason "Failed"
                            :status "False"
                            :type   "Succeeded"}]}}}}}]

    (testing "returns a vector containing Slack blocks describing the taskruns"
      (is (= [{:type "section",
               :text {:type "mrkdwn", :text ":fire: *cljfmt*"},
               :accessory
               {:type "button",
                :text {:type "plain_text", :text "Details"},
                :url
                "http://localhost:9000/#/namespaces/dev/taskruns/heimdall-pr-run-7j86z-cljfmt-dpsd5"}}
              {:type "section",
               :text {:type "mrkdwn", :text ":thumbsup: *unit-tests*"},
               :accessory
               {:type "button",
                :text {:type "plain_text", :text "Details"},
                :url
                "http://localhost:9000/#/namespaces/dev/taskruns/heimdall-pr-run-7j86z-unit-tests-jjhxf"}}]  (slack-notifications/render-taskruns
                                                                                                              {:config            {:tekton.dashboard/task-run "http://localhost:9000/#/namespaces/dev/taskruns/{task-run}"}
                                                                                                               :pipeline-run/data pipeline-run}))))))

(deftest post-message-test
  (testing "reads the specified templates, replaces any declared placeholders
  with values supplied through the context map and sends the message by using
  the Slack API"
    (providing [(slack/call (standalone/match? {:slack/method      :chat/post-message
                                                :slack.client/opts {:oauth-token-fn fn?}
                                                :slack.req/payload {:channel "U123"
                                                                    :blocks  #"\"text\":\"Hey John,\\nThe pipelinerun didn't succeed.\""
                                                                    :text    "Hey John,\nThe pipelinerun didn't succeed.\n"}}))
                {:ok true}]
               (is (= {:ok true}
                      (slack-notifications/post-message {:config               {:slack/oauth-token   "token"
                                                                                :slack.templates/dir "test/resources/fixtures"}
                                                         :receiver/name        "John"
                                                         :receiver/channel     "U123"
                                                         :template.blocks/name "pipeline-run-did-not-succeed.json"
                                                         :template.text/name   "pipeline-run-did-not-succeed.txt"}))))))

(deftest lookup-user-by-email-test
  (let [pipeline-run {:metadata {:name        "heimdall-run-1"
                                 :annotations {"tekton-watcher.slack/preferred-user-email" "john.doe@mycompany.com"}}}
        commit       {:commit {:author {:email "john.doe@gmail.com"}}}
        config       {:slack/oauth-token "token"}
        user         {:name "johndoe"}]

    (testing "attempts to lookup the user by the preferred e-mail declared in
      the pipelinerun"
      (providing [(slack/call (standalone/match? {:slack/method      :users/lookup-by-email
                                                  :slack.client/opts {:oauth-token-fn fn?}
                                                  :slack.req/query   {:email "john.doe@mycompany.com"}}))
                  {:ok true :user user}]
                 (is (= user
                        (slack-notifications/lookup-user-by-email pipeline-run commit config)))))

    (testing "attempts to lookup the user by the commit e-mail when it can't be
    found by the preferred e-mail"
      (providing [(slack/call (standalone/match? {:slack.req/query {:email "john.doe@mycompany.com"}}))
                  {:ok true :error "users_not_found"}
                  (slack/call (standalone/match? {:slack.req/query {:email "john.doe@gmail.com"}}))
                  {:ok true :user user}]
                 (is (= user
                        (slack-notifications/lookup-user-by-email pipeline-run commit config)))))

    (testing "attempts to lookup the user by the commit e-mail when the preferred e-mail isn't supplied"
      (providing [(slack/call (standalone/match? {:slack.req/query {:email "john.doe@gmail.com"}}))
                  {:ok true :user user}]
                 (is (= user
                        (slack-notifications/lookup-user-by-email {:metadata {:name "heimdall-run-1"}} commit config)))))

    (testing "returns nil when the user can't be found"
      (providing [(slack/call (standalone/match? {:slack.req/query {:email "john.doe@mycompany.com"}}))
                  {:ok true :error "users_not_found"}
                  (slack/call (standalone/match? {:slack.req/query {:email "john.doe@gmail.com"}}))
                  {:ok true :error "users_not_found"}]
                 (is (nil? (slack-notifications/lookup-user-by-email pipeline-run commit config)))))))

(deftest make-composition-context-test
  (testing "builds a context map with all relevant information to compose and
  send notifications"
    (let [pipeline-run {:metadata {:name        "heimdall-run-1"
                                   :annotations {"tekton-watcher.github/repository-owner" "nubank"
                                                 "tekton-watcher.github/repository-name"  "heimdall"
                                                 "tekton-watcher.github/commit-sha"       "abc123"}}}
          config       {:github.commits/url            "https://api.github.com/repos/{repository-owner}/{repository-name}/commits/{commit-sha}"
                        :github/oauth-token            "token"
                        :github.repositories/html-url  "https://github.com/{repository-owner}/{repository-name}"
                        :tekton.dashboard/pipeline-run "http://localhost:9000/#/namespaces/dev/pipelineruns/{pipeline-run}"}
          commit       {:commit {:author {:email "john.doe@gmail.com"}}}
          slack-user   {:name "johndoe"}]
      (providing [(http-client/send-and-await #:http{:url         "https://api.github.com/repos/{repository-owner}/{repository-name}/commits/{commit-sha}"
                                                     :path-params {:repository-owner "nubank"
                                                                   :repository-name  "heimdall"
                                                                   :commit-sha       "abc123"}
                                                     :oauth-token "token"}) commit
                  (slack-notifications/lookup-user-by-email pipeline-run commit config) slack-user]
                 (is (= {:config                 config
                         :github.commit/data     commit
                         :github.repository/data {:owner    "nubank"
                                                  :name     "heimdall"
                                                  :html-url "https://github.com/nubank/heimdall"}
                         :pipeline-run/data      pipeline-run
                         :pipeline-run/link      "http://localhost:9000/#/namespaces/dev/pipelineruns/heimdall-run-1"
                         :slack/user             slack-user}
                        (slack-notifications/make-composition-context pipeline-run config)))))))

(deftest send-notifications-test
  (let [succeeded-pipeline-run {:metadata {:name        "heimdall-run-1"
                                           :annotations {"tekton-watcher.github/repository-owner" "nubank"
                                                         "tekton-watcher.github/repository-name"  "heimdall"
                                                         "tekton-watcher.github/commit-sha"       "abc123"
                                                         "tekton-watcher.slack/channel"           "#dev"}}
                                :status   {:taskRuns
                                           {:heimdall-pr-run-7j86z-unit-tests-jjhxf
                                            {:pipelineTaskName "unit-tests"
                                             :status
                                             {:conditions
                                              [{:reason "Succeeded"
                                                :status "True"
                                                :type   "Succeeded"}]}}
                                            :heimdall-pr-run-7j86z-cljfmt-dpsd5
                                            {:pipelineTaskName "cljfmt"
                                             :status
                                             {:conditions
                                              [{:reason "Succeeded"
                                                :status "True"
                                                :type   "Succeeded"}]}}}}}
        failed-pipeline-run    {:metadata {:name        "heimdall-run-2"
                                           :annotations {"tekton-watcher.github/repository-owner" "nubank"
                                                         "tekton-watcher.github/repository-name"  "heimdall"
                                                         "tekton-watcher.github/commit-sha"       "abc123"
                                                         "tekton-watcher.slack/channel"           "#dev"}}
                                :status   {:taskRuns
                                           {:heimdall-pr-run-7j86z-unit-tests-jjhxf
                                            {:pipelineTaskName "unit-tests"
                                             :status
                                             {:conditions
                                              [{:reason "Succeeded"
                                                :status "True"
                                                :type   "Succeeded"}]}}
                                            :heimdall-pr-run-7j86z-cljfmt-dpsd5
                                            {:pipelineTaskName "cljfmt"
                                             :status
                                             {:conditions
                                              [{:reason "Failed"
                                                :status "False"
                                                :type   "Succeeded"}]}}}}}
        config                 {:slack.templates/dir       "test/resources/fixtures"
                                :tekton.dashboard/task-run "http://localhost:9000/#/namespaces/dev/taskruns/{task-run}"}
        context                {:config                 config
                                :github.commit/data     {:commit {:author {:email "john.doe@gmail.com"}}}
                                :github.repository/data {:owner    "nubank"
                                                         :name     "heimdall"
                                                         :html-url "https://github.com/nubank/heimdall"}
                                :pipeline-run/link      "http://localhost:9000/#/namespaces/dev/pipelineruns/heimdall-run-1"
                                :slack/user             {:id      "U123"
                                                         :name    "johndoe"
                                                         :profile {:first-name "John"}}}]

    (testing "does nothing when the following annotations aren't present"
      (are [annotation] (nil? (slack-notifications/send-notifications (update-in succeeded-pipeline-run
                                                                                 [:metadata :annotations]
                                                                                 #(dissoc % annotation))
                                                                      :pipeline-run/succeeded
                                                                      config))
        "tekton-watcher.github/repository-owner"
        "tekton-watcher.github/repository-name"
        "tekton-watcher.github/commit-sha"))

    (testing "does nothing when the Slack user can't be found"
      (providing [(slack-notifications/make-composition-context succeeded-pipeline-run config) {}]
                 (nil?
                  (slack-notifications/send-notifications succeeded-pipeline-run :pipeline-run/succeeded config))))

    (testing "sends notifications about the succeeded pipeline to the user who
      made the commit, as well to the configured channel"
      (providing [(slack-notifications/make-composition-context succeeded-pipeline-run config) (assoc context :pipeline-run/data succeeded-pipeline-run)
                  (slack/call (standalone/match? {:slack/method      :chat/post-message
                                                  :slack.req/payload {:channel "U123"
                                                                      :blocks  #"\"text\":\"Hey John,\\nThe pipelinerun succeeded!\""
                                                                      :text    "Hey John,\nThe pipelinerun succeeded!\n"}}))
                  {:ok true :channel "U123"}
                  (slack/call (standalone/match? {:slack/method      :chat/post-message
                                                  :slack.req/payload {:channel "#dev"
                                                                      :blocks  #"\"text\":\"Hey peeps,\\nThe pipelinerun succeeded!\""
                                                                      :text    "Hey peeps,\nThe pipelinerun succeeded!\n"}}))
                  {:ok true :channel "#dev"}]
                 (is (match? (m/in-any-order [{:ok true :channel "U123"}
                                              {:ok true :channel "#dev"}])
                             (<!! (slack-notifications/send-notifications succeeded-pipeline-run :pipeline-run/succeeded config))))))

    (testing "do not send a message to a Slack channel when there is no one
    declared"
      (let [pipeline-run (update-in succeeded-pipeline-run [:metadata :annotations] #(dissoc % "tekton-watcher.slack/channel"))]
        (providing [(slack-notifications/make-composition-context pipeline-run config) (assoc context :pipeline-run/data pipeline-run)
                    (slack/call (standalone/match? {:slack/method      :chat/post-message
                                                    :slack.req/payload {:channel "U123"
                                                                        :blocks  #"\"text\":\"Hey John,\\nThe pipelinerun succeeded!\""
                                                                        :text    "Hey John,\nThe pipelinerun succeeded!\n"}}))
                    {:ok true :channel "U123"}]
                   (is (= [{:ok true :channel "U123"}]
                          (<!! (slack-notifications/send-notifications pipeline-run :pipeline-run/succeeded config)))))))

    (testing "does nothing when the pipeline run succeeds and it's configured to
    not send notifications"
      (let [pipeline-run (assoc-in succeeded-pipeline-run [:metadata :annotations "tekton-watcher.slack.preferences/only-failed-runs"] "true")]
        (providing [(slack-notifications/make-composition-context pipeline-run config) pipeline-run]
                   (nil?
                    (slack-notifications/send-notifications pipeline-run :pipeline-run/succeeded config)))))

    (testing "sends notifications about the failed pipeline run to the user who
      made the commit, as well to the configured channel"
      (providing [(slack-notifications/make-composition-context failed-pipeline-run config) (assoc context :pipeline-run/data failed-pipeline-run)
                  (slack/call (standalone/match? {:slack/method      :chat/post-message
                                                  :slack.req/payload {:channel "U123"
                                                                      :blocks  #"\"text\":\"Hey John,\\nThe pipelinerun didn't succeed.\""
                                                                      :text    "Hey John,\nThe pipelinerun didn't succeed.\n"}}))
                  {:ok true :channel "U123"}
                  (slack/call (standalone/match? {:slack/method      :chat/post-message
                                                  :slack.req/payload {:channel "#dev"
                                                                      :blocks  #"\"text\":\"Hey peeps,\\nThe pipelinerun didn't succeed.\""
                                                                      :text    "Hey peeps,\nThe pipelinerun didn't succeed.\n"}}))
                  {:ok true :channel "#dev"}]
                 (is (match? (m/in-any-order [{:ok true :channel "U123"}
                                              {:ok true :channel "#dev"}])
                             (<!! (slack-notifications/send-notifications failed-pipeline-run :pipeline-run/failed config))))))))
