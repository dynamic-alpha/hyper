(ns hyper.core
  "Public API for the hyper web framework.
   
   Provides:
   - session-cursor and tab-cursor for state management
   - action macro for handling user interactions
   - start! and stop! for server lifecycle"
  (:require [hyper.state :as state]
            [hyper.actions :as actions]
            [hyper.render :as render]
            [hyper.server :as server]))

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

;; TODO: Implement session-cursor
;; TODO: Implement tab-cursor
;; TODO: Implement action macro
;; TODO: Implement start!
;; TODO: Implement stop!
