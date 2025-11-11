(ns com.barbiff.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.barbiff.middleware :as mid]
            [com.barbiff.ui :as ui]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]
            [com.barbiff.domain.setlogging :as setlog]
            [com.barbiff.domain.events :as events]
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

    (when (and (= type :set-logged) exercise-id weight reps)
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
            db-events (map #(events/->db-event uid %) domain-events)]

        (biff/submit-tx ctx db-events)))

    {:status 303 :headers {"location" "/app/workout"}}))

(defn workout-page [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        events (get-user-events db (:uid session))
        projection-events (->> events
                               (map setlog/normalize-event)
                               (map setlog/->projection-event))
        progress (proj/build-state projection-events)
        merged-plan (proj/merge-plan-with-progress sample-plan progress)]
    (ui/workout-page {:email email
                      :merged-plan merged-plan
                      :projection-events projection-events
                      :progress progress  ; Add for debugging
                      :events events})))

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["/workout" {:get workout-page}]
            ["/workout/log-event" {:post log-event}]
            ["/workout/log-set" {:post log-event}]]})
