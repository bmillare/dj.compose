dj.compose
==========

Composable mutual recursion function composition.

*While currently this is a proof-of-concept library, users should not be afraid to use it. The code is very simple. Since the source is only 1 macro and 1 function, I encourage users to read the code.*

# Motivation

When building a network of mutually recursive functions, clojure provides the `let` and `letfn` forms. While these forms allow users to define functions and bind them to symbols, they are problematic in that:

* As the number of bindings increase the `let` becomes unwieldy and hard to test. If you want to seperate and test sub components, users must copy paste the definitions into a new `let` form.
* Bindings are not composable. Users cannot arbitrarily add, remove, and combine different sets of functions.
* Binding order must be compatible with the dependency graph, making the code less declarative.
* Late binding mechanisms are not built into `let` for handling cylic dependencies.

The goal of this library is to add compositional power to direct and late bindings in a *declarative* way, to enhance programming with mutually dependent functions.

## Example Usage

```clojure
(let [bind-fn-map {:conj-ping (fnb #{} #{}
                                (fn [v]
                                  (conj v :ping)))
                   :ping (fnb #{conj-ping} #{pong}
                           (fn [n v]
                             (if (> n 0)
                               (@pong n (conj-ping v))
                               v)))
                   :pong-message (fnb #{} #{}
                                   :pong)
                   :pong (fnb #{pong-message} #{ping}
                           (fn [n v]
                             (@ping (dec n) (conj v pong-message))))}
      ping (-> bind-map
               (->bind-map :ping)
               :ping
               deref)]
  (ping 5 []))
;;=>
[:ping :pong :ping :pong :ping :pong :ping :pong :ping :pong]
```

Note that `bind-fn-map` is just a hashmap, so we gain all the composition power of using hashmaps. We can arbitrarily `assoc`, `dissoc`, and `merge` other `bind-fn-map`s.

# Methods & Concepts

Ideally, we want a hashmap of keywords representing the **user functions**, to the **user functions** themselves. In addition the hashmap should:

* Support references to other **user functions** (cyclic references)
* Support direct access to dependent elements. (To be efficient, **user functions**, when called, should not perform a map lookup, and should instead refer directly (or late-directly) to the other **user functions**.)

## The `->bind-map` function

`->bind-map` produces exactly this data structure. Users must pass the **user function** definitions and their dependencies.

Ideal usage:
```clojure
(let [x @(:x (dc/->bind-map ...definitions...))]
  (x ...))
```

## The `fnb` macro

A **binding function**, or `fnb` for short, is a function that accepts a **bind-map**, and returns a **user function** (but can also be a plain value if you wanted to) that may depend on some of the values in the **bind-map**. `fnb`s are important for generating the **user functions** that the user will actually call in their code.

```clojure
(let [user-fn (some-fnb {:x ... :y ...})]
  (user-fn ...))
```

`fnb` is a macro that lets you define **binding functions**. `fnb`s accept a set of direct and late binding symbols, and then the body.

Example `fnb`:
```clojure
(dc/fnb #{direct-add} #{late-parse late-compile}
  (fn [x y]
    (-> (direct-add x y)
    	late-parse
	late-compile)))
```

## Discussion

In more detail, `->bind-map` obtains dependency information from a `fnb-map`, a hashmap of keywords representing **user functions** -> `fnb`.

`->bind-map` will call the `fnb`s in the correct order, updating the **bind-map** with the "compiled" **user functions**. If the dependents are declared late-bound, then a **reference** is put in the map instead. This **reference** is set at the end of the `->bind-map` call, to the value returned by `fnb`.

The full argument list of `->bind-map` is:

`fnb-map`

`root-key`: `->bind-map` will construct the hashmap relative to the dependencies of a specific node. This also means if there are other keys not reached by the root node, then they won't get compiled.

Optional:

`root-late?`: You can declare the root is not late-bound, by default this is true.

You can define your own reference type:

`ref-fn`: a 0-arity constructor fn to produce a ref

`ref-set-fn!`: sets the ref to a value (eg. `(ref-set-fn! the-ref val)`)

If you don't provide your own reference type, dj.compose will use atoms.

*Performance Note:*
To minimize `deref` overhead, use `java.util.concurrent.atomic.AtomicReference` which has a `(.get)` method. This is slightly faster than **atoms** since we remove an extra invocation. The downside is you will need to typehint the symbols.
