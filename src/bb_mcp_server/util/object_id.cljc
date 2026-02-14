(ns bb-mcp-server.util.object-id
    "Cross-platform O(1) object identity fingerprinting.

   Assigns a stable numeric ID to any object reference, enabling
   cheap change detection without deep value comparison.

   JVM/Babashka: Uses System/identityHashCode (memory-address-based).
   CLJS/Scittle: Uses WeakMap + monotonic counter (GC-friendly).

   Usage:
     (object-id my-atom)          ;; => 12345 (stable for same reference)
     (object-id (deref my-atom))  ;; => 67890 (changes when atom is swapped)")

#?(:clj
   (defn object-id
     "Returns an O(1) identity fingerprint for any object.
      Same object reference always returns the same integer.
      Note: JVM identityHashCode may collide for different objects."
     [obj]
     (if (some? obj)
       (System/identityHashCode obj)
       0))

   :cljs
   (do
    (defonce ^:private !oid-counter (atom 0))
    (defonce ^:private !oid-map (js/WeakMap.))

    (defn ^:private weakmap-key?
      "Returns true if val can be used as a WeakMap key (non-primitive)."
      [val]
      (and (some? val)
           (not (number? val))
           (not (string? val))
           (not (boolean? val))
           (not (keyword? val))
           (not (symbol? val))))

    (defn object-id
      "Returns an O(1) identity fingerprint for any object.
        For JS objects: unique WeakMap-backed counter (no collisions).
        For primitives: falls back to value-based hash."
      [obj]
      (cond
        (nil? obj) 0
        (weakmap-key? obj)
        (or (.get !oid-map obj)
            (let [id (swap! !oid-counter inc)]
              (.set !oid-map obj id)
              id))
        :else (hash obj)))))
