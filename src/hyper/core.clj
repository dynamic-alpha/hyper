(ns hyper.core
  "Public API for the hyper web framework.

   Provides:
   - session-cursor and tab-cursor for state management
   - action macro for handling user interactions
   - start! and stop! for server lifecycle"
  (:require [hyper.state :as state]))

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

(defn session-cursor
  "Create a cursor to session state at the given path.
   Path can be a keyword or vector.

   Example:
     (session-cursor :user)
     (session-cursor [:user :name])"
  [path]
  (when-not *request*
    (throw (ex-info "session-cursor called outside request context" {})))
  (let [session-id (get *request* :hyper/session-id)]
    (when-not session-id
      (throw (ex-info "No session-id in request" {:request *request*})))
    (state/session-cursor session-id path)))

(defn tab-cursor
  "Create a cursor to tab state at the given path.
   Path can be a keyword or vector.

   Example:
     (tab-cursor :count)
     (tab-cursor [:todos :list])"
  [path]
  (when-not *request*
    (throw (ex-info "tab-cursor called outside request context" {})))
  (let [tab-id (get *request* :hyper/tab-id)]
    (when-not tab-id
      (throw (ex-info "No tab-id in request" {:request *request*})))
    (state/tab-cursor tab-id path)))

;; TODO: Implement action macro
;; TODO: Implement start!
;; TODO: Implement stop!
