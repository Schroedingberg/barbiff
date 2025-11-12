(ns com.barbiff.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.barbiff.middleware :as mid]
            [com.barbiff.ui :as ui]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]
            [com.barbiff.domain.setlogging :as setlog]
            [com.barbiff.domain.events :as events]
            [com.barbiff.exercise :as exercise]
            [com.barbiff.template :as template]
            [rum.core :as rum]
            [xtdb.api :as xt]))

;; Workout Tracking

(def sample-plan
  {:microcycles
   [{:workouts
     [{:name "Upper" :day :monday
       :exercises [{:name "Bench Press" :sets [{:prescribed-reps 8 :prescribed-weight 100}
                                               {:prescribed-reps 8 :prescribed-weight 100}
                                               {:prescribed-reps 8 :prescribed-weight 100}]}
                   {:name "Barbell Row" :sets [{:prescribed-reps 8 :prescribed-weight 80}
                                               {:prescribed-reps 8 :prescribed-weight 80}]}]}
      {:name "Lower" :day :wednesday
       :exercises [{:name "Squat" :sets [{:prescribed-reps 5 :prescribed-weight 140}
                                         {:prescribed-reps 5 :prescribed-weight 140}
                                         {:prescribed-reps 5 :prescribed-weight 140}]}]}]}]})

;; Database Queries

(defn get-user-events [db uid]
  (sort-by :event/timestamp
           (q db '{:find (pull event [*]) :in [uid]
                   :where [[event :event/user uid] [event :event/type]]} uid)))

;; HTTP Handler

(defn log-event
  "HTTP handler for logging workout events.
   
   Event transformation pipeline:
   1. Parse HTTP params into simple map (:type, :exercise-id, :weight, :reps)
   2. Fetch user's existing events from DB
   3. Normalize DB events → domain events (strip DB namespace prefixes)
   4. Pass domain events to pure business logic (setlog/events-for-set-log)
   5. Business logic generates all required events (startup + set-logged)
   6. Convert domain events → DB events (add :db/doc-type, :event/ namespace)
   7. Persist DB events via biff/submit-tx
   
   Why normalize DB → domain → DB?
   - Business logic is pure and DB-agnostic
   - Domain events are simple maps: {:type :set-logged :exercise \"Bench\" :weight 100}
   - DB events have persistence metadata: {:db/doc-type :event :event/type :set-logged ...}
   - This separation allows testing domain logic without DB"
  [{:keys [session params biff/db] :as ctx}]
  (let [uid (:uid session)
        events (get-user-events db uid)
        {:keys [type exercise-id weight reps]} (events/parse-params params)]

    (cond
      ;; Handle set logging
      (and (= type :set-logged) exercise-id weight reps)
      (let [;; Convert DB events to domain events for business logic
            projection-events (->> events
                                   (map setlog/normalize-event)
                                   (map setlog/->projection-event))

            ;; Generate all needed events using pure domain logic
            domain-events (setlog/events-for-set-log projection-events
                                                     sample-plan
                                                     exercise-id
                                                     weight
                                                     reps)

            ;; Convert back to DB events for persistence
            ;; Use millisecond offsets to preserve ordering within batch
            db-events (map-indexed (fn [idx event]
                                     (events/->db-event uid event idx))
                                   domain-events)]

        (biff/submit-tx ctx db-events))

      ;; Handle workout completion
      (= type :workout-completed)
      (let [domain-event {:type :workout-completed}
            db-event (events/->db-event uid domain-event)]
        (biff/submit-tx ctx [db-event])))

    {:status 303 :headers {"location" "/app/workout"}}))

(defn get-active-plan
  "Get user's active plan structure. Falls back to sample-plan if none exists."
  [db user-id]
  (if-let [active-plan (ffirst (biff/q db
                                       '{:find [(pull ap [*])]
                                         :in [user-id]
                                         :where [[ap :active-plan/user user-id]]}
                                       user-id))]
    (template/build-template-structure db (:active-plan/template active-plan))
    sample-plan))  ; Fallback to hardcoded plan

(defn workout-page [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        events (get-user-events db (:uid session))
        projection-events (->> events
                               (map setlog/normalize-event)
                               (map setlog/->projection-event))
        progress (proj/build-state projection-events)
        plan (get-active-plan db (:uid session))
        merged-plan (proj/merge-plan-with-progress plan progress)]
    (ui/workout-page {:email email
                      :merged-plan merged-plan
                      :projection-events projection-events
                      :progress progress  ; Add for debugging
                      :events events})))

;; Exercise Library Handlers

(defn exercises-page [{:keys [session biff/db]}]
  (let [exercises (exercise/get-user-exercises db (:uid session))]
    (ui/exercises-page {:exercises exercises})))

(defn seed-exercises [{:keys [session] :as ctx}]
  (let [uid (:uid session)
        exercise-entities (exercise/seed-exercises-for-user uid)]
    (biff/submit-tx ctx exercise-entities)
    {:status 303 :headers {"location" "/app/exercises"}}))

;; Template Handlers

(defn templates-page [{:keys [session biff/db]}]
  (let [templates (template/get-user-templates db (:uid session))]
    (ui/templates-page {:templates templates})))

;; ============================================================================
;; Template Management Handlers
;; ============================================================================

(defn new-template-page [{:keys [session biff/db]}]
  (let [exercises (exercise/get-user-exercises db (:uid session))]
    (ui/new-template-page {:exercises exercises})))

(def workout-counter (atom 0))

(defn get-workout-form [{:keys [session biff/db]}]
  (let [exercises (exercise/get-user-exercises db (:uid session))
        workout-id (swap! workout-counter inc)]
    {:status 200
     :headers {"content-type" "text/html"}
     :body (rum/render-static-markup
            (ui/workout-form {:workout-id workout-id :exercises exercises}))}))

(defn delete-workout-form [_]
  {:status 200
   :headers {"content-type" "text/html"}
   :body ""})

(defn view-template [{:keys [biff/db path-params]}]
  (let [template-id (parse-uuid (:id path-params))
        template (template/get-template db template-id)
        workouts-with-exercises (template/get-template-with-exercises db template-id)]
    (ui/view-template-page {:template template
                            :workouts workouts-with-exercises})))

(defn- parse-workout-params
  "Parse workout form params into structured data."
  [params]
  (when-let [workout-map (:workout params)]
    (map (fn [[idx workout-data]]
           {:index (if (int? idx) idx (parse-long (str idx)))
            :name (:name workout-data)
            :day (keyword (:day workout-data))
            :exercise-ids (let [v (:exercises workout-data)]
                            (if (vector? v) v [v]))})
         (sort-by first workout-map))))

(defn create-template [{:keys [session params] :as ctx}]
  (let [uid (:uid session)
        template-name (:name params)
        workouts (parse-workout-params params)
        entities (template/create-template-with-workouts
                  {:user-id uid
                   :name template-name
                   :workouts workouts})]
    (biff/submit-tx ctx entities)
    {:status 303 :headers {"location" "/app/templates"}}))

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["/workout" {:get workout-page}]
            ["/workout/log-event" {:post log-event}]
            ["/workout/log-set" {:post log-event}]
            ["/exercises" {:get exercises-page}]
            ["/exercises/seed" {:post seed-exercises}]
            ["/templates" {:get templates-page}]
            ["/templates/new" {:get new-template-page}]
            ["/templates/create" {:post create-template}]
            ["/templates/workout-form" {:get get-workout-form}]
            ["/templates/workout-form/:workout-id" {:delete delete-workout-form}]
            ["/templates/view/:id" {:get view-template}]]})
