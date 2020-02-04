(ns tekton-watcher.config
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [tekton-watcher.misc :as misc]))

(defn- read-edn
  "Reads data in EDN format from the input in question."
  [input]
  (edn/read-string (slurp input)))

(defn read-resource
  "Reads config data from the resource in question."
  [^String file-name]
  (let [input-stream (io/input-stream (io/resource (str "tekton_watcher/" file-name)))]
    (when input-stream
      (read-edn input-stream))))

(defn- file-exists?
  "Returns true if the file in question exists or false otherwise."
  [file]
  (.exists file))

(defn read-file
  [^String file-name]
  (let [file (io/file file-name)]
    (when (file-exists? file)
      (read-edn file))))

(defn- log-kv
  [source-name current-config loaded-config]
  (let [[_ overrides] (data/diff current-config loaded-config)]
    (doseq [[k v] overrides]
      (log/info :overriding-config :source source-name :key k :value v))))

(defn- wrap-reader
  [reader-var]
  (fn [current-config]
    (let [source-name   (subs (str reader-var) 2)
          loaded-config (@reader-var)]
      (if-not loaded-config
        (do (log/info :config :source source-name :message :no-config-found)
            current-config)
        (do (log-kv source-name current-config loaded-config)
            (merge current-config loaded-config))))))

(defn- read-waterfall
  [& sources]
  (reduce #(%2 %1) {}
          (map #(wrap-reader %) sources)))

(defn- read-github-oauth-token
  [{:github.oauth-token/keys [path] :as config}]
  (let [file (io/file path)]
    (if (file-exists? file)
      (assoc config :github/oauth-token (slurp file))
      (throw (ex-info "Github oauth token not found. Did you forget to create a secret named `github-statuses-updater`?"
                      {:path path})))))

(defn render-config
  [config]
  (misc/map-vals #(if-not (string? %)
                    %
                    (misc/render % config))
                 config))

(def resource (partial read-resource "config.edn"))

(def config-map (partial read-file "/etc/tekton-watcher/config.edn"))

(defn read-config
  []
  (-> (read-waterfall #'resource #'config-map)
      render-config
      read-github-oauth-token))
