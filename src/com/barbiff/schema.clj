(ns com.barbiff.schema)

(def schema
  {:user/id :uuid
   :user [:map {:closed true}
          [:xt/id          :user/id]
          [:user/email     :string]
          [:user/joined-at inst?]]

   :event/id :uuid
   :event [:map {:closed true}
           [:xt/id                           :event/id]
           [:event/user                      :user/id]
           [:event/type                      :keyword]
           [:event/timestamp                 inst?]
           [:event/name {:optional true}     :string]
           [:event/day {:optional true}      :string]
           [:event/exercise {:optional true} :string]
           [:event/weight {:optional true}   :double]
           [:event/reps {:optional true}     :int]]

   :exercise/id :uuid
   :exercise [:map {:closed true}
              [:xt/id                                        :exercise/id]
              [:exercise/name                                :string]
              [:exercise/muscle-groups                       [:vector :keyword]]
              [:exercise/equipment {:optional true}          [:vector :keyword]]
              [:exercise/notes {:optional true}              :string]
              [:exercise/max-recoverable-sets {:optional true} :int]
              [:exercise/user                                :user/id]
              [:exercise/created-at                          inst?]]

   ;; Template: Blueprint for a microcycle (one week of training)
   ;; Stored flat - workouts are separate entities
   :template/id :uuid
   :template [:map {:closed true}
              [:xt/id                              :template/id]
              [:template/name                      :string]
              [:template/user                      :user/id]
              [:template/created-at                inst?]]

   ;; Workout: Part of a template, defines one training session
   :workout/id :uuid
   :workout [:map {:closed true}
             [:xt/id                              :workout/id]
             [:workout/template                   :template/id]
             [:workout/name                       :string]
             [:workout/day                        :keyword]  ; :monday, :tuesday, etc.
             [:workout/order                      :int]]     ; Display order within template

   ;; Workout Exercise: Links exercises to workouts with optional starting sets
   :workout-exercise/id :uuid
   :workout-exercise [:map {:closed true}
                      [:xt/id                                   :workout-exercise/id]
                      [:workout-exercise/workout                :workout/id]
                      [:workout-exercise/exercise               :exercise/id]
                      [:workout-exercise/order                  :int]  ; Order within workout
                      [:workout-exercise/starting-sets {:optional true} :int]]  ; Initial set count

   ;; Active Plan: Tracks which template the user is currently following
   ;; The actual mesocycle structure (with prescribed values) is built on-the-fly
   ;; by combining: template structure + user events + progression algorithm
   :active-plan/id :uuid
   :active-plan [:map {:closed true}
                 [:xt/id                              :active-plan/id]
                 [:active-plan/user                   :user/id]
                 [:active-plan/template               :template/id]
                 [:active-plan/started-at             inst?]
                 [:active-plan/current-microcycle     :int]]})  ; Which week they're on

(def module
  {:schema schema})
