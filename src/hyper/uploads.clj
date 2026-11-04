(ns ^:no-doc hyper.uploads
  "Multipart file uploads for hyper actions.

   An upload is just an `h/action` whose body uses a file client param ($form
   or $files) — the macro then routes the request through a multipart/form-data
   transport instead of the JSON action queue.  Files cannot ride the JSON
   channel, so this is a real `fetch`/XHR multipart POST to `/hyper/upload`,
   parsed server-side by Ring's multipart middleware (streaming each part to a
   temp file on disk).

   Status + progress flow through an `:upload` ref the caller supplies — any
   IRef, but a `signal` is recommended:

     (let [status* (h/signal :avatar-upload {:phase :idle :percent 0})]
       [:form {:data-on:submit__prevent (h/action {:upload status*} (save! $form))}
        ...]
       [:progress {:data-attr:value (h/expr (:percent @status*))}])

   The ref's value is a map:

     {:phase   :idle | :uploading | :processing | :done | :error
      :percent 0-100        ;; live transfer % (signal refs only)
      :result  <handler return value, on :done>
      :error   <message string, on :error>}

   Who writes what:
   - A signal ref gets live client-side writes during transfer
     (phase :uploading + percent) plus server writes on receipt; the server's
     status changes are pushed back over SSE (the server never writes :percent
     mid-transfer, so it can't clobber the client's live value — it sets
     percent 100 only once the body has fully arrived).
   - A cursor/atom ref is server-only: no live transfer %, just
     :idle -> :processing -> :done/:error via the normal re-render.

   The handler's return value becomes :result; an uncaught throw becomes
   :error.  Because the ref is the caller's, the handler may also write it
   directly for richer (e.g. per-file) status."
  (:require [camel-snake-kebab.core :as csk]
            [hyper.actions :as actions]
            [hyper.datastar :as datastar]
            [hyper.signal :as signal]))

;; ---------------------------------------------------------------------------
;; Status ref
;; ---------------------------------------------------------------------------

(def default-status
  "The initial upload-status value used to seed an `:upload` ref's map when it
   has no value yet."
  {:phase :idle :percent 0})

(defn set-status!
  "Merge `patch` into the upload status held by `ref` (a signal, cursor, or any
   IRef).  No-op when `ref` is nil (a fire-and-forget upload with no `:upload`
   ref).  Merging (not resetting) preserves keys the patch does not touch — in
   particular a signal ref's client-written `:percent`."
  [ref patch]
  (when ref
    (swap! ref (fn [s] (merge (or s default-status) patch))))
  nil)

;; ---------------------------------------------------------------------------
;; Client-side expression (action macro -> Datastar attribute)
;; ---------------------------------------------------------------------------

(defn build-upload-expr
  "Build the Datastar expression that performs a multipart upload via the
   `window.hyper.upload` helper, which sends it with the render the element
   `el` was made on.

   `form-data-js` is JS evaluating to a `FormData` (the multipart param's :js).
   When `upload-ref` is a signal, progress/phase callbacks are wired to write
   its nested signal — live transfer % and a `uploading`/`error` phase, with no
   server round-trip.  For a cursor/atom ref there are no client callbacks
   (status is wholly server-driven over SSE).  `guard-js`, when present, gates
   the upload behind a client-side condition (the action `:when` guard)."
  [action-id form-data-js upload-ref base-path guard-js]
  (let [url       (str base-path "/hyper/upload?action-id=" action-id)
        callbacks (when (signal/signal? upload-ref)
                    (let [n (signal/js-name upload-ref)]
                      (str ",start:function(){$" n ".phase='uploading';$" n ".percent=0}"
                           ",progress:function(p){$" n ".percent=p}"
                           ",error:function(){$" n ".phase='error'}")))
        call      (str "hyper.upload(" form-data-js ",{url:'" url "',el:el" callbacks "})")]
    (if guard-js
      (str guard-js " && " call)
      call)))

(defn build-expr
  "Dispatch used by the `action` macro: when the body uses a multipart param
   ($form/$files) emit an upload expression; otherwise the normal @hyperAction
   expression.  `used-params` is the sym->definition map collected by the
   macro.

   Returns a `hyper.datastar/RawExpr` so the result both renders as an HTML
   attribute value and splices as raw JS inside `h/expr`."
  [action-id used-params guard-js base-path upload-ref]
  (datastar/raw-expr
    (if-let [mp (some (fn [[_ d]] (when (:multipart? d) d)) used-params)]
      (build-upload-expr action-id (:js mp) upload-ref base-path guard-js)
      (actions/build-action-expr action-id used-params guard-js base-path))))

;; ---------------------------------------------------------------------------
;; Server-side multipart parsing
;; ---------------------------------------------------------------------------

(defn- file-map?
  "True when a parsed multipart value is an uploaded file (the temp-file-store
   shape: a map with :filename / :tempfile)."
  [v]
  (and (map? v)
       (or (contains? v :filename) (contains? v :tempfile))))

(defn parse-multipart
  "Turn a Ring `:multipart-params` map (string keys; file values are
   `{:filename :content-type :tempfile :size}`) into the action client-params
   map bound to `$form` / `$files`:

     {:form  <keyword-keyed map of every field, files inline>
      :files <vector of file maps across all fields>}

   Repeated field names (e.g. a `multiple` file input) arrive as a vector and
   are flattened into :files."
  [multipart-params]
  (let [form  (reduce-kv (fn [m k v] (assoc m (keyword k) v))
                         {}
                         (or multipart-params {}))
        files (->> (vals form)
                   (mapcat (fn [v]
                             (cond
                               (file-map? v)                              [v]
                               (and (sequential? v) (every? file-map? v)) v
                               :else                                      nil)))
                   vec)]
    {:form form :files files}))

(defn form-fields->signals
  "Fold the non-file form fields into a kebab-keyword map so `@signal*` reads in
   the upload handler resolve to submitted field values when an input's `name`
   matches the signal (e.g. a `data-bind` input named \"userName\" satisfies
   `(h/signal :user-name)`).  Values are the raw form strings."
  [multipart-params]
  (reduce-kv (fn [m k v]
               (if (file-map? v)
                 m
                 (assoc m (keyword (csk/->kebab-case-string k)) v)))
             {}
             (or multipart-params {})))
