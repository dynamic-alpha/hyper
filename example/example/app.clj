(ns example.app
  (:require [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.state]))

;; ---------------------------------------------------------------------------
;; Shared layout
;; ---------------------------------------------------------------------------

(defn layout
  "Wrap page content with a nav bar and consistent styling."
  [title & children]
  [:div.container
   [:nav
    [:a (h/navigate :home) "Home"]
    " · "
    [:a (h/navigate :counters) "Cursors (via counters)"]
    " · "
    [:a (h/navigate :forms) "Forms & Inputs"]
    " · "
    [:a (h/navigate :signals) "Signals"]
    " · "
    [:a (h/navigate :optimistic) "Optimistic"]
    " · "
    [:a (h/navigate :effects) "Effects"]
    " · "
    [:a (h/navigate :uploads) "Uploads"]
    " · "
    [:a (h/navigate :components) "Components"]]
   [:h1 title]
   children])

;; ---------------------------------------------------------------------------
;; Home
;; ---------------------------------------------------------------------------

(defn home-page [_]
  (layout "Hyper Examples"
          [:p "Pick an example from the nav above."]))

;; ---------------------------------------------------------------------------
;; Counters
;; ---------------------------------------------------------------------------

(defn counter
  "Render a counter widget for any cursor."
  [label description cursor*]
  [:div.card
   [:h3 label ": " @cursor*]
   [:p.muted description]
   [:button {:data-on:click (h/action (swap! cursor* inc))} "+"]
   " "
   [:button {:data-on:click (h/action (swap! cursor* dec))} "–"]
   " "
   [:button {:data-on:click (h/action (reset! cursor* 0))} "Reset"]])

(defn counters-page [_]
  (let [global*  (h/global-cursor :count 0)
        session* (h/session-cursor :count 0)
        tab*     (h/tab-cursor :count 0)
        url*     (h/path-cursor :count 0)]
    (layout
      "Counters"
      [:p "Four counters, each backed by a different scope of state. "
       "Open multiple tabs to see how they differ."]
      (counter "Global" "Shared across every session and tab." global*)
      (counter "Session" "Shared across all tabs in this browser session." session*)
      (counter "Tab" "Private to this tab only." tab*)
      (counter "URL" "Stored in the query string — try bookmarking or sharing the link." url*))))

;; ---------------------------------------------------------------------------
;; Forms & Inputs
;; ---------------------------------------------------------------------------

(defn forms-page [_]
  (letfn [(card [title desc & children]
            [:div.card [:h3 title] [:p.muted desc] children])
          (result [label content]
            [:p label [:strong content]])]
    (let [text*    (h/tab-cursor :text "")
          value*   (h/tab-cursor :value "")
          checked* (h/tab-cursor :dark-mode false)
          key*     (h/tab-cursor :last-key "")
          select*  (h/tab-cursor :color "red")
          form*    (h/tab-cursor :form-data nil)]
      (layout
        "Forms & Inputs"
        [:p "These examples demonstrate " [:code "$value"] ", "
         [:code "$checked"] ", " [:code "$key"] ", and " [:code "$form-data"]
         " — client-side values transmitted to server actions."]

        ;; $value — text input
        (card "$value — Text Input"
              "Type below. Each keystroke sends $value to the server."
              [:input {:type          "text"
                       :placeholder   "Type something…"
                       :value         @text*
                       :data-on:input (h/action (reset! (h/tab-cursor :text) $value))}]
              (result "Server sees: " (if (seq @text*) @text* "nothing yet")))

        ;; $value — select
        (card "$value — Select"
              "Pick a colour. The server receives the selected option's value."
              [:select {:data-on:change (h/action (reset! (h/tab-cursor :color) $value))}
               (for [c ["red" "green" "blue" "purple"]]
                 [:option {:value c :selected (= c @select*)} c])]
              (result "Selected: " @select*))

        ;; $checked — checkbox
        (card "$checked — Checkbox"
              "Toggle the checkbox. $checked sends a boolean to the server."
              [:label
               [:input {:type           "checkbox"
                        :checked        @checked*
                        :data-on:change (h/action (reset! (h/tab-cursor :dark-mode) $checked))}]
               " Dark mode"]
              (result "Dark mode is: " (if @checked* "ON" "OFF")))

        ;; $key — keyboard events
        (card "$key — Keyboard Events"
              "Focus the input and press any key. $key captures the key name."
              [:input {:type            "text"
                       :placeholder     "Press a key…"
                       :data-on:keydown (h/action (reset! (h/tab-cursor :last-key) $key))}]
              (result "Last key: " (if (seq @key*) @key* "none yet")))

        (card "Javascript injection — client side await for Enter keystroke"
              "Await the Enter keystroke to send the input value to the server."
              [:input {:type            "text"
                       :placeholder     "Type something…"
                       :data-on:keydown (h/expr (when (= $key "Enter")
                                                  (h/action (reset! (h/tab-cursor :value) $value))))}]
              (result "Server sees: " (if (seq @value*) @value* "nothing yet")))

        ;; $form-data — form submission
        (card "$form-data — Form Submission"
              "Submit the form. All named fields are sent as a map via $form-data."
              [:form {:data-on:submit__prevent
                      (h/action (reset! (h/tab-cursor :form-data) $form-data))}
               [:input {:name "name" :placeholder "Name"}]
               [:input {:name "email" :type "email" :placeholder "Email"}]
               [:select {:name "role"}
                [:option {:value "user"} "User"]
                [:option {:value "admin"} "Admin"]
                [:option {:value "editor"} "Editor"]]
               [:button {:type "submit"} "Submit"]]
              (when @form*
                [:div.result
                 [:strong "Server received:"]
                 [:pre (pr-str @form*)]]))))))
;; ---------------------------------------------------------------------------
;; Signals
;; ---------------------------------------------------------------------------

(defn signals-page [_]
  (letfn [(card [title desc & children]
            [:div.card [:h3 title] [:p.muted desc] children])
          (result [label content]
            [:p label [:strong content]])]
    (let [name*  (h/signal :user-name "")
          open?* (h/local-signal :open false)
          saved* (h/tab-cursor :saved-name "")]
      (layout
        "Signals"
        [:p "Signals are client-side reactive state managed by Datastar. "
         "They sync between the browser and server seamlessly."]

        ;; data-bind + data-text — pure client-side reactivity
        (card "data-bind + data-text"
              "Type below. The signal updates client-side instantly via data-bind."
              [:input {:data-bind name* :placeholder "Your name…"}]
              [:p "Hello, " [:span {:data-text @name*}]])

        ;; Reading signal in action — signal + server round-trip
        (card "Reading signals in actions"
              "Click Save. The action reads the signal value on the server."
              [:button {:data-on:click (h/action
                                         (reset! (h/tab-cursor :saved-name) @name*))}
               "Save name"]
              (result "Server saved: " (if (seq @saved*) @saved* "nothing yet")))

        ;; Reading signal in action with client params — both work together
        (card "Signals + client params"
              "Client params ($value) and signals work together in the same action."
              [:input {:type        "text"
                       :placeholder "Type and tab away…"
                       :data-on:change
                       (h/action
                         (reset! (h/tab-cursor :saved-name)
                                 (str @name* " (input: " $value ")")))}]
              (result "Combined saved: " (if (seq @saved*) @saved* "nothing yet")))

        ;; reset! signal from server — push update to client
        (card "Server-side signal reset"
              "Click Clear. The server resets the signal, pushing the change to the browser."
              [:button {:data-on:click (h/action (reset! name* ""))} "Clear name"]
              (result "Signal value: " [:span {:data-text @name*} ""]))

        ;; Async signal update — works outside action handlers
        (card "Async signal update"
              "Click Start. The action kicks off a background thread that updates the signal after a delay, showing that signals can be updated outside of a handler."
              [:button {:data-on:click
                        (h/action
                          (future
                            (Thread/sleep 1000)
                            (reset! name* "Updated from background thread!")))}
               "Start"]
              (result "Signal value: " [:span {:data-text @name*} ""]))

        ;; Local signal — client-only toggle
        (card "Local signal (client-only)"
              "Local signals never leave the browser. Toggle without a server round-trip."
              [:button {:data-on:click (h/expr (swap! open?* not))} "Toggle"]
              [:div {:data-show @open?* :style "display:none"}
               [:p "👋 This content is toggled by a local signal."]])))))

;; ---------------------------------------------------------------------------
;; Optimistic
;; ---------------------------------------------------------------------------

(defn optimistic-page [_]
  (letfn [(card [title desc & children]
            [:div.card [:h3 title] [:p.muted desc] children])]
    (let [bar* (h/session-cursor :bar-width 40)
          w*   (h/optimistic bar*)
          hue* (h/optimistic (h/session-cursor :hue 200) {:auto-commit? true})]
      (layout
        "Optimistic"
        [:p [:code "h/optimistic"] " pairs a cursor with a client-side signal: "
         "the client is authoritative while you interact, the cursor at rest. "
         "Open a second tab to watch committed values converge."]

        (card "Drag, then commit on release"
              "The bar tracks the slider every frame with zero server traffic;
               releasing commits to the session cursor."
              [:input {:type              "range"                   :min 0 :max 100
                       :data-bind         w*
                       :data-on:pointerup (h/action (h/commit! w*))}]
              [:div {:data-attr:style
                     (h/expr (str "width:" @w* "%;height:20px;background:#369;border-radius:4px"))}]
              [:p "Committed (server-rendered): " [:strong @bar*] "%"])

        (card "Auto-commit"
              "With :auto-commit? every action POST persists the riding value —
               a throttled no-op action is the entire write path."
              [:input {:type                          "range"        :min 0 :max 360
                       :data-bind                     hue*
                       :data-on:input__throttle.200ms (h/action nil)}]
              [:div {:data-attr:style
                     (h/expr (str "height:20px;border-radius:4px;background:hsl("
                                  @hue* ",70%,50%)"))}])))))

;; ---------------------------------------------------------------------------
;; Effects
;; ---------------------------------------------------------------------------

(defn effects-page [_]
  (letfn [(card [title desc & children]
            [:div.card [:h3 title] [:p.muted desc] children])
          (result [label content]
            [:p label [:strong content]])]
    (let [_items* (h/tab-cursor :items [])
          cookie* (h/tab-cursor :last-cookie "none")]
      (layout
        "Effects"
        [:p "Effects are escape hatches for actions that need to do more "
         "than mutate cursors: navigation, cookies, and client-side scripts."]

        ;; navigate! — create item then navigate to confirmation
        (card "navigate! — Redirect after action"
              "Click Create to save a new item and navigate to the home page."
              [:button {:data-on:click
                        (h/action {:as "create-and-navigate"}
                                  (swap! (h/tab-cursor :items) conj
                                         {:id   (count @(h/tab-cursor :items))
                                          :name "New Item"})
                                  (effects/navigate! :home))}
               "Create & Navigate Home"])

        ;; set-cookie! / delete-cookie!
        (card "set-cookie! / delete-cookie! — HTTP cookies"
              "Set or delete a cookie. The cookie value is read from the request on each render."
              [:button {:data-on:click
                        (h/action {:as "set-cookie"}
                                  (effects/set-cookie! "example-pref" "dark-mode"
                                                       {:max-age (* 60 60 24)})
                                  (reset! (h/tab-cursor :last-cookie) "dark-mode"))}
               "Set Cookie"]
              " "
              [:button {:data-on:click
                        (h/action {:as "delete-cookie"}
                                  (effects/delete-cookie! "example-pref")
                                  (reset! (h/tab-cursor :last-cookie) "deleted"))}
               "Delete Cookie"]
              (result "Cookie status: " @cookie*))

        ;; execute-script! — client-side JS
        (card "execute-script! — Client-side JavaScript"
              "Run arbitrary JS on the client. Use sparingly — most UI is better as cursors."
              [:input {:id "focus-target" :type "text" :placeholder "I'll get focused..."}]
              [:br]
              [:button {:data-on:click
                        (h/action {:as "focus-input"}
                                  (effects/execute-script! "document.getElementById('focus-target').focus()"))}
               "Focus the input"]
              " "
              [:button {:data-on:click
                        (h/action {:as "scroll-top"}
                                  (effects/execute-script! "window.scrollTo({top: 0, behavior: 'smooth'})"))}
               "Scroll to top"])

        ;; Multiple effects in one action
        (card "Multiple effects — composing effects"
              "A single action can emit multiple effects."
              [:button {:data-on:click
                        (h/action {:as "multi-effect"}
                                  (effects/set-cookie! "multi-test" "combined"
                                                       {:max-age 3600})
                                  (effects/execute-script! "console.log('Effects composed!')")
                                  (reset! (h/tab-cursor :last-cookie) "combined"))}
               "Set cookie + run script"])))))

;; ---------------------------------------------------------------------------
;; Uploads
;; ---------------------------------------------------------------------------

(defn- save-upload!
  "Pretend to save an uploaded file.  Real apps would move (:tempfile f) to
   permanent storage; here we just report its metadata back as the action
   result (which becomes the status ref's :result)."
  [f]
  (when f
    {:filename     (:filename f)
     :size         (:size f)
     :content-type (:content-type f)}))

(defn uploads-page [_]
  (letfn [(card [title desc & children]
            [:div.card [:h3 title] [:p.muted desc] children])
          ;; A map-valued signal holds {:phase :percent :result ...}.  In render
          ;; @sig* is the Datastar expression string ("$formUpload"), so nested
          ;; fields are reached by string-building the Datastar path — keyword
          ;; access like (:phase @sig*) would be evaluated as Clojure, not
          ;; compiled to "$formUpload.phase".
          (field [sig* suffix] (str @sig* suffix))
          (phase-is [sig* p] (str @sig* ".phase === '" p "'"))]
    ;; A signal status ref gets both client-side transfer progress and
    ;; server-side phase/result; a cursor ref would give server status only.
    (let [form*  (h/signal :form-upload {:phase :idle :percent 0})
          quick* (h/signal :quick-upload {:phase :idle :percent 0})]
      (layout
        "Uploads"
        [:p "File uploads are just " [:code "h/action"] "s whose body uses "
         [:code "$form"] " or " [:code "$files"] ". The action posts a real "
         "multipart request; status + progress flow through an " [:code ":upload"]
         " ref (a signal here, for live progress)."]

        ;; $form — whole form (fields + file) on submit
        (card "$form — submit a form with a file"
              "Submit sends every named field plus the file as one multipart request."
              [:form {:data-on:submit__prevent
                      (h/action {:upload form*}
                                (save-upload! (:avatar $form)))}
               [:input {:name "name" :placeholder "Your name"}]
               " "
               [:input {:type "file" :name "avatar"}]
               " "
               [:button {:type "submit"} "Upload"]]
              ;; Live transfer progress (client-side signal, no round-trip)
              [:p {:data-show (phase-is form* "uploading")}
               "Uploading… "
               [:progress {:max 100 :data-attr:value (field form* ".percent")}]]
              [:p {:data-show (phase-is form* "processing")} "Processing…"]
              [:p {:data-show (phase-is form* "done")}
               "✓ Saved "
               [:strong {:data-text (field form* ".result.filename")}]
               " ("
               [:span {:data-text (field form* ".result.size")}]
               " bytes)"]
              [:p.muted "Live status: "
               [:code {:data-text (str "JSON.stringify(" @form* ")")}]])

        ;; $files — upload immediately when a file is chosen (input change)
        (card "$files — upload on selection"
              "Choosing a file fires data-on:change with $files (the input's files),
               uploading immediately — no form, no submit button."
              [:input {:type           "file"
                       :data-on:change (h/action {:upload quick*}
                                                 (save-upload! (first $files)))}]
              [:p {:data-show (phase-is quick* "uploading")}
               "Uploading… "
               [:progress {:max 100 :data-attr:value (field quick* ".percent")}]]
              [:p {:data-show (phase-is quick* "done")}
               "✓ Saved "
               [:strong {:data-text (field quick* ".result.filename")}]])))))

;; ---------------------------------------------------------------------------
;; Client components (compiled via embedded Squint)
;; ---------------------------------------------------------------------------

;; A client-side web component written in a CLJS dialect (Squint), compiled to
;; JS on the JVM at load time and served at /hyper/components.js — no Node,
;; no build step.
;;
;; - Attributes are the boundary: the server pushes :value/:history etc. as
;;   serialized attributes; the component re-renders ONLY when an attribute
;;   string actually changes (watch the "client render @ ..." timestamp stay
;;   frozen during unrelated server re-renders).
;; - Events are the channel out: clicking the gauge emits "gauge-selected"
;;   which the server handles as a normal data-on action via $detail.
(h/defc temp-gauge
  "A client-side temperature gauge with a history sparkline."
  [{:keys [value max label history]}]

  (event ::selected [_e]
    (emit "gauge-selected" {:label label :value value}))

  (render
    (let [pct (js/Math.round (* 100 (/ value max)))]
      [:div {:style "border:2px solid #888;border-radius:8px;padding:12px;cursor:pointer;user-select:none"
             :on    {:click ::selected}}
       [:div {:style "display:flex;justify-content:space-between;align-items:baseline"}
        [:strong label]
        [:span (str value "° (" pct "%)")]]
       [:div {:style "background:#eee;border-radius:4px;height:14px;margin:8px 0;overflow:hidden"}
        [:div {:style (str "height:100%;width:" pct "%;transition:width .3s;"
                           "background:" (if (> pct 75) "#d33" "#3a7"))}]]
       [:div {:style "display:flex;gap:2px;align-items:flex-end;height:30px"}
        (for [v history]
          [:div {:style (str "flex:1;background:#9bc;height:"
                             (js/Math.round (* 30 (/ v max))) "px")}])]
       [:small {:style "color:#999"}
        (str "client render @ " (.toLocaleTimeString (js/Date.)))]])))

;; Seamless mode: d3 owns the DOM inside the component, hyper owns the data.
;; The server pushes :values through the attribute boundary; `update` hands
;; each change to d3, which animates the delta. The chart instance survives
;; arbitrary server re-renders — `mount` runs exactly once.
(h/defc live-bars
  "An animated d3 bar chart driven by server state."
  {:require [["https://esm.sh/d3@7" :as d3]]}
  [{:keys [values]}]

  (render
    [:svg {:width 560 :height 180 :style "display:block"}])

  (mount [root]
    (let [svg  (d3/select (.querySelector root "svg"))
          draw (fn [vs]
                 (-> svg
                     (.selectAll "rect")
                     (.data vs)
                     (.join "rect")
                     (.attr "x" (fn [_ i] (* i 46)))
                     (.attr "width" 40)
                     (.transition)
                     (.duration 500)
                     (.attr "y" (fn [v] (- 180 (* v 3))))
                     (.attr "height" (fn [v] (* v 3)))
                     (.attr "fill" (fn [v] (if (> v 40) "#d33" "#369")))))]
      (set! (.-draw ctx) draw)
      (draw values)))

  (update [_root]
    ((.-draw ctx) values)))

(defn components-page [_]
  (letfn [(card [title desc & children]
            [:div.card [:h3 title] [:p.muted desc] children])]
    (let [temp*     (h/tab-cursor :temp 18)
          history*  (h/tab-cursor :temp-history [18])
          selected* (h/tab-cursor :gauge-selected nil)
          noise*    (h/tab-cursor :noise 0)]
      (layout
        "Client Components"
        [:p "A web component written in a CLJS dialect (Squint), compiled to "
         "JS on the server — no Node, no build step. Attributes in, events out."]

        (card "Attributes as the boundary"
              "The server pushes data into attributes; the component renders client-side in shadow DOM."
              (temp-gauge {:value   @temp*
                           :max     40
                           :label   "Server temp"
                           :history @history*
                           :data-on:gauge-selected
                           (h/action {:as "gauge-selected"}
                                     (reset! (h/tab-cursor :gauge-selected) $detail))})
              [:p
               [:button {:data-on:click (h/action {:as "temp-up"}
                                                  (h/batch
                                                    (swap! (h/tab-cursor :temp) #(min 40 (+ % 3)))
                                                    (swap! (h/tab-cursor :temp-history)
                                                           #(vec (take-last 12 (conj % @(h/tab-cursor :temp)))))))}
                "Hotter"]
               " "
               [:button {:data-on:click (h/action {:as "temp-down"}
                                                  (h/batch
                                                    (swap! (h/tab-cursor :temp) #(max 0 (- % 3)))
                                                    (swap! (h/tab-cursor :temp-history)
                                                           #(vec (take-last 12 (conj % @(h/tab-cursor :temp)))))))}
                "Colder"]])

        (card "Events as the channel out"
              "Click the gauge above — it emits a CustomEvent the server handles via $detail."
              [:p "Last emitted: " [:strong (if @selected* (pr-str @selected*) "nothing yet")]])

        (card "The change gate"
              "This button re-renders the whole page server-side, but the gauge's attributes
               don't change — note its client render timestamp stays frozen."
              [:button {:data-on:click (h/action {:as "noise"} (swap! (h/tab-cursor :noise) inc))}
               "Unrelated state change"]
              [:p "Server renders forced: " [:strong @noise*]])

        (card "Seamless mode — d3 transitions"
              "The server pushes data; d3 animates the delta. The chart instance is
               created once (mount) and every change flows through update — it survives
               all server re-renders, including the change-gate button above."
              (live-bars {:values @(h/tab-cursor :bars [12 30 22 45 17 38 25 50 9 33 41 20])})
              [:p
               [:button {:data-on:click
                         (h/action {:as "shuffle-bars"}
                                   (reset! (h/tab-cursor :bars)
                                           (vec (repeatedly 12 #(+ 5 (rand-int 50))))))}
                "Randomize"]
               " "
               [:button {:data-on:click
                         (h/action {:as "rotate-bars"}
                                   (swap! (h/tab-cursor :bars)
                                          #(vec (concat (rest %) [(first %)]))))}
                "Rotate"]])))))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(def routes
  [["/" {:name  :home
         :title "Examples"
         :get   #'home-page}]
   ["/counters"
    {:parameters {:query [:map [:count {:optional true} :int]]}
     :name       :counters
     :title      (fn [_] (str "The count is " @(h/session-cursor :count 0)))
     :get        #'counters-page}]
   ["/forms"
    {:name  :forms
     :title "Forms & Inputs"
     :get   #'forms-page}]
   ["/signals"
    {:name  :signals
     :title "Signals"
     :get   #'signals-page}]
   ["/optimistic"
    {:name  :optimistic
     :title "Optimistic"
     :get   #'optimistic-page}]
   ["/effects"
    {:name  :effects
     :title "Effects"
     :get   #'effects-page}]
   ["/uploads"
    {:name  :uploads
     :title "Uploads"
     :get   #'uploads-page}]
   ["/components"
    {:name  :components
     :title "Client Components"
     :get   #'components-page}]])

;; ---------------------------------------------------------------------------
;; Styles
;; ---------------------------------------------------------------------------

(def styles
  [:style
   "* { box-sizing: border-box; }
    body { font-family: system-ui, sans-serif; margin: 0; }
    .container { max-width: 800px; margin: 0 auto; padding: 20px; }
    nav { margin-bottom: 24px; padding-bottom: 12px; border-bottom: 1px solid #ddd; }
    .card { border: 1px solid #ccc; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
    .card h3 { margin-top: 0; }
    .muted { color: #666; margin-top: 0; }
    .result { margin-top: 12px; background: #f5f5f5; padding: 12px; border-radius: 4px; }
    .result pre { margin: 8px 0 0; }
    input, select { padding: 8px; font-size: 16px; }
    button { padding: 8px 16px; cursor: pointer; }"])

;; ---------------------------------------------------------------------------
;; Server
;; ---------------------------------------------------------------------------

(def app
  (h/start! (h/create-handler #'routes :head [styles] :source-map? true) {:port 4000}))

(comment
  (h/stop! app))
