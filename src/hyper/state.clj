(ns hyper.state
  "State management for hyper applications.
   
   Manages session and tab-scoped state using atoms and cursors.
   Cursors implement IRef for familiar Clojure semantics.")

;; Global state stores
;; session-states: {session-id atom}
;; tab-states: {tab-id atom}

;; TODO: Define session-states atom
;; TODO: Define tab-states atom
;; TODO: Implement Cursor type (IRef, IDeref, ISwap, IReset)
;; TODO: Implement get-or-create-session-state!
;; TODO: Implement get-or-create-tab-state!
;; TODO: Implement cursor factory functions
