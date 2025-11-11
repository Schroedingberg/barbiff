(ns com.domain.setlogging-property-test
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [com.barbiff.domain.setlogging :as setlog]))

;; Generators

(def gen-exercise (gen/elements ["Bench Press" "Squat" "Deadlift"]))
(def gen-weight (gen/choose 40 200))
(def gen-reps (gen/choose 1 20))
(def gen-day (gen/elements [:monday :tuesday :wednesday]))

(def gen-plan
  (gen/let [exercises (gen/vector gen-exercise 1 3)]
    {:microcycles
     [{:workouts
       [{:name "Upper"
         :day :monday
         :exercises (mapv (fn [ex] {:name ex :sets []}) exercises)}]}]}))

;; Properties

(defspec make-event-has-type 100
  (prop/for-all [t (gen/elements [:microcycle-started :workout-started :set-logged])]
                (= t (:type (setlog/make-event t {})))))

(defspec active-session-empty-is-false 100
  (prop/for-all [session-type (gen/elements [:microcycle :workout])]
                (not (setlog/active-session? [] session-type))))

(defspec active-session-with-start-is-true 100
  (prop/for-all [session-type (gen/elements [:microcycle :workout])]
                (let [event {:type (keyword (str (name session-type) "-started"))}]
                  (setlog/active-session? [event] session-type))))

(defspec active-session-with-complete-pair-is-false 100
  (prop/for-all [session-type (gen/elements [:microcycle :workout])]
                (let [start {:type (keyword (str (name session-type) "-started"))}
                      end {:type (keyword (str (name session-type) "-completed"))}]
                  (not (setlog/active-session? [start end] session-type)))))

(defspec find-workout-returns-map-or-nil 100
  (prop/for-all [plan gen-plan
                 exercise gen/string-ascii]
                (let [result (setlog/find-workout-for-exercise plan exercise)]
                  (or (nil? result) (map? result)))))

(defspec events-for-set-log-always-ends-with-set-logged 100
  (prop/for-all [plan gen-plan
                 exercise gen-exercise
                 weight gen-weight
                 reps gen-reps]
                (let [events (setlog/events-for-set-log [] plan exercise weight reps)]
                  (= :set-logged (:type (last events))))))

(defspec events-for-set-log-set-has-correct-data 100
  (prop/for-all [plan gen-plan
                 exercise gen-exercise
                 weight gen-weight
                 reps gen-reps]
                (let [events (setlog/events-for-set-log [] plan exercise weight reps)
                      set-event (last events)]
                  (and (= exercise (:exercise set-event))
                       (= weight (:weight set-event))
                       (= reps (:reps set-event))))))

(defspec projection-event-has-type 100
  (prop/for-all [t (gen/elements [:microcycle-started :workout-started :set-logged])]
                (let [db-event {:event/type t}
                      proj-event (setlog/->projection-event db-event)]
                  (= t (:type proj-event)))))
