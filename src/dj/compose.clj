(ns dj.compose
  (:require [clojure.set :as cs]
            [dj.compose.algorithm :as dca]))

;; This library provides two compositional methods.

;; 1. compile-time generated values using fnc (c stands compile-time)
;; 2. run-time generated values using fnr (r stand for run-time)

;; Compile time composition

(defmacro fnc
  "

returns a function that is expected to be called during compiling
step. Metadata is added to specify direct and late bound dependencies,
which must be plain symbols with no namespaces.

Typical usage would to have the value returned by this fnc to be a
fn. Then at compile-time, ->fn-map would pass these values (fns) to
fns that depend on them, thus enabling mutually recursive composition.

"
  [direct-bindings late-bindings & body]
  `(with-meta (fn [{:keys ~(vec (cs/union direct-bindings
                                          late-bindings))}]
                ~@body)
     {:dj.compose {:direct-bindings ~(set (map keyword direct-bindings))
                   :late-bindings ~(set (map keyword late-bindings))}}))

(defn ->fn-map
  "
Provides a more composable construct than letfn.

returns a hashmap of keywords -> values

Usually these values are functions to take advantage of the late
binding features but they can be plain values to take advantage of the
compositional power of maps.

fnc-map: keywords -> fncs

"
  ([fnc-map root-key alias-map root-late? ref-fn ref-set-fn!]
     ((fn add-bind [references temp-root late?]
        (let [the-fnc (or (fnc-map temp-root)
                          (fnc-map (alias-map temp-root))
                          (throw (Exception. (str "keyword " temp-root " not found in fnc-map"))))
              {:keys [direct-bindings late-bindings]} (-> the-fnc
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
                               (the-fnc references')))
                           (reduce (if-add true)
                                   references'
                                   late-bindings))]
          (when late?
            (ref-set-fn! the-ref (the-fnc return)))
          return))
      {}
      root-key
      root-late?))
  ([fnc-map root-key]
     (->fn-map fnc-map
               root-key
               {}))
  ([fnc-map root-key alias-map]
     (->fn-map fnc-map
               root-key
               alias-map
               true
               #(clojure.lang.Var/create)
               (fn [^clojure.lang.Var s v]
                 (.bindRoot s v)))))

;; ----------------------------------------------------------------------

;; Complement of a fnc-map is a fnr-map

(defmacro fnr
  "

returns a function that is expected to be called during compiling
step. Metadata is added to specify direct and late bound dependencies,
which must be plain symbols with no namespaces.

Typical usage would to have the value returned by this fnc to be a
fn. Then at compile-time, ->fn-map would pass these values (fns) to
fns that depend on them, thus enabling mutually recursive composition.

"
  [bindings & body]
  `(with-meta (fn ~bindings
                ~@body)
     {:dj.compose {:dependencies '~(mapv keyword bindings)}}))

(defmacro wrap-fnr
  "
Convenience macro:

adds dependency metadata to an existing fn or var

Takes a fn-or-var and a vector of keys
"
  [fn-or-var vec-of-keys]
  `(with-meta ~fn-or-var
     {:dj.compose {:dependencies ~vec-of-keys}}))

(declare ^:dynamic eval-pass)

(defn ->let-fn [fnr-map root-key input-keys]
  (let [input-key-set (set input-keys)
        available-keys (set (keys fnr-map))
        shaken-keys ((fn collect [all-dependents temp-key]
                       (let [the-fnr (temp-key fnr-map)
                             dependents (-> the-fnr
                                            meta
                                            :dj.compose
                                            :dependencies
                                            set
                                            (cs/difference input-key-set))]
                         (if (empty? dependents)
                           (conj all-dependents
                                 temp-key)
                           (reduce collect
                                   (cs/union dependents all-dependents)
                                   dependents))))
                     #{root-key}
                     root-key)
        undefined-keys (cs/difference shaken-keys available-keys)
        _ (if (empty? undefined-keys)
            nil
            (throw (Exception. (str "Unbound keys " undefined-keys))))
        shaken-map (select-keys fnr-map shaken-keys)
        shaken-dag (reduce-kv (fn [ret k the-fnr]
                                (let [dependents (-> the-fnr
                                                     meta
                                                     :dj.compose
                                                     :dependencies)]
                                  (assoc ret
                                    k
                                    (cs/difference (set dependents)
                                                   input-key-set))))
                              {}
                              shaken-map)
        sorted-keys (dca/topological-sort shaken-dag)
        symbols (reduce (fn [ret k]
                          (assoc ret
                            k (-> k
                                  name
                                  gensym)))
                        {}
                        shaken-keys)]
    (binding [eval-pass shaken-map]
      (eval `(let ~(vec
                    (mapcat (fn [k]
                              (list (symbols k) `(~k eval-pass)))
                            shaken-keys))
               (fn ~(mapv (comp symbol name) input-keys)
                 (let ~(vec
                        (mapcat (fn [k]
                                  (list (symbol (name k))
                                        `(~(symbols k) ~@(map (comp symbol name)
                                                              (-> k
                                                                  shaken-map
                                                                  meta
                                                                  :dj.compose
                                                                  :dependencies)))))
                                sorted-keys))
                   ~(symbol (name root-key)))))))))
