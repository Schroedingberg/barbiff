(ns com.barbiff.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.barbiff.middleware :as mid]
            [com.barbiff.ui :as ui]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]
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

(defn get-user-events [db uid]
  (sort-by :event/timestamp
           (q db '{:find (pull event [*]) :in [uid]
                   :where [[event :event/user uid] [event :event/type]]} uid)))

(defn find-workout-for-exercise [exercise-name]
  (some (fn [mc]
          (some #(when (some (fn [e] (= exercise-name (:name e))) (:exercises %)) %)
                (:workouts mc)))
        (:microcycles sample-plan)))

(defn has-active-session? [events session-type]
  (let [start-type (keyword (str (name session-type) "-started"))
        end-type (keyword (str (name session-type) "-completed"))
        events-vec (vec events)
        last-start (last (keep-indexed #(when (= start-type (:event/type %2)) %1) events-vec))
        last-end (last (keep-indexed #(when (= end-type (:event/type %2)) %1) events-vec))]
    (or (nil? last-end) (and last-start last-end (> last-start last-end)))))

(defn make-event [uid type & {:as extras}]
  (merge {:db/doc-type :event :event/user uid :event/timestamp :db/now :event/type type} extras))

(defn parse-params [params]
  {:type (keyword (get params "event/type"))
   :exercise (get params "event/exercise")
   :weight (when-let [w (get params "event/weight")] (when (seq w) (Double/parseDouble w)))
   :reps (when-let [r (get params "event/reps")] (when (seq r) (Integer/parseInt r)))
   :name (get params "event/name")
   :day (get params "event/day")})

(defn log-event [{:keys [session params biff/db] :as ctx}]
  (let [uid (:uid session)
        events (get-user-events db uid)
        {:keys [type exercise weight reps name day]} (parse-params params)

        events-to-submit
        (if (= type :set-logged)
          (let [workout (find-workout-for-exercise exercise)
                auto-events (cond-> []
                              (not (has-active-session? events :microcycle))
                              (conj (make-event uid :microcycle-started))
                              (and workout (not (has-active-session? events :workout)))
                              (conj (make-event uid :workout-started
                                                :event/name (:name workout)
                                                :event/day (name (:day workout)))))]
            (conj auto-events
                  (make-event uid type :event/exercise exercise :event/weight weight :event/reps reps)))
          [(make-event uid type :event/name name :event/day day)])]

    (biff/submit-tx ctx events-to-submit)
    {:status 303 :headers {"location" "/app/workout"}}))

(defn ->projection-event [e]
  (cond-> {:type (:event/type e)}
    (:event/name e) (assoc :name (:event/name e))
    (:event/day e) (assoc :day (:event/day e))
    (:event/exercise e) (assoc :exercise (:event/exercise e))
    (:event/weight e) (assoc :weight (:event/weight e))
    (:event/reps e) (assoc :reps (:event/reps e))))

(defn normalize-event [e]
  (-> e
      (update :event/type keyword)
      (update :event/day (fn [d] (when d (keyword d))))
      (update :event/weight (fn [w] (when w (Double/parseDouble (str w)))))
      (update :event/reps (fn [r] (when r (Integer/parseInt (str r)))))))

(defn workout-page [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        events (get-user-events db (:uid session))
        projection-events (->> events (map normalize-event) (map ->projection-event))
        merged-plan (proj/merge-plan-with-progress sample-plan (proj/build-state projection-events))]
    (ui/workout-page {:email email
                      :merged-plan merged-plan
                      :projection-events projection-events
                      :events events})))

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["/workout" {:get workout-page}]
            ["/workout/log-event" {:post log-event}]
            ["/workout/log-set" {:post log-event}]]})
