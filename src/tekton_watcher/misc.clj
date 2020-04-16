(ns tekton-watcher.misc
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as string])
  (:import java.io.File
           java.text.DecimalFormat
           [java.time Duration Instant]
           java.time.format.DateTimeFormatter
           java.time.temporal.TemporalQuery
           java.util.Locale))

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

(defn parse-input
  "Given a spec and an arbitrary data structure as the input, tries to
  conform the input using the supplied spec.

  Returns the conformed data or throws an exception if the data
  doesn't conform to the spec."
  [spec input]
  (let [result (s/conform spec input)]
    (if-not (s/invalid? result)
      result
      (throw (ex-info "Data doesn't conform to the spec"
                      (s/explain-data spec input))))))

(def ^:private instant-query
  "Implements java.time.temporal.TemporalQuery by delegating to
  java.time.Instant/from."
  (reify TemporalQuery
    (queryFrom [this temporal]
      (Instant/from temporal))))

(defn ^Duration duration
  "Returns a java.time.Duration object representing the duration of the
  task run in question."
  [task-run]
  (let [parse              (fn [^String instant]
                             (.. DateTimeFormatter ISO_INSTANT (parse instant instant-query)))
        ^String start-time (get-in task-run [:spec :status :startTime])
        ^String end-time   (get-in task-run [:spec :status :completionTime])]
    (Duration/between (parse start-time)
                      (parse end-time))))

(def ^:private formatter
  "Instance of java.text.Decimalformat used internally to format decimal
  values."
  (let [decimal-format (DecimalFormat/getInstance (Locale/ENGLISH))]
    (.applyPattern decimal-format "#.##")
    decimal-format))

(defn ^String display-duration
  "Returns a friendly representation of the duration of the task-run in question."
  [task-run]
  (let [format-duration (fn [value time-unit]
                          (str (.format formatter value) " "
                               (if (= (float value) 1.0)
                                 (name time-unit)
                                 (str (name time-unit) "s"))))
        millis          (.toMillis (duration task-run))]
    (cond
      (< millis 60000)   (format-duration (float (/ millis 1000)) :second)
      (< millis 3600000) (format-duration (float (/ millis 60000)) :minute)
      :else              (format-duration (float (/ millis 3600000)) :hour))))
