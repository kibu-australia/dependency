;; Copyright (c) Stuart Sierra, 2013. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "Directed acyclic graph for representing dependency relationships."}
  com.stuartsierra.dependency
  (:require [clojure.set :as set]))

(defprotocol DependencyGraph
  (immediate-dependencies [graph node]
    "Returns the set of immediate dependencies of node.")
  (immediate-dependents [graph node]
    "Returns the set of immediate dependents of node.")
  (transitive-dependencies [graph node]
    "Returns the set of all things which node depends on, directly or
    transitively.")
  (transitive-dependents [graph node]
    "Returns the set of all things which depend upon node, directly or
    transitively.")
  (nodes [graph]
    "Returns the set of all nodes in graph."))

(defprotocol DependencyGraphUpdate
  (depend [graph node dep]
    "Returns a new graph with a dependency from node to dep (\"node depends
    on dep\"). Forbids circular dependencies.")
  (remove-edge [graph node dep]
    "Returns a new graph with the dependency from node to dep removed.")
  (remove-all [graph node]
    "Returns a new dependency graph with all references to node removed.")
  (remove-node [graph node]
    "Removes the node from the dependency graph without removing it as a
    dependency of other nodes. That is, removes all outgoing edges from
    node."))

(defn- remove-from-map [amap x]
  (reduce (fn [m [k vs]]
	    (assoc m k (disj vs x)))
	  {} (dissoc amap x)))

(defn- transitive
  "Recursively expands the set of dependency relationships starting
  at (get m x)"
  [m x]
  (reduce (fn [s k]
	    (set/union s (transitive m k)))
	  (get m x) (get m x)))

(declare depends?)

(def ^:private set-conj (fnil conj #{}))

;; Do not construct directly, use 'graph' function
(defrecord MapDependencyGraph [dependencies dependents]
  DependencyGraph
  (immediate-dependencies [graph node]
    (get dependencies node #{}))
  (immediate-dependents [graph node]
    (get dependents node #{}))
  (transitive-dependencies [graph node]
    (transitive dependencies node))
  (transitive-dependents [graph node]
    (transitive dependents node))
  (nodes [graph]
    (set/union (set (keys dependencies))
               (set (keys dependents))))
  DependencyGraphUpdate
  (depend [graph node dep]
    (when (or (= node dep) (depends? graph dep node))
      (throw (#+clj Exception.
              #+cljs js/Error.
              (str "Circular dependency between "
                   (pr-str node) " and " (pr-str dep)))))
    (MapDependencyGraph.
     (update-in dependencies [node] set-conj dep)
     (update-in dependents [dep] set-conj node)))
  (remove-edge [graph node dep]
    (MapDependencyGraph.
     (update-in dependencies [node] disj dep)
     (update-in dependents [dep] disj node)))
  (remove-all [graph node]
    (MapDependencyGraph.
     (remove-from-map dependencies node)
     (remove-from-map dependents node)))
  (remove-node [graph node]
    (MapDependencyGraph.
     (dissoc dependencies node)
     dependents)))

(defn graph
  "Returns a new, empty, dependency graph. A graph contains nodes,
  which may be any type which supports Clojure's equality semantics.
  Edges are represented as pairs of nodes. An edge between two nodes X
  and Y indicates that X depends on Y or, conversely, that Y is a
  dependent of X. A dependency graph may not have cycles."
  []
  (MapDependencyGraph. {} {}))

(defn depends?
  "True if x is directly or transitively dependent on y."
  [graph x y]
  (contains? (transitive-dependencies graph x) y))

(defn dependent?
  "True if y is a direct or transitive dependent of x."
  [graph x y]
  (contains? (transitive-dependents graph x) y))

(defn topo-sort
  "Returns a topologically-sorted list of nodes in graph."
  [graph]
  (loop [sorted ()
         g graph
         todo (set (filter #(empty? (immediate-dependents graph %))
                           (nodes graph)))]
    (if (empty? todo)
      sorted
      (let [[node & more] (seq todo)
            deps (immediate-dependencies g node)
            [add g'] (loop [deps deps
                            g g
                            add #{}]
                       (if (seq deps)
                         (let [d (first deps)
                               g' (remove-edge g node d)]
                           (if (empty? (immediate-dependents g' d))
                             (recur (rest deps) g' (conj add d))
                             (recur (rest deps) g' add)))
                         [add g]))]
        (recur (cons node sorted)
               (remove-node g' node)
               (set/union (set more) (set add)))))))

(defn topo-comparator
  "Returns a comparator fn which produces a topological sort based on
  the dependencies in graph. Nodes not present in the graph will sort
  after nodes in the graph."
  [graph]
  (let [pos (zipmap (topo-sort graph) (range))]
    (fn [a b]
      (compare (get pos a #+clj Long/MAX_VALUE
                          #+cljs (.-MAX_VALUE js/Number))
               (get pos b #+clj Long/MAX_VALUE
                          #+cljs (.-MAX_VALUE js/Number))))))
