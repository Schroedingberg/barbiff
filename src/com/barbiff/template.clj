(ns com.barbiff.template
  "Template management - CRUD operations for workout templates."
  (:require [com.biffweb :as biff]))

;; ============================================================================
;; Template Creation
;; ============================================================================

(defn make-template
  "Create a new template entity."
  [{:keys [name user-id]}]
  {:xt/id (random-uuid)
   :db/doc-type :template
   :template/name name
   :template/user user-id
   :template/created-at (java.util.Date.)})

(defn make-workout
  "Create a workout for a template."
  [{:keys [template-id name day order]}]
  {:xt/id (random-uuid)
   :db/doc-type :workout
   :workout/template template-id
   :workout/name name
   :workout/day day
   :workout/order order})

(defn make-workout-exercise
  "Link an exercise to a workout with optional starting sets."
  [{:keys [workout-id exercise-id order starting-sets]}]
  (cond-> {:xt/id (random-uuid)
           :db/doc-type :workout-exercise
           :workout-exercise/workout workout-id
           :workout-exercise/exercise exercise-id
           :workout-exercise/order order}
    starting-sets (assoc :workout-exercise/starting-sets starting-sets)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn get-user-templates
  "Get all templates for a user."
  [db user-id]
  (map first
       (biff/q db
               '{:find [(pull template [*])]
                 :in [user-id]
                 :where [[template :template/user user-id]]}
               user-id)))

(defn get-template
  "Get a specific template by id."
  [db template-id]
  (biff/lookup db :xt/id template-id))

(defn get-template-workouts
  "Get all workouts for a template, ordered."
  [db template-id]
  (->> (biff/q db
               '{:find [(pull workout [*])]
                 :in [template-id]
                 :where [[workout :workout/template template-id]]}
               template-id)
       (map first)
       (sort-by :workout/order)))

(defn get-workout-exercises
  "Get all exercises for a workout, ordered."
  [db workout-id]
  (->> (biff/q db
               '{:find [(pull we [*])]
                 :in [workout-id]
                 :where [[we :workout-exercise/workout workout-id]]}
               workout-id)
       (map first)
       (sort-by :workout-exercise/order)))

(defn get-template-with-exercises
  "Get template workouts enriched with exercise entities."
  [db template-id]
  (let [workouts (get-template-workouts db template-id)]
    (map (fn [workout]
           (let [workout-exercises (get-workout-exercises db (:xt/id workout))
                 exercises (map (fn [we]
                                  (biff/lookup db :xt/id (:workout-exercise/exercise we)))
                                workout-exercises)]
             (assoc workout :exercises exercises)))
         workouts)))

;; ============================================================================
;; Template Creation with Workouts
;; ============================================================================

(defn create-template-with-workouts
  "Create a complete template with workouts and exercises in one transaction.
   Takes {:user-id uid :name str :workouts [{:index int :name str :day keyword :exercise-ids [uuid]}]}"
  [{:keys [user-id name workouts]}]
  (let [template-entity (make-template {:name name :user-id user-id})
        template-id (:xt/id template-entity)

        workout-entities
        (mapcat (fn [{:keys [index name day exercise-ids]}]
                  (let [workout-entity (make-workout {:template-id template-id
                                                      :name name
                                                      :day day
                                                      :order index})
                        workout-id (:xt/id workout-entity)
                        exercise-entities (map-indexed
                                           (fn [idx ex-id]
                                             (make-workout-exercise {:workout-id workout-id
                                                                     :exercise-id (parse-uuid ex-id)
                                                                     :order idx
                                                                     :starting-sets 3}))
                                           exercise-ids)]
                    (cons workout-entity exercise-entities)))
                workouts)]
    (cons template-entity workout-entities)))

;; ============================================================================
;; Building the in-memory structure
;; ============================================================================

(defn build-template-structure
  "Load template from DB and build the nested structure in memory.
   Returns the sample-plan compatible structure."
  [db template-id]
  (when (get-template db template-id)
    (let [workouts (get-template-workouts db template-id)]
      {:microcycles
       [{:workouts
         (for [workout workouts]
           (let [workout-exercises (get-workout-exercises db (:xt/id workout))]
             {:name (:workout/name workout)
              :day (:workout/day workout)
              :exercises
              (for [we workout-exercises]
                (let [exercise (biff/lookup db :xt/id (:workout-exercise/exercise we))
                      num-sets (or (:workout-exercise/starting-sets we) 3)]
                  {:name (:exercise/name exercise)
                   :sets (vec (repeat num-sets {:prescribed-reps nil
                                                :prescribed-weight nil}))}))}))}]})))
