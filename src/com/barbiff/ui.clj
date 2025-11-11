(ns com.barbiff.ui
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [com.barbiff.settings :as settings]
            [com.biffweb :as biff]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.response :as ring-response]
            [rum.core :as rum]))

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn base [{:keys [::recaptcha] :as ctx} & body]
  (apply
   biff/base-html
   (-> ctx
       (merge #:base{:title settings/app-name
                     :lang "en-US"
                     :icon "/img/glider.png"
                     :description (str settings/app-name " Description")
                     :image "https://clojure.org/images/clojure-logo-120b.png"})
       (update :base/head (fn [head]
                            (concat [[:link {:rel "stylesheet" :href (static-path "/css/main.css")}]
                                     [:script {:src (static-path "/js/main.js")}]
                                     [:script {:src "https://unpkg.com/htmx.org@2.0.7"}]
                                     [:script {:src "https://unpkg.com/htmx-ext-ws@2.0.2/ws.js"}]
                                     [:script {:src "https://unpkg.com/hyperscript.org@0.9.14"}]
                                     (when recaptcha
                                       [:script {:src "https://www.google.com/recaptcha/api.js"
                                                 :async "async" :defer "defer"}])]
                                    head))))
   body))

(defn page [ctx & body]
  (base
   ctx
   [:.flex-grow]
   [:.p-3.mx-auto.max-w-screen-sm.w-full
    (when (bound? #'csrf/*anti-forgery-token*)
      {:hx-headers (cheshire/generate-string
                    {:x-csrf-token csrf/*anti-forgery-token*})})
    body]
   [:.flex-grow]
   [:.flex-grow]))

(defn on-error [{:keys [status ex] :as ctx}]
  {:status status
   :headers {"content-type" "text/html"}
   :body (rum/render-static-markup
          (page
           ctx
           [:h1.text-lg.font-bold
            (if (= status 404)
              "Page not found."
              "Something went wrong.")]))})

;; Workout UI Components

;; Common UI primitives
(defn card [class & content]
  (into [:.p-4.bg-white.border.rounded-lg {:class class}] content))

(defn input-number [attrs]
  [:input.text-sm.px-2.py-1.border.rounded (merge {:type "number"} attrs)])

(defn button [class text]
  [:button.px-3.py-1.text-xs.rounded.font-semibold {:type "submit" :class class} text])

(defn hidden [name value]
  [:input {:type "hidden" :name name :value value}])

(defn flex-between [& content]
  (into [:div.flex.justify-between.items-center] content))

(defn text-muted [text]
  [:span.text-sm.text-gray-600 text])

(defn spacer [size]
  [(keyword (str "div.space-y-" size))])

(defn render-items [items render-fn]
  (map-indexed (fn [i item] ^{:key i} (render-fn i item)) items))

;; Workout components
(defn set-status [set-data]
  (let [complete? (and (= (:prescribed-weight set-data) (:actual-weight set-data))
                       (= (:prescribed-reps set-data) (:actual-reps set-data))
                       (:actual-weight set-data) (:actual-reps set-data))]
    (cond complete? {:class "bg-green-50 border-green-300" :icon "✅"}
          (and (:actual-weight set-data) (:actual-reps set-data)) {:class "bg-yellow-50 border-yellow-300" :icon "💪"}
          :else {:class "bg-gray-50 border-gray-200" :icon nil})))

(defn format-set-stats [weight reps]
  (str weight "kg × " reps " reps"))

(defn set-input [exercise-name set-data]
  (biff/form
   {:action "/app/workout/log-set" :class "flex gap-2 items-center"}
   (hidden "event/type" "set-logged")
   (hidden "event/exercise" exercise-name)
   (input-number {:name "event/weight" :class "w-16" :step "0.5" :required true
                  :placeholder (str (:prescribed-weight set-data))
                  :defaultValue (:prescribed-weight set-data)})
   [:span.text-xs.text-gray-500 "kg ×"]
   (input-number {:name "event/reps" :class "w-12" :required true
                  :placeholder (str (:prescribed-reps set-data))
                  :defaultValue (:prescribed-reps set-data)})
   (button "bg-green-600 hover:bg-green-700 text-white" "✓")))

(defn render-set [_idx exercise-name set-data]
  (let [{:keys [class icon]} (set-status set-data)
        has-actual? (and (:actual-weight set-data) (:actual-reps set-data))]
    [:.p-3.rounded.border {:class class}
     [:div.flex.justify-between.items-start.gap-4
      [:div.flex-1
       (when (or (:prescribed-weight set-data) (:prescribed-reps set-data))
         [:div.text-sm.text-gray-600.mb-1
          "📋 Planned: " (format-set-stats (:prescribed-weight set-data) (:prescribed-reps set-data))])
       (if has-actual?
         [:div.font-semibold
          (when icon [:<> icon " "])
          "Actual: " (format-set-stats (:actual-weight set-data) (:actual-reps set-data))]
         [:div.text-sm.text-gray-400.italic "Click ✓ to log →"])]
      (when-not has-actual? [:div (set-input exercise-name set-data)])]]))

(defn render-exercise [exercise]
  (card "mb-4 border-gray-200"
        [:h4.text-lg.font-semibold.mb-3 (:name exercise)]
        (into (spacer 2) (render-items (:sets exercise) #(render-set %1 (:name exercise) %2)))))

(defn render-workout [workout]
  (card "mb-6 bg-gray-50 border-gray-300 border-2"
        (flex-between [:h3.text-xl.font-bold (:name workout)]
                      (text-muted (str "Day: " (name (or (:day workout) :unknown)))))
        [:div.mb-4]
        (into (spacer 3) (render-items (:exercises workout) (fn [_ e] (render-exercise e))))))

(defn render-microcycle [idx microcycle]
  (card "mb-8 p-6 rounded-xl shadow-md"
        [:h2.text-2xl.font-bold.mb-6 "Microcycle " (inc idx)]
        (into (spacer 4) (render-items (:workouts microcycle) (fn [_ w] (render-workout w))))))

(defn render-projection [merged-plan]
  [:div
   [:h2.text-2xl.font-bold.mb-6 "Training Plan with Progress"]
   (into (spacer 6) (render-items (:microcycles merged-plan) render-microcycle))])

(defn control-button [type label icon]
  (biff/form
   {:action "/app/workout/log-event" :class "inline"}
   (hidden "event/type" type)
   [:button.btn.bg-blue-600.hover:bg-blue-700 {:type "submit"} icon " " label]))

(defn workout-controls []
  (card "mb-6 bg-blue-50 shadow"
        [:h3.text-lg.font-semibold.mb-3 "Session Controls"]
        [:p.text-sm.text-gray-600.mb-3 "Just log sets by clicking ✓ below. Workouts and microcycles start automatically!"]
        [:div.flex.flex-wrap.gap-3
         (control-button "workout-completed" "Complete Workout" "✅")
         (control-button "microcycle-completed" "Complete Microcycle" "🏆")]))

(defn render-event-field [label value & [suffix]]
  (when value [:div.text-gray-700 label ": " value suffix]))

(defn render-event [event]
  (card "text-sm border-gray-200"
        [:div.flex.justify-between
         [:span.font-mono.text-blue-600 (:event/type event)]
         [:span.text-gray-500 (biff/format-date (:event/timestamp event) "HH:mm:ss")]]
        (render-event-field "Name" (:event/name event))
        (render-event-field "Day" (:event/day event))
        (render-event-field "Exercise" (:event/exercise event))
        (render-event-field "Weight" (:event/weight event) "kg")
        (render-event-field "Reps" (:event/reps event))))

(defn workout-page [{:keys [email merged-plan projection-events events]}]
  (page
   {}
   [:div.max-w-6xl.mx-auto
    [:div.flex.justify-between.items-center.mb-6
     [:h1.text-3xl.font-bold "Workout Tracker"]
     [:div
      [:span.text-gray-600 email " | "]
      (biff/form {:action "/auth/signout" :class "inline"}
                 [:button.text-blue-500.hover:text-blue-800 {:type "submit"} "Sign out"])]]

    (workout-controls)

    [:details.mb-4.p-4.bg-yellow-50.rounded
     [:summary.cursor-pointer.font-semibold "Debug: View Projection Events"]
     [:pre.text-xs.overflow-auto (pr-str projection-events)]]

    (card "p-6 rounded-xl shadow-md mb-8"
          (render-projection merged-plan))

    [:details.mb-8
     [:summary.cursor-pointer.text-lg.font-semibold.p-4.bg-gray-100.rounded.hover:bg-gray-200
      "📜 View Raw Event Log (" (count events) " events)"]
     (card "mt-4 p-6 rounded-xl shadow-md"
           [:div.space-y-2
            (if (empty? events)
              [:p.text-gray-500 "No events yet"]
              (render-items (reverse events) (fn [_ e] (render-event e))))])]]))
