(ns tekton-watcher.config
  (:require [clojure.data :as data]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [tekton-watcher.misc :as misc])
  (:import clojure.lang.Var
           java.io.File))

(defn read-resource
  "Reads config data from the resource in question."
  [^String resources-path]
  (let [input-stream (io/input-stream (io/resource (str "tekton_watcher/" resources-path)))]
    (when input-stream
      (misc/read-edn input-stream))))

(defn read-file
  "Reads config data from the file in question."
  [^String file-path]
  (let [^File file (io/file file-path)]
    (when (misc/file-exists? file)
      (misc/read-edn file))))

(defn- log-kv
  "Logs keys and values in the config data."
  [source-name current-config loaded-config]
  (let [[_ overrides] (data/diff current-config loaded-config)]
    (doseq [[k v] overrides]
      (log/info :overriding-config :source source-name :key k :value v))))

(defn- wrap-reader
  "Given a var that references a no args function that reads config data
  from an arbitrary source, returns a function that takes the current
  config data and performs a shallow merge of the current config into
  the config read by the reader function."
  [^Var reader-var]
  (fn [current-config]
    (let [source-name   (subs (str reader-var) 2)
          loaded-config (@@reader-var)]
      (if-not loaded-config
        (do (log/info :config :source source-name :message :no-config-found)
            current-config)
        (do (log-kv source-name current-config loaded-config)
            (merge current-config loaded-config))))))

(defn- read-waterfall
  "Reads the config data from the supplied sources."
  [& sources]
  (reduce (fn [config reader]
            (reader config))
          {} (map #(wrap-reader %) sources)))

(defn- render-config
  "Traverses config values and replaces templates (e.g. {key}) with the
  corresponding values supplied through the config data."
  [config]
  (misc/map-vals #(if-not (string? %)
                    %
                    (misc/render % config))
                 config))

(defn read-github-oauth-token
  "Reads the Github oauth token used for updating status checks.

  Throws an exception if the secret can't be found."
  [^String file-path]
  (let [^File file (io/file file-path)]
    (if (misc/file-exists? file)
      (slurp file)
      (throw (ex-info "Github oauth token not found. Did you forget to create a secret named `github-statuses-updater`?"
                      {:path file-path})))))

(defn- add-github-oauth-token
  "Given a config data, reads the Github oauth token used to make
  requests to the statuses API and assoc's it into the config data."
  [{:github.oauth-token/keys [path] :as config}]
  (assoc config
         :github/oauth-token (read-github-oauth-token path)))

(def resource
  "Reads config data from tekton-watcher/config.edn on the classpath."
  (delay (partial read-resource "config.edn")))

(def config-map
  "Reads config data from a config map mounted at
  /etc/tekton-watcher/config.edn."
  (delay (partial read-file "/etc/tekton-watcher/config.edn")))

(defn read-config
  "Reads config data from the supported sources (currently resources and config map)."
  []
  (-> (read-waterfall #'resource #'config-map)
      render-config
      add-github-oauth-token))
