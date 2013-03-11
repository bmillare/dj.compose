(ns dj.compose
  (:require [clojure.set :as cs]))

(defmacro fnb
  "

direct-bindings and late-bindings must be sets of plain symbols, no namespaces

fnb returns a function that accepts a single map of keys to
expressions/functions that may depend on late-bounded references

"
  [direct-bindings late-bindings & body]
  `(with-meta (fn [{:keys ~(vec (cs/union direct-bindings
                                          late-bindings))}]
                ~@body)
     {:dj.compose {:direct-bindings ~(set (map keyword direct-bindings))
                   :late-bindings ~(set (map keyword late-bindings))}}))

(defn ->bind-map
  "
returns a hashmap of keywords -> values

Usually these values are functions to take advantage of the late
binding features but they can be plain values to take advantage of the
compositional power of maps.

fnb-map: keywords -> fnbs

"
  ([fnb-map root-key alias-map root-late? ref-fn ref-set-fn!]
     ((fn add-bind [references temp-root late?]
        (let [the-fnb (or (fnb-map temp-root)
                          (fnb-map (alias-map temp-root))
                          (throw (Exception. (str "keyword " temp-root " not found in fnb-map"))))
              {:keys [direct-bindings late-bindings]} (-> the-fnb
                                                          meta
                                                          :dj.compose)
              the-ref (when late?
                        (ref-fn))
              if-add (fn [l?]
                       (fn if-add [ret s]
                         (if (ret s)
                           ret
                           (add-bind ret s l?))))
              return (as-> references
                           references'
                           (reduce (if-add false)
                                   references'
                                   direct-bindings)
                           (assoc references'
                             temp-root
                             (if late?
                               the-ref
                               (the-fnb references')))
                           (reduce (if-add true)
                                   references'
                                   late-bindings))]
          (when late?
            (ref-set-fn! the-ref (the-fnb return)))
          return))
      {}
      root-key
      root-late?))
  ([fnb-map root-key]
     (->bind-map fnb-map
                 root-key
                 {}))
  ([fnb-map root-key alias-map]
     (->bind-map fnb-map
                 root-key
                 alias-map
                 true
                 #(clojure.lang.Var/create)
                 (fn [^clojure.lang.Var s v]
                   (.bindRoot s v)))))
