dj.compose
==========

# Motivation

Programmers can locally define bindings to values and mutually recursive functions using `let` and `letfn` respectively. Increasing the number of bindings is problematic however, in that:

* The bindings become unwieldy. If you want to seperate and test sub-components, users must copy paste the definitions into a new `let` or `letfn` form.
* Bindings are not composable. Users cannot arbitrarily add, remove, and combine different sets of expressions or functions.
* For `let`, binding order must be compatible with the dependency graph, making the code less declarative.
* For `letfn`, we would prefer to not use references for non-cyclic and instead refer to these functions directly for efficiency.

This goal of this library is to provide methods for *declaratively* composing both mutual recursive functions and `let` bindings.

## Example usage for defining mutually recursive functions (`letfn` like)

```clojure
(let [fnc-map {:conj-ping (fnc #{} #{}
                            (fn [v]
                              (conj v :ping)))
               :ping (fnc #{conj-ping} #{pong}
                       (fn [n v]
                         (if (> n 0)
                           (pong n (conj-ping v))
                           v)))
               :pong-message (fnc #{} #{}
                               :pong)
               :pong (fnc #{pong-message} #{ping}
                       (fn [n v]
                         (ping (dec n) (conj v pong-message))))}
      ping (-> fnc-map
               (->fn-map :ping)
               :ping
               deref)]
  (ping 5 []))
;;=>
[:ping :pong :ping :pong :ping :pong :ping :pong :ping :pong]
```

Note that `fnc-map` is just a hashmap, so we gain all the composition power of using hashmaps. We can arbitrarily `assoc`, `dissoc`, and `merge` other `fnc-map`s.

## Example usage for composing value bindings (`let` like)

```clojure
(require '[dj.compose :as dc])
(let [fnr-map {:c (dc/fnr [n]
                    (* 2 n))
               :a (dc/fnr [c]
                    (inc c))
               :b (dc/fnr [a c]
                    (+ a c))}
      let-fn (dc/->let-fn fnr-map
                          :b
                          [:n])]
  (let-fn 2))
;;=>
9
```

# Methods & Concepts

* This library obtains compositional power by using functions in hashmaps. Functions enable binding values under different contexts, and hashmaps give the ability to add, remove, and merge different sets of data.
* This library uses metadata to store the dependency information.
* Two different function constructors are defined, `fnc` and `fnr`. The "c" and "r" stand for **compile-time** and **run-time** respectively. The two types are necessary because when users define bindings for the **lefn-like** case, the bindings won't be used immediately but only later when the functions are called, whereas when defining bindings for the **let-like** case, the values will be passed on and immediately used by the dependent.

## The `fnc` macro in detail

A **compile-time function**, or `fnc` for short, is a function that accepts a **fn-map** (map of keywords to **user functions**), and returns a **user function** (these can also be a plain value if you wanted to). It is expected that the **fn-map** will be passed to the `fnc`s at a "compile" time. `fnc` will destructure the map passed and these values will close over whatever is in the body. Typically it makes sense to make the body an anonymous function definition.

Raw usage of a `fnc` (not something a user would do typically):
```clojure
(let [user-fn (some-fnc {:x (fn ...) :y (fn ...)})]
  (user-fn ...))
```

Usage of the `fnc` macro. `fnc`s accept a set of direct and late binding symbols, and then the body:
```clojure
(require '[dj.compose :as dc])
(dc/fnc #{direct-add} #{late-parse late-compile}
  (fn [x y]
    (-> (direct-add x y)
    	late-parse
	late-compile)))
```

Users will store there `fnc`s in a hashmap of keywords to `fnc`s. When users are ready to compile `fnc`s, they will use `(->fn-map)` to produce a hashmap of compiled **user functions**, which we name them **fn-maps**.

In review, these **fn-maps** have the following useful properties:

* They support references to other **user functions** (cyclic references)
* They support direct access to dependent elements. (To be efficient, **user functions**, when called, should not perform a map lookup, and should instead refer directly (or late-directly) to the other **user functions**.)

Idealized usage:
```clojure
(require '[dj.compose :as dc])
(let [my-fn-map (dc/->fn-map ...definitions...)
      x (:x my-fn-map)]
  (x ...))
```

Note that `fnc`s are to be used only with `(->fn-map)`.

## The `fnr` macro

A **run-time function**, or `fnr` for short, is a function that accepts a **value-map**, and returns a resulting **user value** from some computation. Unlike `fnc`, `fnr` is just sugar to obtain dependency information from a plain function and record it in the metadata. The values passed in to the `fnr` are expected to be passed at runtime.

## Discussion

In more detail, `->fn-map` obtains dependency information from a `fnc-map`, a hashmap of keywords representing **user functions** -> `fnc`.

`->fn-map` will call the `fnc`s in the correct order, updating the **fn-map** with the "compiled" **user functions**. If the dependents are declared late-bound, then a **reference** is put in the map instead. This **reference** is set at the end of the `->fn-map` call, to the value returned by `fnc`.

The full argument list of `->fn-map` is:

`fnc-map`

`root-key`: `->fn-map` will construct the hashmap relative to the dependencies of a specific node. This also means if there are other keys not reached by the root node, then they won't get compiled.

Optional:

`alias-map`: You can define aliases from `:alias -> :existing-key`. This way you can have "interface" keywords and depend on those. Then instead of redefining all functions to use a different key, you just change the alias.

`root-late?`: You can declare the root is not late-bound, by default this is true.

You can define your own reference type:

`ref-fn`: a 0-arity constructor fn to produce a ref

`ref-set-fn!`: sets the ref to a value (eg. `(ref-set-fn! the-ref val)`)

If you don't provide your own reference type, dj.compose will use `clojure.lang.Vars`.

*Performance Note:*
To minimize indirection overhead, use `java.util.concurrent.atomic.AtomicReference` which has a `(.get)` method. This is slightly faster than **atoms** and much faster than **Vars** since we remove an extra invocation. The downside is you will need to typehint the symbols and you can't use **Vars** auto invocation of the function.

## License

Copyright (c) Brent Millare. All rights reserved. The use and distribution terms for this software are covered by the Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can be found in the file epl-v10.html at the root of this distribution. By using this software in any fashion, you are agreeing to be bound by the terms of this license. You must not remove this notice, or any other, from this software.
