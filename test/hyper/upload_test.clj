(ns hyper.upload-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.client-params :as client-params]
            [hyper.context :as context]
            [hyper.server :as server]
            [hyper.signal :as signal]
            [hyper.state :as state]
            [hyper.uploads :as uploads]))

;; ===========================================================================
;; Client params
;; ===========================================================================

(deftest test-multipart-client-params
  (testing "$form and $files are defined and flagged multipart"
    (is (contains? (client-params/defined-client-params) '$form))
    (is (contains? (client-params/defined-client-params) '$files))
    (is (true? (client-params/multipart-param? '$form)))
    (is (true? (client-params/multipart-param? '$files))))
  (testing "ordinary params are not multipart"
    (is (false? (client-params/multipart-param? '$value)))
    (is (false? (client-params/multipart-param? '$form-data))))
  (testing "$form / $files :js evaluate to a FormData and carry server keys"
    (is (= "form" (:key (client-params/client-param '$form))))
    (is (= "files" (:key (client-params/client-param '$files))))
    (is (string/includes? (:js (client-params/client-param '$form)) "FormData"))))

;; ===========================================================================
;; Multipart parsing
;; ===========================================================================

(def ^:private a-file
  {:filename "a.png" :content-type "image/png" :tempfile :A :size 10})

(def ^:private b-file
  {:filename "b.png" :content-type "image/png" :tempfile :B :size 20})

(deftest test-parse-multipart
  (testing "fields are keywordized and files inline; :files flattens vectors"
    (let [parsed (uploads/parse-multipart {"name"   "Alice"
                                           "avatar" a-file
                                           "docs"   [a-file b-file]})]
      (is (= "Alice" (get-in parsed [:form :name])))
      (is (= a-file (get-in parsed [:form :avatar])))
      (is (= [a-file b-file] (get-in parsed [:form :docs])))
      ;; avatar + both docs, flattened
      (is (= [a-file a-file b-file] (:files parsed)))))
  (testing "empty / nil multipart params"
    (is (= {:form {} :files []} (uploads/parse-multipart nil)))
    (is (= {:form {} :files []} (uploads/parse-multipart {})))))

(deftest test-form-fields->signals
  (testing "non-file fields fold into kebab-keyword signals; files excluded"
    (is (= {:user-name "Bob"}
           (uploads/form-fields->signals {"userName" "Bob" "avatar" a-file})))
    (is (= {} (uploads/form-fields->signals {"avatar" a-file})))))

;; ===========================================================================
;; Status ref
;; ===========================================================================

(deftest test-set-status!
  (testing "merges into an existing value, preserving untouched keys"
    (let [ref (atom {:phase :uploading :percent 73})]
      (uploads/set-status! ref {:phase :processing :percent 100})
      (is (= {:phase :processing :percent 100} @ref))
      (uploads/set-status! ref {:phase :done :result {:ok 1}})
      ;; percent preserved from the prior merge
      (is (= {:phase :done :percent 100 :result {:ok 1}} @ref))))
  (testing "seeds from default-status when ref is empty"
    (let [ref (atom nil)]
      (uploads/set-status! ref {:phase :done})
      (is (= {:phase :done :percent 0} @ref))))
  (testing "nil ref is a no-op (fire-and-forget upload)"
    (is (nil? (uploads/set-status! nil {:phase :done})))))

;; ===========================================================================
;; Client expression
;; ===========================================================================

(deftest test-build-upload-expr
  (let [app*   (atom (state/init-state))
        _      (state/get-or-create-tab! app* "s" "t")
        sigref (signal/create-signal app* "t" :avatar-upload {:phase :idle :percent 0})
        curref (state/tab-cursor app* "t" :status {:phase :idle})]
    (testing "a signal ref wires progress/phase callbacks to its nested signal"
      (let [expr (uploads/build-upload-expr "a_t_1" "new FormData(evt.target.closest('form'))"
                                            sigref "" nil)]
        (is (string/starts-with? expr "hyper.upload("))
        (is (string/includes? expr "/hyper/upload?action-id=a_t_1"))
        (is (string/includes? expr ",el:el") "the helper reads the render from the element")
        (is (string/includes? expr "$avatarUpload.phase='uploading'"))
        (is (string/includes? expr "$avatarUpload.percent=p"))))
    (testing "a cursor ref has no client callbacks (server-driven status)"
      (let [expr (uploads/build-upload-expr "a_t_1" "FD" curref "" nil)]
        (is (= "hyper.upload(FD,{url:'/hyper/upload?action-id=a_t_1',el:el})" expr))))
    (testing "a guard gates the upload"
      (let [expr (uploads/build-upload-expr "a_t_1" "FD" curref "" "evt.key === 'Enter'")]
        (is (string/starts-with? expr "evt.key === 'Enter' && hyper.upload("))))
    (testing "base-path is prepended to the endpoint"
      (let [expr (uploads/build-upload-expr "a_t_1" "FD" curref "/app" nil)]
        (is (string/includes? expr "/app/hyper/upload?action-id=a_t_1"))))))

(deftest test-build-expr-dispatch
  (let [app* (atom (state/init-state))
        _    (state/get-or-create-tab! app* "s" "t")
        ref  (state/tab-cursor app* "t" :status {:phase :idle})]
    (testing "multipart param -> upload expression"
      (let [expr (uploads/build-expr "a_t_1" {'$form (client-params/client-param '$form)}
                                     nil "" ref)]
        (is (string/starts-with? expr "hyper.upload("))))
    (testing "no multipart param -> ordinary @hyperAction expression"
      (let [expr (uploads/build-expr "a_t_1" {'$value (client-params/client-param '$value)}
                                     nil "" nil)]
        (is (string/starts-with? expr "@hyperAction("))))))

;; ===========================================================================
;; Upload endpoint handler
;; ===========================================================================

(defn- register-upload-action!
  "Register an upload action the way the macro does in a render of tab
   `tab-id`'s page: an fn plus an :upload-ref.  Returns the render's version."
  ([app* tab-id action-id ref f]
   (register-upload-action! app* tab-id action-id ref f ""))
  ([app* tab-id action-id ref f html]
   (binding [context/*rendered-actions* (atom {})]
     (actions/register-action! app* "s" tab-id f action-id {:upload-ref ref})
     (actions/record-render! app* tab-id {:html html :actions @context/*rendered-actions*}))))

(deftest test-upload-handler-success
  (testing "runs the action with parsed files and transitions status to :done"
    (let [app*     (atom (state/init-state))
          _        (state/get-or-create-tab! app* "s" "t")
          ref      (state/tab-cursor app* "t" :avatar-status {:phase :idle :percent 0})
          received (atom nil)
          handler  (#'server/upload-handler app*)]
      (register-upload-action! app* "t" "a_t_1" ref
                               (fn [{:keys [form files]}]
                                 (reset! received {:form form :files files})
                                 {:saved (mapv :filename files)}))
      (let [resp (handler {:query-params     {"action-id" "a_t_1" "tab-id" "t"}
                           :multipart-params {"name"   "Alice"
                                              "avatar" a-file}})]
        (is (= 204 (:status resp)))
        ;; the action fn saw the parsed form + files
        (is (= "Alice" (get-in @received [:form :name])))
        (is (= [a-file] (:files @received)))
        ;; status ref reached :done with the handler's return as :result
        (is (= :done (:phase @ref)))
        (is (= 100 (:percent @ref)))
        (is (= {:saved ["a.png"]} (:result @ref)))))))

(deftest test-upload-handler-error
  (testing "an exception in the action transitions status to :error and 500s"
    (let [app*    (atom (state/init-state))
          _       (state/get-or-create-tab! app* "s" "t")
          ref     (state/tab-cursor app* "t" :status {:phase :idle :percent 0})
          handler (#'server/upload-handler app*)]
      (register-upload-action! app* "t" "a_t_1" ref
                               (fn [_] (throw (ex-info "boom" {}))))
      (let [resp (handler {:query-params     {"action-id" "a_t_1" "tab-id" "t"}
                           :multipart-params {"avatar" a-file}})]
        (is (= 500 (:status resp)))
        (is (= :error (:phase @ref)))
        (is (= "boom" (:error @ref)))))))

(deftest test-upload-handler-missing-action
  (testing "404 for an unknown action-id, 400 for a missing one"
    (let [app*    (atom (state/init-state))
          handler (#'server/upload-handler app*)]
      (is (= 404 (:status (handler {:query-params {"action-id" "nope" "tab-id" "t"}}))))
      (is (= 400 (:status (handler {:query-params {}})))))))

(deftest test-upload-from-an-outdated-render
  (testing "an upload runs the action of the render it was made on"
    (let [app*    (atom (state/init-state))
          _       (state/get-or-create-tab! app* "s" "t")
          ran     (atom [])
          handler (#'server/upload-handler app*)
          v1      (register-upload-action! app* "t" "a_t_1" nil (fn [_] (swap! ran conj :first)) "first")
          v2      (register-upload-action! app* "t" "a_t_1" nil (fn [_] (swap! ran conj :second)) "second")]
      (is (not= v1 v2))
      (is (= 204 (:status (handler {:query-params     {"action-id" "a_t_1" "tab-id" "t" "v" (str v1)}
                                    :multipart-params {"avatar" a-file}}))))
      (is (= [:first] @ran))
      (testing "and without a version, the latest page render's"
        (handler {:query-params     {"action-id" "a_t_1" "tab-id" "t"}
                  :multipart-params {"avatar" a-file}})
        (is (= [:first :second] @ran))))))

(deftest test-upload-route-is-wired
  (testing "create-handler mounts a POST /hyper/upload route"
    (let [app*    (atom (state/init-state))
          handler (server/create-handler [["/" {:name :home :get (fn [_] [:div "h"])}]]
                                         app*)]
      ;; A GET on the upload route is a 405 (route exists, wrong method),
      ;; not a 404 — proving the route is mounted.
      (is (= 405 (:status (handler {:uri "/hyper/upload" :request-method :get})))))))
