(ns example.app
  (:require [hyper.core :as h]
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
    [:a (h/navigate :signals) "Signals"]]
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
                       :data-on:keydown (h/action {:when "evt.key === 'Enter'"} (reset! (h/tab-cursor :value) $value))}]
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
              [:button {:data-on:click (str @open?* " = !" @open?*)} "Toggle"]
              [:div {:data-show @open?* :style "display:none"}
               [:p "👋 This content is toggled by a local signal."]])))))

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
     :get   #'signals-page}]])

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
  (h/start! (h/create-handler #'routes :head [styles]) {:port 4000}))

(comment
  (h/stop! app))
