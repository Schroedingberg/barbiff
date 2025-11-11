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

(def gen-multi-workout-plan
  {:microcycles
   [{:workouts
     [{:name "Upper" :day :monday
       :exercises [{:name "Bench Press" :sets []}
                   {:name "Barbell Row" :sets []}]}
      {:name "Lower" :day :wednesday
       :exercises [{:name "Squat" :sets []}
                   {:name "Deadlift" :sets []}]}]}]})

(def gen-workout-event
  (gen/let [name (gen/elements ["Upper" "Lower"])
            day (gen/elements [:monday :wednesday])]
    {:type :workout-started :name name :day day}))

(def gen-event-sequence
  (gen/vector
   (gen/frequency
    [[5 (gen/return {:type :microcycle-started})]
     [10 gen-workout-event]
     [10 (gen/return {:type :workout-completed})]
     [20 (gen/let [ex gen-exercise w gen-weight r gen-reps]
           {:type :set-logged :exercise ex :weight w :reps r})]])
   0 20))

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

;; Property tests for workout transition logic

(defspec find-active-workout-returns-last-unfinished 100
  (prop/for-all [events gen-event-sequence]
                (let [result (setlog/find-active-workout events)
                      starts (filter #(= :workout-started (:type %)) events)
                      ends (filter #(= :workout-completed (:type %)) events)]
                  (if (> (count starts) (count ends))
                    ;; Should return the last started workout
                    (= result (last starts))
                    ;; Should return nil when all completed
                    (nil? result)))))

(defspec exercise-belongs-to-workout-is-consistent 100
  (prop/for-all []
                (let [plan gen-multi-workout-plan
                      upper-workout {:name "Upper" :day :monday}
                      lower-workout {:name "Lower" :day :wednesday}]
                  (and
                   ;; Upper exercises belong to Upper
                   (setlog/exercise-belongs-to-workout? "Bench Press" upper-workout plan)
                   (setlog/exercise-belongs-to-workout? "Barbell Row" upper-workout plan)
                   ;; Lower exercises belong to Lower
                   (setlog/exercise-belongs-to-workout? "Squat" lower-workout plan)
                   (setlog/exercise-belongs-to-workout? "Deadlift" lower-workout plan)
                   ;; Cross-checks are false
                   (not (setlog/exercise-belongs-to-workout? "Squat" upper-workout plan))
                   (not (setlog/exercise-belongs-to-workout? "Bench Press" lower-workout plan))))))

(defspec required-startup-events-never-starts-without-completing 100
  (prop/for-all [exercise (gen/elements ["Bench Press" "Squat"])]
                (let [plan gen-multi-workout-plan
                      ;; Scenario: Upper workout active, try to log exercise
                      events [{:type :microcycle-started}
                              {:type :workout-started :name "Upper" :day :monday}]
                      result (setlog/required-startup-events events plan exercise)
                      types (mapv :type result)]
                  (if (= exercise "Bench Press")
                    ;; Bench Press belongs to Upper - no transition needed
                    (empty? result)
                    ;; Squat belongs to Lower - must complete then start
                    (= [:workout-completed :workout-started] types)))))

(defspec events-for-set-log-maintains-workout-integrity 100
  (prop/for-all [weight gen-weight
                 reps gen-reps]
                (let [plan gen-multi-workout-plan
                      ;; Start Upper workout, log Bench Press
                      events1 (setlog/events-for-set-log [] plan "Bench Press" weight reps)
                      ;; Now try to log Squat (different workout)
                      events2 (setlog/events-for-set-log events1 plan "Squat" weight reps)]
                  (and
                   ;; First set: should start with microcycle and Upper workout
                   (= :microcycle-started (:type (first events1)))
                   (= "Upper" (:name (second events1)))
                   (= :set-logged (:type (last events1)))
                   ;; Second set: should complete Upper, start Lower, log set
                   (= :workout-completed (:type (first events2)))
                   (= "Lower" (:name (second events2)))
                   (= :set-logged (:type (last events2)))
                   (= "Squat" (:exercise (last events2)))))))

(defspec auto-transition-preserves-event-order 100
  (prop/for-all [weight gen-weight
                 reps gen-reps]
                (let [plan gen-multi-workout-plan
                      events (setlog/events-for-set-log
                              [{:type :microcycle-started}
                               {:type :workout-started :name "Upper" :day :monday}]
                              plan "Squat" weight reps)]
                  ;; Must be: workout-completed, workout-started, set-logged
                  (and (= 3 (count events))
                       (= :workout-completed (:type (nth events 0)))
                       (= :workout-started (:type (nth events 1)))
                       (= :set-logged (:type (nth events 2)))))))

(defspec set-logged-always-last 100
  (prop/for-all [exercise (gen/elements ["Bench Press" "Squat" "Deadlift"])
                 weight gen-weight
                 reps gen-reps
                 events gen-event-sequence]
                (let [plan gen-multi-workout-plan
                      result (setlog/events-for-set-log events plan exercise weight reps)]
                  ;; Invariant: set-logged is ALWAYS the last event
                  (= :set-logged (:type (last result))))))

(defspec startup-events-ordered-correctly 100
  (prop/for-all [exercise (gen/elements ["Bench Press" "Barbell Row" "Squat" "Deadlift"])]
                (let [plan gen-multi-workout-plan
                      events (setlog/events-for-set-log [] plan exercise 100 8)
                      types (mapv :type events)]
                  ;; When starting fresh: microcycle must come before workout
                  (if (> (count events) 1)
                    (let [microcycle-idx (.indexOf types :microcycle-started)
                          workout-idx (.indexOf types :workout-started)]
                      (if (and (>= microcycle-idx 0) (>= workout-idx 0))
                        (< microcycle-idx workout-idx)
                        true))
                    true))))