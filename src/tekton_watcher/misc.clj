(ns tekton-watcher.misc
  (:require [clojure.string :as string]))

(defn render
  [template context]
  {:pre [template]}
  (string/replace template #"\{([^\}]+)\}" (fn [match]
                                             (get context (keyword (last match))
                                                  (first match)))))

(defn map-vals
  "Applies the function f to each value in the map m and return the
  resulting map."
  [f m]
  (into {} (map (fn [[k v]]
                  [k (f v)]) m)))
