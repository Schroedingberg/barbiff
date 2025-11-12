(ns tasks
  (:require [com.biffweb.tasks :as tasks]
            [clojure.test :as test]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn hello
  "Says 'Hello'"
  []
  (println "Hello"))

(defn test-all
  "Runs all tests - automatically discovers and loads all *-test namespaces"
  []
  (println "Running all tests...")
  (let [test-dir (io/file "test")
        test-files (->> (file-seq test-dir)
                        (filter #(.isFile %))
                        (filter #(str/ends-with? (.getName %) "_test.clj"))
                        (map #(-> (.getPath %)
                                  (str/replace #"^test/" "")
                                  (str/replace #"\.clj$" "")
                                  (str/replace #"/" ".")
                                  symbol)))]
    (println (str "Discovered " (count test-files) " test namespaces"))
    (doseq [ns test-files]
      (try
        (require ns)
        (catch Exception e
          (println (str "Warning: Could not load " ns ": " (.getMessage e))))))
    ;; Run all tests
    (test/run-all-tests)))

(defn test-domain
  "Runs only domain tests"
  []
  (println "Running domain tests...")
  (require 'com.domain.hardcorefunctionalprojection-test)
  (test/run-tests 'com.domain.hardcorefunctionalprojection-test))

(defn test-property
  "Runs property-based tests"
  []
  (println "Running property-based tests...")
  (require 'com.domain.setlogging-property-test)
  (require 'com.domain.workout-filtering-property-test)
  (test/run-tests 'com.domain.setlogging-property-test
                  'com.domain.workout-filtering-property-test))

(defn test-workout-filtering
  "Runs workout-filtering tests (both example and property-based)"
  []
  (println "Running workout-filtering tests...")
  (require 'com.domain.workout-filtering-test)
  (require 'com.domain.workout-filtering-property-test)
  (test/run-tests 'com.domain.workout-filtering-test
                  'com.domain.workout-filtering-property-test))

;; Tasks should be vars (#'hello instead of hello) so that `clj -M:dev help` can
;; print their docstrings.
(def custom-tasks
  {"hello" #'hello
   "test" #'test-all
   "test-all" #'test-all
   "test-domain" #'test-domain
   "test-property" #'test-property
   "test-workout-filtering" #'test-workout-filtering})

(def tasks (merge tasks/tasks custom-tasks))
