(ns hyper.context
  "Request context and dynamic vars for hyper applications.

   Lives in a low-level namespace so that both render.clj and core.clj
   can reference these vars without circular dependencies.")

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

;; Per-render action counter. Bound to (atom 0) before each render so that
;; deterministic render functions produce the same action IDs every time,
;; enabling effective brotli streaming compression.
(def ^:dynamic *action-idx* nil)

(defn require-context!
  "Extract and validate the request context from *request*.
   Throws if called outside a request context or if required keys are missing.
   Returns a map with :session-id, :tab-id, :app-state*, and :router."
  [caller-name]
  (when-not *request*
    (throw (ex-info (str caller-name " called outside request context") {})))
  (let [session-id (:hyper/session-id *request*)
        tab-id     (:hyper/tab-id *request*)
        app-state* (:hyper/app-state *request*)]
    (when-not app-state*
      (throw (ex-info "No app-state in request" {:request *request*})))
    {:session-id session-id
     :tab-id     tab-id
     :app-state* app-state*
     :router     (:hyper/router *request*)}))
