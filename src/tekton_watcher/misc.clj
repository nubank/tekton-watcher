(ns tekton-watcher.misc
  (:require [clojure.edn :as edn]
            [clojure.string :as string])
  (:import java.io.File))

(def ^:private digits-and-letters
  (keep (comp #(when (Character/isLetterOrDigit %)
                 %) char) (range 48 123)))

(defn correlation-id
  "Returns a random string composed of numbers ([0-9]) and
  letters ([a-zA-Z]) to be used as a correlation identifier."
  []
  (apply str (repeatedly 7 #(rand-nth digits-and-letters))))

(defn file-exists?
  "Returns true if the file exists or false otherwise."
  [^File file]
  (.exists file))

(defn map-vals
  "Applies the function f to each value in the map m and return the
  resulting map."
  [f m]
  (into {} (map (fn [[k v]]
                  [k (f v)]) m)))

(defn read-edn
  "Reads an EDN object and parses it as Clojure data.

  input can be any object supported by clojure.core/slurp."
  [input]
  (edn/read-string (slurp input)))

(defn render
  "Given a template string containing one or more placeholders between
  curly braces and a context map of arbitrary values whose keys should
  match the placeholders, returns a new string where placeholders were
  replaced with values taken from matching keys in the context map."
  [^String template context]
  {:pre [template]}
  (string/replace template #"\{([^\}]+)\}" (fn [match]
                                             (str (get context (keyword (last match))
                                                       (first match))))))
