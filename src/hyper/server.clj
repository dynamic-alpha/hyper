(ns hyper.server
  "HTTP server, routing, and middleware.
   
   Provides Ring handlers for:
   - Initial page loads
   - SSE event streams
   - Action POST endpoints"
  (:require [reitit.ring :as ring]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.adapter.jetty :as jetty]))

;; TODO: Implement session/tab ID middleware
;; TODO: Implement GET / handler (initial page)
;; TODO: Implement GET /hyper/events handler (SSE)
;; TODO: Implement POST /hyper/actions handler
;; TODO: Implement create-app (reitit router)
;; TODO: Implement start-server!
;; TODO: Implement stop-server!
