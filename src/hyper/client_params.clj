(ns ^:no-doc hyper.client-params
  "Client parameters are special symbols that can appear in the body
  of the hyper.core/action macro, allowing server-side event handlers
  to access data collected from the client event handler.

  The built-in parameters are $value, $checked, $key, $detail, $form-data,
  and the file-upload params $form and $files.

  Most params are JSON-encoded into the @post() request — but binary file
  content cannot ride that channel.  A param marked `:multipart? true` instead
  switches the action's transport to a multipart/form-data upload (see
  hyper.uploads): its `:js` must evaluate to a `FormData` object (the request
  body), and its `:key` is the key under which the parsed upload is bound on
  the server (\"form\" or \"files\")."
  (:require [clojure.string :as str]))

(def ^:private default-client-param-registry
  "The default registry of client params."
  {'$value     {:js "evt.target.value" :key "value"}
   '$checked   {:js "evt.target.checked" :key "checked"}
   '$key       {:js "evt.key" :key "key"}
   '$detail    {:js "evt.detail" :key "detail"}
   '$form-data {:js  "Object.fromEntries(new FormData(evt.target.closest('form')))"
                :key "formData"}
   ;; --- File-upload params (multipart transport) ----------------------------
   ;; $form  — the whole enclosing form (named fields + files) as one multipart
   ;;          body.  Bound server-side to a keyword-keyed map (files inline).
   ;; $files — just the file(s) from the event target (an <input type=file>).
   ;;          Bound server-side to a vector of file maps.
   ;; Each :js evaluates to a FormData object; :key selects the server binding.
   '$form      {:js         "new FormData(evt.target.closest('form'))"
                :key        "form"
                :multipart? true}
   '$files     {:js         "(function(){var d=new FormData();var fs=(evt.target.files||[]);for(var i=0;i<fs.length;i++){d.append('files',fs[i]);}return d;})()"
                :key        "files"
                :multipart? true}})

(defmulti client-param
  "Maps special symbols, with a leading $, to a definition.
  
  Each definition is a map with two keys:
  
  :js - (string) JavaScript run in the handler to extract the value
  :key - (string) Key used in the URL query string to send the value to the server.
  
  Applications may provide methods for additional such symbols.
  Note that such methods must be provided before actions that make
  use of the symbols."
  identity)

(defn defined-client-params
  "Returns a set of the symbols of available client parameters."
  []
  (disj
    (->> (keys default-client-param-registry)
         (into (-> client-param methods keys))
         set)
    :default))

(defmethod client-param :default
  [symbol]
  (or (get default-client-param-registry symbol)
      (throw (ex-info (str "Unknown client-param value: "
                           symbol
                           " - extend multi hyper.client-params/client-param to add")
                      {:symbol          symbol
                       :defined-symbols (defined-client-params)}))))

(defn- expression
  "`js` as an expression.  An `h/expr` with statements (see
   `hyper.expr/join-statements`) returns its value through `__hyper_r`,
   which only works as a whole handler, so it's wrapped in a function."
  [js]
  (if (and (string? js) (str/ends-with? js "return __hyper_r;"))
    (str "(() => { " js " })()")
    js))

(defn definition
  "Client param `sym`'s definition, with its :js an expression that can sit
   in an argument list, an object or another expression."
  [sym]
  (update (client-param sym) :js expression))

(defn multipart-param?
  "True when `sym` is a client param whose transport is a multipart/form-data
   upload (its definition carries `:multipart? true`) rather than the default
   JSON @post() channel.  File params ($form, $files) are multipart."
  [sym]
  (boolean (:multipart? (client-param sym))))
