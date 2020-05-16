(ns tekton-watcher.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [providing]]
            [tekton-watcher.config :as config]
            [tekton-watcher.misc :as misc]))

(deftest read-resource-test
  (testing "reads config data from the supplied resource file"
    (is (not (empty?
              (config/read-resource "config.edn"))))))

(deftest read-file-test
  (testing "reads config data from the supplied file path"
    (providing [(misc/file-exists? (io/file "/etc/tekton-watcher/config.edn")) true
                (slurp (io/file "/etc/tekton-watcher/config.edn")) "{:tekton-watcher/key \"value\"}"]
               (is (= {:tekton-watcher/key "value"}
                      (config/read-file "/etc/tekton-watcher/config.edn")))))

  (testing "returns nil when there is no file at the supplied path"
    (providing [(misc/file-exists? (io/file "/etc/tekton-watcher/config.edn")) false]
               (is (nil? (config/read-file "/etc/tekton-watcher/config.edn"))))))

(deftest read-secret-test
  (testing "reads a secret"
    (providing [(misc/file-exists? (io/file "/etc/github-statuses-updater/oauth-token")) true
                (slurp (io/file "/etc/github-statuses-updater/oauth-token")) "token"]
               (is (= "token"
                      (config/read-secret "/etc/github-statuses-updater/oauth-token" "my token"))))))

(testing "throws an exception when the secret can't be found"
  (providing [(misc/file-exists? (io/file "/etc/github-statuses-updater/oauth-token")) false]
             (is (thrown? java.io.FileNotFoundException
                          (config/read-secret "/etc/github-statuses-updater/oauth-token" "my token")))))

(deftest read-config-test
  (providing [(config/read-resource "config.edn") {:api.server/url          "http://localhost:8080"
                                                   :github.oauth-token/path "/etc/github-statuses-updater/oauth-token"
                                                   :slack.oauth-token/path  "/etc/slack/oauth-token"}
              (config/read-file "/etc/tekton-watcher/config.edn") {:api.server/url   "http://localhost:9000"
                                                                   :tekton.api/path  "/apis/tekton.dev/v1alpha1"
                                                                   :tekton.api/url
                                                                   "{api.server/url}{tekton.api/path}/namespaces/{tekton/namespace}"
                                                                   :tekton/namespace "default"}
              (config/read-secret "/etc/github-statuses-updater/oauth-token" string?) "github-token"
              (config/read-secret "/etc/slack/oauth-token" string?) "slack-token"]
             (let [config-data (config/read-config)]

               (testing "config data contains values from the config.edn resource file"
                 (is (match? {:github.oauth-token/path "/etc/github-statuses-updater/oauth-token"
                              :slack.oauth-token/path  "/etc/slack/oauth-token"}
                             config-data)))

               (testing "config data contains values from the mounted config map"
                 (is (match? {:tekton.api/path  "/apis/tekton.dev/v1alpha1"
                              :tekton/namespace "default"}
                             config-data)))

               (testing "values supplied through the config map override values from the resource file"
                 (is (match? {:api.server/url "http://localhost:9000"}
                             config-data)))

               (testing "values containing templates are expanded according to
               other values defined in the config data"
                 (is (match? {:tekton.api/url
                              "http://localhost:9000/apis/tekton.dev/v1alpha1/namespaces/default"}
                             config-data)))

               (testing "config data contains the Github oauth token"
                 (is (match? {:github/oauth-token "github-token"}
                             config-data)))

               (testing "config data contains the Slack oauth token"
                 (is (match? {:slack/oauth-token "slack-token"}
                             config-data))))))
