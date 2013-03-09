(ns dj.compose
  (:require [clojure.set :as cs]))

(defmacro fnl
  "like prismatic's fnk but accepts two sets, first set is for direct-binding, and second set is for late-bindings

fnl returns a function that accepts a single map of keys to expressions/functions that depend on late-bounded references
"
  [direct-bindings late-bindings & body]
  `(with-meta (fn [{:keys ~(vec (cs/union direct-bindings
                                          late-bindings))}]
                ~@body)
     {:dj.compose {:direct-bindings direct-bindings
                   :late-bindings late-bindings}}))

(defn compose [generator-map root-key]
  )