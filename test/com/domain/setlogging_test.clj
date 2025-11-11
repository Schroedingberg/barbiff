(ns com.domain.setlogging-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.barbiff.domain.setlogging :as setlog]))

;; Test Data

(def test-plan
  {:microcycles
   [{:workouts
     [{:name "Upper" :day :monday
       :exercises [{:name "Bench Press" :sets []}
                   {:name "Barbell Row" :sets []}]}
      {:name "Lower" :day :wednesday
       :exercises [{:name "Squat" :sets []}
                   {:name "Deadlift" :sets []}]}]}]})

;; Tests for find-active-workout

(deftest find-active-workout-test
  (testing "returns nil when no workouts started"
    (is (nil? (setlog/find-active-workout []))))

  (testing "returns workout-started event when active"
    (let [events [{:type :workout-started :name "Upper" :day :monday}]
          result (setlog/find-active-workout events)]
      (is (= "Upper" (:name result)))))

  (testing "returns nil when workout completed"
    (let [events [{:type :workout-started :name "Upper"}
                  {:type :workout-completed}]]
      (is (nil? (setlog/find-active-workout events)))))

  (testing "returns most recent active workout"
    (let [events [{:type :workout-started :name "Upper"}
                  {:type :workout-completed}
                  {:type :workout-started :name "Lower"}]
          result (setlog/find-active-workout events)]
      (is (= "Lower" (:name result))))))

;; Tests for exercise-belongs-to-workout?

(deftest exercise-belongs-to-workout?-test
  (testing "returns true when exercise belongs to workout"
    (is (setlog/exercise-belongs-to-workout?
         "Bench Press"
         {:name "Upper" :day :monday}
         test-plan)))

  (testing "returns false when exercise doesn't belong to workout"
    (is (not (setlog/exercise-belongs-to-workout?
              "Squat"
              {:name "Upper" :day :monday}
              test-plan))))

  (testing "returns nil for non-existent workout"
    (is (nil? (setlog/exercise-belongs-to-workout?
               "Bench Press"
               {:name "NonExistent" :day :monday}
               test-plan)))))

;; Tests for required-startup-events

(deftest required-startup-events-test
  (testing "generates microcycle-started and workout-started when empty"
    (let [events (setlog/required-startup-events [] test-plan "Bench Press")]
      (is (= 2 (count events)))
      (is (= :microcycle-started (:type (first events))))
      (is (= :workout-started (:type (second events))))
      (is (= "Upper" (:name (second events))))))

  (testing "only generates workout-started when microcycle active"
    (let [existing [{:type :microcycle-started}]
          events (setlog/required-startup-events existing test-plan "Bench Press")]
      (is (= 1 (count events)))
      (is (= :workout-started (:type (first events))))
      (is (= "Upper" (:name (first events))))))

  (testing "generates nothing when workout active and exercise belongs"
    (let [existing [{:type :microcycle-started}
                    {:type :workout-started :name "Upper" :day :monday}]
          events (setlog/required-startup-events existing test-plan "Bench Press")]
      (is (empty? events))))

  (testing "auto-completes and starts new workout when exercise doesn't belong"
    (let [existing [{:type :microcycle-started}
                    {:type :workout-started :name "Upper" :day :monday}]
          events (setlog/required-startup-events existing test-plan "Squat")]
      (is (= 2 (count events)))
      (is (= :workout-completed (:type (first events))))
      (is (= :workout-started (:type (second events))))
      (is (= "Lower" (:name (second events))))))

  (testing "handles transition from completed workout to new workout"
    (let [existing [{:type :microcycle-started}
                    {:type :workout-started :name "Upper" :day :monday}
                    {:type :workout-completed}]
          events (setlog/required-startup-events existing test-plan "Squat")]
      (is (= 1 (count events)))
      (is (= :workout-started (:type (first events))))
      (is (= "Lower" (:name (first events)))))))

;; Tests for events-for-set-log

(deftest events-for-set-log-test
  (testing "generates full startup + set-logged when starting fresh"
    (let [events (setlog/events-for-set-log [] test-plan "Bench Press" 100 8)]
      (is (= 3 (count events)))
      (is (= [:microcycle-started :workout-started :set-logged]
             (mapv :type events)))
      (is (= "Bench Press" (:exercise (last events))))
      (is (= 100 (:weight (last events))))
      (is (= 8 (:reps (last events))))))

  (testing "only generates set-logged when workout active"
    (let [existing [{:type :microcycle-started}
                    {:type :workout-started :name "Upper" :day :monday}]
          events (setlog/events-for-set-log existing test-plan "Bench Press" 100 8)]
      (is (= 1 (count events)))
      (is (= :set-logged (:type (first events))))))

  (testing "auto-transitions workouts when exercise changes"
    (let [existing [{:type :microcycle-started}
                    {:type :workout-started :name "Upper" :day :monday}
                    {:type :set-logged :exercise "Bench Press" :weight 100 :reps 8}]
          events (setlog/events-for-set-log existing test-plan "Squat" 140 5)]
      (is (= 3 (count events)))
      (is (= [:workout-completed :workout-started :set-logged]
             (mapv :type events)))
      (is (= "Lower" (:name (second events))))
      (is (= "Squat" (:exercise (last events)))))))

;; Tests for event ordering invariants

(deftest event-ordering-test
  (testing "startup events always come before set-logged"
    (let [events (setlog/events-for-set-log [] test-plan "Bench Press" 100 8)
          types (mapv :type events)]
      (is (= [:microcycle-started :workout-started :set-logged] types))
      (is (= :set-logged (:type (last events))))))

  (testing "microcycle-started always comes before workout-started"
    (let [events (setlog/events-for-set-log [] test-plan "Bench Press" 100 8)]
      (is (= :microcycle-started (:type (first events))))
      (is (= :workout-started (:type (second events))))
      (is (= "Upper" (:name (second events))))))

  (testing "workout-completed comes before new workout-started in transitions"
    (let [existing [{:type :microcycle-started}
                    {:type :workout-started :name "Upper" :day :monday}]
          events (setlog/events-for-set-log existing test-plan "Squat" 140 5)
          types (mapv :type events)]
      (is (= [:workout-completed :workout-started :set-logged] types))))

  (testing "set-logged is always the last event in the sequence"
    (let [;; Test various scenarios
          scenario1 (setlog/events-for-set-log [] test-plan "Bench Press" 100 8)
          scenario2 (setlog/events-for-set-log
                     [{:type :microcycle-started}]
                     test-plan "Bench Press" 100 8)
          scenario3 (setlog/events-for-set-log
                     [{:type :microcycle-started}
                      {:type :workout-started :name "Upper" :day :monday}]
                     test-plan "Squat" 140 5)]
      (is (= :set-logged (:type (last scenario1))))
      (is (= :set-logged (:type (last scenario2))))
      (is (= :set-logged (:type (last scenario3)))))))
