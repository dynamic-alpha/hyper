(ns hyper.protocols
  "Protocols for extending hyper's capabilities.

   Consumers implementing external state sources (databases, message queues, etc.)
   should extend the Watchable protocol to enable reactive re-renders via `h/watch!`.")

(defprotocol Watchable
  "Protocol for types that can be watched for changes.
   Extend this to allow `watch!` to observe custom external state sources
   like databases, message queues, or any stateful resource.

   Implementations must:
   - Call (callback old-val new-val) when the watched value changes
   - Support multiple concurrent watches keyed by unique keys
   - Remove the watch cleanly when -remove-watch is called"
  (-add-watch [this key callback]
    "Add a watch with the given key. callback is (fn [old-val new-val]).")
  (-remove-watch [this key]
    "Remove a previously added watch by key."))

(extend-protocol Watchable
  clojure.lang.IRef
  (-add-watch [this key callback]
    (add-watch this key (fn [_k _ref old-val new-val]
                          (callback old-val new-val))))
  (-remove-watch [this key]
    (remove-watch this key)))
