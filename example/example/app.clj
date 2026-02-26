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
    [:a (h/navigate :forms) "Forms & Inputs"]]
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
     :get   #'forms-page}]])

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
