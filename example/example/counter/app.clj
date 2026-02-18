(ns example.counter.app
  (:require [hyper.core :as h]
            [hyper.state]))

(defn counter
  "Render a counter widget for any cursor."
  [label description cursor*]
  [:div {:style "border: 1px solid #ccc; border-radius: 8px; padding: 16px; margin-bottom: 16px;"}
   [:h2 {:style "margin-top: 0;"} label ": " @cursor*]
   [:p {:style "color: #666; margin-top: 0;"} description]
   [:button (h/action (swap! cursor* inc)) "+"]
   " "
   [:button (h/action (swap! cursor* dec)) "–"]
   " "
   [:button (h/action (reset! cursor* 0)) "Reset"]])

;; BUG: Title isn't updated on route var definition update
;; BUG: URL isn't updated anymore


(def routes
  [["/" {:name :home
         :title "Examples"
         :get
         (fn [_]
           [:div
            [:h1 "Hyper Examples"]
            [:ul
             [:a (h/navigate :counters) "Counters"]]])}]
   ["/counters"
    {:parameters {:query [:map [:count {:optional true} :int]]}
     :name :counters
     :title (fn [_]
              (str "The count is " @(h/session-cursor :count 0)))
     :get
     (let [external* (atom 0)]
       (fn [_]
         (let [global*  (h/global-cursor :count 0)
               session* (h/session-cursor :count 0)
               tab*     (h/tab-cursor :count 0)
               url*     (h/path-cursor :count 0)]
           [:div
            [:h1 "Hyper Counters"]
            [:p "Four counters, each backed by a different scope of state. "
             "Open multiple tabs to see how they differ."]

            (counter "Global" "Shared across every session and tab. Change it here and watch it update everywhere." global*)
            (counter "External" "Implemented via an external watch!" external*)
            (counter "Session" "Shared across all tabs in this browser session (same cookie)." session*)
            (counter "Tab" "Private to this tab only." tab*)
            (counter "URL" "Stored in the query string — try bookmarking or sharing the link." url*)])))}]])

(def state*
  (atom (hyper.state/init-state)))

(def app
  (h/start! (h/create-handler #'routes state*) {:port 4000}))

(comment
  (reset! state* (hyper.state/init-state))
  (h/stop! app))
