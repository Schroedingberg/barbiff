(ns com.barbiff.template-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.barbiff.template :as template]
            [xtdb.api :as xt]))

(defn with-test-db [f]
  (with-open [node (test-xtdb-node {})]
    (f {:node node :db (xt/db node)})))

(deftest create-template-with-workouts-test
  (testing "creates template with workouts and exercises"
    (let [user-id (random-uuid)
          ex1-id (random-uuid)
          ex2-id (random-uuid)
          ex3-id (random-uuid)
          workouts [{:index 0
                     :name "Upper Body"
                     :day :monday
                     :exercise-ids [(str ex1-id) (str ex2-id)]}
                    {:index 1
                     :name "Lower Body"
                     :day :wednesday
                     :exercise-ids [(str ex3-id)]}]
          entities (template/create-template-with-workouts
                    {:user-id user-id
                     :name "Test Template"
                     :workouts workouts})]

      (testing "returns correct number of entities"
        (is (= 6 (count entities)))
        (is (= 1 (count (filter #(= :template (:db/doc-type %)) entities))))
        (is (= 2 (count (filter #(= :workout (:db/doc-type %)) entities))))
        (is (= 3 (count (filter #(= :workout-exercise (:db/doc-type %)) entities)))))

      (testing "template entity has correct structure"
        (let [template (first (filter #(= :template (:db/doc-type %)) entities))]
          (is (= "Test Template" (:template/name template)))
          (is (= user-id (:template/user template)))
          (is (inst? (:template/created-at template)))
          (is (uuid? (:xt/id template)))))

      (testing "workout entities have correct structure and order"
        (let [workouts (sort-by :workout/order (filter #(= :workout (:db/doc-type %)) entities))
              template-id (:xt/id (first (filter #(= :template (:db/doc-type %)) entities)))]
          (is (= 2 (count workouts)))
          (is (= "Upper Body" (:workout/name (first workouts))))
          (is (= :monday (:workout/day (first workouts))))
          (is (= 0 (:workout/order (first workouts))))
          (is (int? (:workout/order (first workouts))))
          (is (= template-id (:workout/template (first workouts))))

          (is (= "Lower Body" (:workout/name (second workouts))))
          (is (= :wednesday (:workout/day (second workouts))))
          (is (= 1 (:workout/order (second workouts))))
          (is (int? (:workout/order (second workouts))))))

      (testing "workout-exercise entities have correct structure"
        (let [workout-exercises (filter #(= :workout-exercise (:db/doc-type %)) entities)
              workout-ids (set (map :xt/id (filter #(= :workout (:db/doc-type %)) entities)))]
          (is (= 3 (count workout-exercises)))
          (doseq [we workout-exercises]
            (is (uuid? (:workout-exercise/workout we)))
            (is (contains? workout-ids (:workout-exercise/workout we)))
            (is (uuid? (:workout-exercise/exercise we)))
            (is (int? (:workout-exercise/order we)))
            (is (= 3 (:workout-exercise/starting-sets we)))))))))

(deftest get-template-with-exercises-test
  (testing "enriches workouts with exercise entities"
    (with-test-db
      (fn [{:keys [node]}]
        (let [user-id (random-uuid)
              ex1-id (random-uuid)
              ex2-id (random-uuid)

              ;; Create exercises
              exercise1 {:xt/id ex1-id
                         :db/doc-type :exercise
                         :exercise/name "Bench Press"
                         :exercise/user user-id}
              exercise2 {:xt/id ex2-id
                         :db/doc-type :exercise
                         :exercise/name "Squat"
                         :exercise/user user-id}

              ;; Create template with workouts
              entities (template/create-template-with-workouts
                        {:user-id user-id
                         :name "Test Template"
                         :workouts [{:index 0
                                     :name "Day 1"
                                     :day :monday
                                     :exercise-ids [(str ex1-id) (str ex2-id)]}]})

              template-id (:xt/id (first (filter #(= :template (:db/doc-type %)) entities)))

              ;; Submit all entities
              _ (xt/submit-tx node (vec (for [e (concat [exercise1 exercise2] entities)]
                                          [::xt/put e])))
              _ (xt/sync node)

              ;; Get enriched workouts
              db (xt/db node)
              workouts (template/get-template-with-exercises db template-id)]

          (is (= 1 (count workouts)))
          (is (= "Day 1" (:workout/name (first workouts))))
          (is (= 2 (count (:exercises (first workouts)))))
          (is (every? #(contains? % :exercise/name) (:exercises (first workouts))))
          (is (= #{"Bench Press" "Squat"}
                 (set (map :exercise/name (:exercises (first workouts)))))))))))

(deftest build-template-structure-test
  (testing "builds sample-plan compatible structure"
    (with-test-db
      (fn [{:keys [node]}]
        (let [user-id (random-uuid)
              ex1-id (random-uuid)

              ;; Create exercise
              exercise {:xt/id ex1-id
                        :db/doc-type :exercise
                        :exercise/name "Bench Press"
                        :exercise/user user-id}

              ;; Create template
              entities (template/create-template-with-workouts
                        {:user-id user-id
                         :name "Test Template"
                         :workouts [{:index 0
                                     :name "Upper"
                                     :day :monday
                                     :exercise-ids [(str ex1-id)]}]})

              template-id (:xt/id (first (filter #(= :template (:db/doc-type %)) entities)))

              ;; Submit all entities
              _ (xt/submit-tx node (vec (for [e (concat [exercise] entities)]
                                          [::xt/put e])))
              _ (xt/sync node)

              ;; Build structure
              db (xt/db node)
              structure (template/build-template-structure db template-id)]

          (testing "has correct top-level structure"
            (is (contains? structure :microcycles))
            (is (= 1 (count (:microcycles structure)))))

          (testing "microcycle has workouts"
            (let [microcycle (first (:microcycles structure))]
              (is (contains? microcycle :workouts))
              (is (= 1 (count (:workouts microcycle))))))

          (testing "workout has correct structure"
            (let [workout (first (:workouts (first (:microcycles structure))))]
              (is (= "Upper" (:name workout)))
              (is (= :monday (:day workout)))
              (is (= 1 (count (:exercises workout))))))

          (testing "exercise has sets with nil prescribed values"
            (let [exercise (first (:exercises (first (:workouts (first (:microcycles structure))))))]
              (is (= "Bench Press" (:name exercise)))
              (is (= 3 (count (:sets exercise))))
              (doseq [set (:sets exercise)]
                (is (nil? (:prescribed-reps set)))
                (is (nil? (:prescribed-weight set)))))))))))