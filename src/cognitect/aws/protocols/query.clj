;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.query
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.service :as service]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

; ----------------------------------------------------------------------------------------
;; Serializer
;; ----------------------------------------------------------------------------------------

(defn serialized-name
  [shape default]
  (or (:locationName shape)
      default))

(defmulti serialize
  (fn [shape _args _serialized _prefix] (:type shape)))

(defn prefix-assoc
  [serialized prefix val]
  (assoc serialized (str/join "." prefix) val))

(defmethod serialize :default
  [_shape args serialized prefix]
  (prefix-assoc serialized prefix (str args)))

(defmethod serialize "structure"
  [shape args serialized prefix]
  (let [args (util/with-defaults shape args)]
    (reduce (fn [serialized k]
              (let [member-shape (shape/member-shape shape k)
                    member-name  (serialized-name member-shape (name k))]
                (if (contains? args k)
                  (serialize member-shape (k args) serialized (conj prefix member-name))
                  serialized)))
            serialized
            (keys (:members shape)))))

(defmethod serialize "list"
  [shape args serialized prefix]
  (if (empty? args)
    (prefix-assoc serialized prefix "")
    (let [member-shape (shape/list-member-shape shape)
          list-prefix (if (:flattened shape)
                        (conj (vec (butlast prefix)) (serialized-name member-shape (last prefix)))
                        (conj prefix (serialized-name member-shape "member")))]
      (reduce (fn [serialized [i member]]
                (serialize member-shape member serialized (conj list-prefix (str i))))
              serialized
              (map-indexed (fn [i member] [(inc i) member]) args)))))

(defmethod serialize "map"
  [shape args serialized prefix]
  (let [map-prefix (if (:flattened shape) prefix (conj prefix "entry"))
        key-shape (shape/key-shape shape)
        key-suffix (serialized-name key-shape "key")
        value-shape (shape/value-shape shape)
        value-suffix (serialized-name value-shape "value")]
    (reduce (fn [serialized [i k v]]
              (as-> serialized $
                (serialize key-shape (name k) $ (conj map-prefix (str i) key-suffix))
                (serialize value-shape v $ (conj map-prefix (str i) value-suffix))))
            serialized
            (map-indexed (fn [i [k v]] [(inc i) k v]) args))))

(defmethod serialize "blob"
  [_shape args serialized prefix]
  (prefix-assoc serialized prefix (util/base64-encode args)))

(defmethod serialize "timestamp" [shape args serialized prefix]
  (prefix-assoc serialized prefix (shape/format-date shape
                                                     args
                                                     (partial util/format-date util/iso8601-date-format))))

(defmethod serialize "boolean"
  [_shape args serialized prefix]
  (prefix-assoc serialized prefix (if args "true" "false")))

(defn build-query-http-request
  [serialize service {:keys [op request]}]
  (let [operation   (get-in service [:operations op])
        input-shape (service/shape service (:input operation))
        params      {"Action"  (name op)
                     "Version" (get-in service [:metadata :apiVersion])}]
    {:request-method :post
     :scheme         :https
     :server-port    443
     :uri            "/"
     :headers        (aws.protocols/headers service operation)
     :body           (util/query-string
                      (serialize input-shape request params []))}))

(defmethod aws.protocols/build-http-request "query"
  [service req-map]
  (build-query-http-request serialize service req-map))

(defn build-query-http-response
  [service {:keys [op]} {:keys [body]}]
  (let [operation (get-in service [:operations op])]
    (if-let [output-shape (service/shape service (:output operation))]
      (shape/xml-parse output-shape (util/bbuf->str body))
      (util/xml->map (util/xml-read (util/bbuf->str body))))))

(defmethod aws.protocols/parse-http-response "query"
  [service op-map http-response]
  (build-query-http-response service op-map http-response))
