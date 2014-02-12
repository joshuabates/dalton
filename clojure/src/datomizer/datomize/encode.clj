(ns datomizer.datomize.encode
  "Encode entities by datomizing their data-structures."
  (:require [datomic.api :as d :refer [q]]
            [datomizer.datoms :refer :all]
            [datomizer.utility.byte-array :refer [byte-array-class]]
            [datomizer.utility.debug :refer :all]
            [datomizer.utility.misc :refer [ref-type]]))


(defmulti attribute-for-value
  "Datomizer attribute to use for an element value."
  class)

(defmethod attribute-for-value nil [_]
  :dmzr.element.value/nil)

(defmethod attribute-for-value java.lang.String [_]
  :dmzr.element.value/string)

(defmethod attribute-for-value java.lang.Long [_]
  :dmzr.element.value/long)

(defmethod attribute-for-value java.lang.Float [_]
  :dmzr.element.value/float)

(defmethod attribute-for-value java.lang.Double [_]
  :dmzr.element.value/double)

(defmethod attribute-for-value java.lang.Boolean [_]
  :dmzr.element.value/boolean)

(defmethod attribute-for-value java.util.Date [_]
  :dmzr.element.value/instant)

(defmethod attribute-for-value clojure.lang.Keyword [_]
  :dmzr.element.value/keyword)

(defmethod attribute-for-value java.util.List [_]
  :dmzr.element.value/vector)

(defmethod attribute-for-value java.util.Map [_]
  :dmzr.element.value/map)

(defmethod attribute-for-value java.math.BigDecimal [_]
  :dmzr.element.value/bigdec)

(defmethod attribute-for-value java.math.BigInteger [_]
  :dmzr.element.value/bigint)

(defmethod attribute-for-value byte-array-class [_]
  :dmzr.element.value/bytes)

(defmethod attribute-for-value :default
  [value]
  (throw (java.lang.IllegalArgumentException.
          (str "Marshalling not supported for type " (class value)))))


(defrecord Context [operation   ; What operation we're currently performing: :db/add or :db/retract
                    db          ; The database.
                    partition   ; The partition where we're putting new datoms. Used for tempids.
                    id          ; Id of the entity to which we are attaching a value.
                    attribute]) ; Attribute on the entity which will point to this value.

(defmulti encode
  "Encode a value as datoms.  Returns a pair of values: the values to assign to
  the parent attribute and a vec of datoms to transact."
  (fn [context value-to-encode]
    (ref-type (:db context) (:attribute context))))


(defn determine-element-id
  "Entity id of existing element, if any. Otherwise, a tempid."
  [context key-attribute key]
  (or (ffirst (q '[:find ?e
                   :in $ ?attribute ?parent-id ?key-attribute ?key
                   :where
                   [?parent-id ?attribute ?e]
                   [?e ?key-attribute ?key]]
                 (:db context)
                 (:attribute context)
                 (:id context)
                 key-attribute
                 key))
      (d/tempid (:partition context))))

(defn determine-empty-marker-id
  "Entity id of existing element, if any. Otherwise, a tempid."
  [context]
  (or (ffirst (q '[:find ?e
                   :in $ ?attribute ?parent-id
                   :where
                   [?parent-id ?attribute ?e]
                   [?e :dmzr/empty true]]
                 (:db context)
                 (:attribute context)
                 (:id context)))
      (d/tempid (:partition context))))

(defn determine-variant-id [context]
  "Entity id of existing variant, if any. Otherwise, a tempid."
  (or (ffirst (q '[:find ?e
                    :in $ ?attribute ?parent-id
                    :where
                    [?parent-id ?attribute ?e]]
                  (:db context)
                  (:attribute context)
                  (:id context)))
      (d/tempid (:partition context))))


(defn determine-key-attribute
  "Proper key attribute for elements added to the current value (if it's
  a collection)."
  [context]
  (case (ref-type (:db context) (:attribute context))
    :dmzr.type/map :dmzr.element.map/key
    :dmzr.type/vector :dmzr.element.vector/index
    :dmzr.type/variant nil
    :dmzr.type/edn nil
    nil nil))

(defn encode-value
  "Encode a value as a list of values/references and datoms to add or
  retract from the current context."
  [context value]
  (let [[encoded-value datoms] (encode context value)
        value-attribute (:attribute context)]
    [(:id context)
     (concat datoms
             (if (sequential? encoded-value)
               (map (fn [value]
                      [(:operation context) (:id context) value-attribute value])
                    encoded-value)
               [[(:operation context) (:id context) value-attribute encoded-value]]))]))

(defn encode-pair
  "Encode a key/value or index/value pairs as a list of references and
  datoms to add or retract to the current context."
  [context key-attribute k v]
  (let [id (determine-element-id context key-attribute k)
        value-attribute (attribute-for-value v)
        element-context (assoc context :id id :attribute value-attribute )
        key-datom [(:operation context) id key-attribute k]]
    (let [[values datoms] (encode-value element-context v)]
      [values (conj datoms key-datom)])))

(defn condense-elements
  "Merge a list of value - datom list pairs."
  [elements]
  (reduce (fn [[accumulated-values accumulated-datoms]
              [value datoms]]
            [(concat accumulated-values (flatten [value])) (concat accumulated-datoms datoms)])
          [[] []]
          elements))

(defn encode-empty [context]
  (let [id (determine-empty-marker-id context)]
    [id [[(:operation context) id :dmzr/empty true]]]))

(defmethod encode :dmzr.type/map
  [context value]
  (if (empty? value)
    (encode-empty context)
    (condense-elements (map (fn [[k, v]]
                              (encode-pair context :dmzr.element.map/key k v))
                            value))))

(defmethod encode :dmzr.type/vector [context value]
  (if (empty? value)
    (encode-empty context)
    (condense-elements (map (fn [[i, v]] (encode-pair context :dmzr.element.vector/index i v))
                            (zipmap (range) value)))))

(defmethod encode :dmzr.type/variant [context value]
  (let [id (determine-variant-id context)]
    (encode-value (assoc context :id id :attribute (attribute-for-value value)) value)))

(defmethod encode :dmzr.type/edn [context value]
  [(pr-str value) []])

(defmethod encode nil [_ value] ; attribute is not annotated with a datomizer type.
  (if (nil? value)
    [:NIL []]
    [value []]))

(defn encode-data [context data]
  (mapcat (fn [[attribute, value]]
            (second (encode-value (assoc context :attribute attribute) value)))
          data))

(defn datomize
  [db entity & {:keys [partition] :or {partition :db.part/user}}]
  (let [id (:db/id entity)
        data (dissoc entity :db/id)
        context (map->Context {:db db, :operation :db/add, :partition partition, :id id})
        retractions (rehearse-transaction db [[:db.fn/retractEntity id]])
        additions (map (partial resolve-idents db) (encode-data context data))]
    (remove-conflicts db additions retractions)))
