;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns transit.read
  (:refer-clojure :exclude [read])
  (:require [transit.write :as w]
            [clojure.edn :as edn])
  (:import [com.fasterxml.jackson.core
            JsonFactory JsonParser JsonToken JsonParseException]
           org.msgpack.MessagePack
           [org.msgpack.unpacker Unpacker MessagePackUnpacker]
           [org.msgpack.type Value MapValue ArrayValue RawValue ValueType]
           [org.apache.commons.codec.binary Base64]
           [java.io InputStream EOFException]))

(set! *warn-on-reflection* true)

(defn read-int-str
  [s]
  (let [o (edn/read-string s)]
    (when (number? o) o)))

(def default-decoders {"'" identity
                       ":" #(keyword %)
                       "?" #(Boolean/valueOf ^boolean (= "t" %))
                       "b" #(Base64/decodeBase64 ^bytes %)
                       "_" (fn [_] nil)
                       "c" #(.charAt ^String % 0)
                       "i" #(try
                              (Long/parseLong %)
                              (catch NumberFormatException _ (read-int-str %)))
                       "d" #(Double. ^String %)
                       "f" #(java.math.BigDecimal. ^String %)
                       "t" #(if (string? %)
                              (clojure.instant/read-instant-date %)
                              (java.util.Date. ^long %))
                       "u" #(if (string? %)
                              (java.util.UUID/fromString %)
                              (java.util.UUID. (first %) (second %)))
                       "r" #(java.net.URI. %)
                       "$" #(symbol %)
                       "set" #(reduce (fn [s v] (conj s v)) #{} %)
                       "list" #(reverse (into '() %))
                       "cmap" #(reduce (fn [m v] (assoc m (nth v 0) (nth v 1)))
                                       {} (partition 2 %))
                       "ratio" #(/ (first %) (second %))})

(def ^:private ^:dynamic *decoders* default-decoders)

(defn default-default-decoder
  [^String tag rep]
  (if (and (= (.length tag) 1) (string? rep))
    (str "`" tag rep)
    (w/tagged-value tag rep)))

(def ^:private ^:dynamic *default* default-default-decoder)

(defn decode
  [tag rep]
  ;;(prn "decode" tag rep)
  (if-let [decoder (*decoders* tag)]
    (decoder rep)
    (*default* tag rep)))

(defprotocol ReadCache
  (cache-read [cache str as-map-key]))

(defn parse-str
  [s]
  ;;(prn "parse-str before" s)
  (let [res (if (and (string? s)
                     (> (.length ^String s) 1)
                     (= "~" (subs ^String s 0 1))) ;; ESC
              (case (subs s 1 2)
                "~" (subs s 1) ;; ESC
                "^" (subs s 1) ;; SUB
                "`" (subs s 1) ;; RESERVED
                "#" s ;; TAG
                (decode (subs s 1 2) (subs s 2)))
              s)]
    ;;(prn "parse-str after" res)
    res))

(defn parse-tagged-map
  [^java.util.Map m]
  (let [entries (.entrySet m)
        iter (.iterator entries)
        entry (when (.hasNext iter) (.next iter))
        key (when entry (.getKey ^java.util.Map$Entry entry))]
    (if (and entry (string? key) (> (.length ^String key) 1) (= "#" (subs key 1 2)))
      (decode (subs key 2) (.getValue ^java.util.Map$Entry entry))
      m)))

(defn cache-code?
  [^String s]
  (= w/SUB (subs s 0 1)))

(defn code->idx
  [^String s]
  (- (byte ^Character (.charAt s 1)) w/BASE_CHAR_IDX))

(deftype ReadCacheImpl [^:unsynchronized-mutable idx cache]
  ReadCache
  (cache-read [_ str as-map-key]
    ;;(prn "cache read before" idx str)
    (let [res (if (and str (not (zero? (.length ^String str))))
                (if (w/cacheable? str as-map-key)
                  (do 
                    (when (= idx w/MAX_CACHE_ENTRIES)
                      (set! idx 0))
                    (let [o (parse-str str)]
                      (aset ^objects cache idx o)
                      (set! idx (inc idx))
                      o))
                  (if (cache-code? str)
                    (aget ^objects cache (code->idx str))
                    str))
                str)]
      ;;(prn "cache read after" idx res)
      res)))

(defn read-cache [] (ReadCacheImpl. 0 (make-array Object w/MAX_CACHE_ENTRIES)))

(defprotocol Parser
  (unmarshal [p cache])
  (parse-val [p as-map-key cache])
  (parse-map [p as-map-key cache])
  (parse-array [p as-map-key cache]))

(extend-protocol Parser
  JsonParser
  (unmarshal [^JsonParser jp cache]
    (when (.nextToken jp) (parse-val jp false cache)))

  (parse-val [^JsonParser jp as-map-key cache]
    ;;(prn "parse-val" (.getCurrentToken jp))
    (let [res (condp = (.getCurrentToken jp)
                JsonToken/START_OBJECT
                (parse-tagged-map (parse-map jp as-map-key cache))
                JsonToken/START_ARRAY
                (parse-array jp as-map-key cache)
                JsonToken/FIELD_NAME
                (parse-str (cache-read cache (.getText jp) as-map-key))
                JsonToken/VALUE_STRING
                (parse-str (cache-read cache (.getText jp) as-map-key))
                JsonToken/VALUE_NUMBER_INT
                (try 
                  (.getLongValue jp) ;; always read as long, coerce to string if too big
                  (catch JsonParseException _ (read-int-str (.getText jp))))
                JsonToken/VALUE_NUMBER_FLOAT
                (.getDoubleValue jp) ;; always read as double
                JsonToken/VALUE_TRUE
                (.getBooleanValue jp)
                JsonToken/VALUE_FALSE
                (.getBooleanValue jp)
                JsonToken/VALUE_NULL
                nil)]
      ;;(prn "parse-val" res)
      res))

  (parse-map [^JsonParser jp _ cache]
    (persistent!
     (loop [tok (.nextToken jp)
            res (transient {})]
       (if (not= tok JsonToken/END_OBJECT)
         (let [k (parse-val jp true cache)
               _ (.nextToken jp)
               v (parse-val jp false cache)]
           (recur (.nextToken jp) (assoc! res k v)))
         res))))

  (parse-array [^JsonParser jp _ cache]
    (persistent!
     (loop [tok (.nextToken jp)
            res (transient [])]
       (if (not= tok JsonToken/END_ARRAY)
         (let [item (parse-val jp false cache)]
           (recur (.nextToken jp) (conj! res item)))
         res)))))

(extend-protocol Parser
  MessagePackUnpacker
  (unmarshal [^MessagePackUnpacker mup cache] (parse-val mup false cache))

  (parse-val [^MessagePackUnpacker mup as-map-key cache]
    (try 
      (condp = (.getNextType mup)
        ValueType/MAP
        (parse-tagged-map (parse-map mup as-map-key cache))
        ValueType/ARRAY
        (parse-array mup as-map-key cache)
        ValueType/RAW
        (parse-str (cache-read cache
                               (-> mup .readValue .asRawValue .getString)
                               as-map-key))
        ValueType/INTEGER
        (-> mup .readValue .asIntegerValue .getLong) ;; always read as long
        ValueType/FLOAT
        (-> mup .readValue .asFloatValue .getDouble) ;; always read as double
        ValueType/BOOLEAN
        (-> mup .readValue .asBooleanValue .getBoolean)
        ValueType/NIL
        (.readNil mup))
      (catch EOFException e)))

  (parse-map [^MessagePackUnpacker mup _ cache]
    (persistent!
     (loop [remaining (.readMapBegin mup)
            res (transient {})]
       (if-not (zero? remaining)
         (recur (dec remaining)
                (assoc! res (parse-val mup true cache) (parse-val mup false cache)))
         (do
           (.readMapEnd mup true)
           res)))))

  (parse-array
    [^MessagePackUnpacker mup _ cache]
    (persistent! 
     (loop [remaining (.readArrayBegin mup)
            res (transient [])]
       (if-not (zero? remaining)
         (recur (dec remaining)
                (conj! res (parse-val mup false cache)))
         (do
           (.readArrayEnd mup true)
           res))))))

(deftype Reader [unmarshaler opts])

(defprotocol Readerable
  (make-reader [_ type opts]))

(extend-protocol Readerable
  InputStream
  (make-reader [^InputStream stm type opts]
    (Reader. 
     (case type
       :json (.createParser (JsonFactory.) stm)
       :msgpack (.createUnpacker (MessagePack.) stm))
     opts))
  java.io.Reader
  (make-reader [^java.io.Reader r type opts]
    (Reader.
     (case type
       :json (.createParser (JsonFactory.) r)
       :msgpack (throw (ex-info "Cannot create :msgpack reader on top of java.io.Reader, must use java.io.InputStream" {})))
     opts)))

(defn reader
  ([o type] (reader o type {}))
  ([o type opts]
     (if (#{:json :msgpack} type)
       (make-reader o type opts)
       (throw (ex-info "Type must be :json or :msgpack" {:type type})))))

(defn read [^Reader reader]
  (let [{:keys [decoders default]} (.opts reader)]
    (binding [*decoders* (merge default-decoders decoders)
              *default* (or default default-default-decoder)]
      (unmarshal (.unmarshaler reader) (read-cache)))))

