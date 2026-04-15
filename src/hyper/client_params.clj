(ns hyper.client-params
  "Client parameters are special symbols that can appear in the body
  of the hyper.core/action macro, allowing server-side event handlers
  to access data collected from the client event handler.
  
  The built-in parameters are $value, $checked, $key, and $form-data.")

(def ^:private default-client-param-registry
  "The default registry of client params."
  {'$value     {:js "evt.target.value" :key "value"}
   '$checked   {:js "evt.target.checked" :key "checked"}
   '$key       {:js "evt.key" :key "key"}
   '$form-data {:js  "Object.fromEntries(new FormData(evt.target.closest('form')))"
                :key "formData"}})

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
