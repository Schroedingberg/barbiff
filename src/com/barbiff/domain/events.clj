(ns com.barbiff.domain.events
  "Event conversion utilities for transforming between different representations.
   
   This namespace handles two key transformations:
   
   1. HTTP params → Domain maps (parse-params)
      HTTP form data with string keys to domain maps with keyword keys
   
   2. Domain events → DB events (->db-event)
      Pure domain events to XTDB-ready documents with metadata
   
   These conversions sit at the boundaries of the system:
   - parse-params: Entry point (HTTP → domain)
   - ->db-event: Exit point (domain → persistence)")

;; HTTP to Domain Conversion

(defn parse-params
  "Parse HTTP form parameters into a domain event map.
   
   Converts string keys to keywords and parses numeric values.
   
   Example:
     {\"event/type\" \"set-logged\"
      \"event/exercise\" \"Bench Press\"
      \"event/weight\" \"100\"
      \"event/reps\" \"8\"}
     =>
     {:type :set-logged
      :exercise-id \"Bench Press\"
      :weight 100.0
      :reps 8}"
  [params]
  {:type (keyword (get params "event/type"))
   :exercise-id (get params "event/exercise")
   :weight (when-let [w (get params "event/weight")] (when (seq w) (Double/parseDouble w)))
   :reps (when-let [r (get params "event/reps")] (when (seq r) (Integer/parseInt r)))})

;; Domain to Database Conversion

(defn ->db-event
  "Convert a domain event to a database event document for persistence.
   
   Adds DB-specific metadata to pure domain events before storage.
   Optionally accepts an offset-ms to slightly adjust timestamp for ordering
   multiple events in the same logical transaction.
   
   Example:
     {:type :set-logged 
      :exercise \"Bench Press\" 
      :weight 100 
      :reps 8}
     =>
     {:db/doc-type :event
      :event/user <uid>
      :event/timestamp <instant>
      :event/type :set-logged
      :event/exercise \"Bench Press\"
      :event/weight 100
      :event/reps 8}
   
   The :event/ namespace prefix allows XTDB queries like:
     {:find [(pull event [*])]
      :where [[event :event/user uid] [event :event/type]]}"
  ([uid domain-event]
   (->db-event uid domain-event 0))
  ([uid domain-event offset-ms]
   (let [base {:db/doc-type :event
               :event/user uid
               :event/timestamp (java.util.Date. (+ (System/currentTimeMillis) offset-ms))
               :event/type (:type domain-event)}]
     (cond-> base
       (:exercise domain-event) (assoc :event/exercise (:exercise domain-event))
       (:weight domain-event) (assoc :event/weight (:weight domain-event))
       (:reps domain-event) (assoc :event/reps (:reps domain-event))
       (:name domain-event) (assoc :event/name (:name domain-event))
       (:day domain-event) (assoc :event/day (name (:day domain-event)))))))