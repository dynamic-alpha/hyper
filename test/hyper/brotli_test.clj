(ns hyper.brotli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.brotli :as br]
            [hyper.render :as render]
            [hyper.state :as state]
            [org.httpkit.server :as http]))

;; ---------------------------------------------------------------------------
;; One-shot compression
;; ---------------------------------------------------------------------------

(deftest test-compress-roundtrip
  (testing "compress and decompress roundtrip with default options"
    (let [input        "Hello, brotli!"
          compressed   (br/compress input)
          decompressed (br/decompress compressed)]
      (is (bytes? compressed))
      (is (= input decompressed))))

  (testing "compressed output is smaller than input for compressible data"
    (let [input      (apply str (repeat 100 "The quick brown fox jumps over the lazy dog. "))
          compressed (br/compress input)]
      (is (< (alength compressed) (count (.getBytes input "UTF-8"))))))

  (testing "roundtrip with quality 11 (max)"
    (let [input      "<html><body><h1>Hello World</h1></body></html>"
          compressed (br/compress input :quality 11)]
      (is (= input (br/decompress compressed)))))

  (testing "roundtrip with quality 1 (fast)"
    (let [input      "<div id=\"hyper-app\"><p>Fast compression</p></div>"
          compressed (br/compress input :quality 1)]
      (is (= input (br/decompress compressed)))))

  (testing "compresses byte array input"
    (let [input        "byte array input"
          input-bytes  (.getBytes input "UTF-8")
          compressed   (br/compress input-bytes)
          decompressed (br/decompress compressed)]
      (is (= input decompressed))))

  (testing "handles empty string"
    (let [compressed   (br/compress "")
          decompressed (br/decompress compressed)]
      (is (= "" decompressed))))

  (testing "handles unicode content"
    (let [input        "こんにちは世界 🌍 Ω≈ç√∫"
          compressed   (br/compress input)
          decompressed (br/decompress compressed)]
      (is (= input decompressed)))))

;; ---------------------------------------------------------------------------
;; Streaming compression
;; ---------------------------------------------------------------------------

(deftest test-streaming-compression
  (testing "single chunk produces compressed bytes"
    (let [out       (br/byte-array-out-stream)
          br-stream (br/compress-out-stream out)]
      (let [compressed (br/compress-stream out br-stream "Hello, streaming!")]
        (is (bytes? compressed))
        (is (pos? (alength compressed))))
      (br/close-stream br-stream)))

  (testing "multiple chunks concatenated decompress to full content after close"
    (let [out       (br/byte-array-out-stream)
          br-stream (br/compress-out-stream out :window-size 18)
          combined  (java.io.ByteArrayOutputStream.)
          chunk1    (br/compress-stream out br-stream "first.")
          _         (.write combined chunk1)
          chunk2    (br/compress-stream out br-stream "second.")
          _         (.write combined chunk2)
          chunk3    (br/compress-stream out br-stream "third.")
          _         (.write combined chunk3)]
      ;; Each chunk should produce non-empty output
      (is (pos? (alength chunk1)))
      (is (pos? (alength chunk2)))
      (is (pos? (alength chunk3)))
      ;; Close the stream so the brotli trailer is flushed
      (br/close-stream br-stream)
      ;; Any final bytes from closing
      (.write combined (.toByteArray out))
      ;; Now decompressing the full stream should yield everything
      (let [full-text (br/decompress (.toByteArray combined))]
        (is (.contains full-text "first."))
        (is (.contains full-text "second."))
        (is (.contains full-text "third.")))))

  (testing "streaming achieves better compression on repeated content"
    ;; Repeated HTML fragments should compress better with a shared LZ77 window
    (let [fragment        "<div id=\"hyper-app\" data-hyper-url=\"/\"><h1>Count: 1</h1><button>+1</button></div>"
          out-streaming   (br/byte-array-out-stream)
          br-stream       (br/compress-out-stream out-streaming :window-size 18)
          streaming-sizes (mapv (fn [i]
                                  (let [sse (str "event: datastar-patch-elements\ndata: elements "
                                                 (str/replace fragment "1" (str i))
                                                 "\n\n")]
                                    (alength (br/compress-stream out-streaming br-stream sse))))
                                (range 10))
          oneshot-sizes   (mapv (fn [i]
                                  (let [sse (str "event: datastar-patch-elements\ndata: elements "
                                                 (str/replace fragment "1" (str i))
                                                 "\n\n")]
                                    (alength (br/compress sse :quality 5))))
                                (range 10))]
      ;; Later streaming chunks should be smaller than one-shot because
      ;; the LZ77 window has seen the repeated structure
      (is (< (apply + (drop 2 streaming-sizes))
             (apply + (drop 2 oneshot-sizes))))
      (br/close-stream br-stream))))

(deftest test-close-stream
  (testing "close-stream is safe on nil"
    (is (nil? (br/close-stream nil))))

  (testing "close-stream closes without error"
    (let [out       (br/byte-array-out-stream)
          br-stream (br/compress-out-stream out)]
      (br/close-stream br-stream)
      ;; Calling close again should be safe
      (br/close-stream br-stream))))

;; ---------------------------------------------------------------------------
;; Ring middleware
;; ---------------------------------------------------------------------------

(deftest test-wrap-brotli-middleware
  (testing "compresses string body when client accepts br"
    (let [handler  (br/wrap-brotli
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body    "<html><body><h1>Hello</h1></body></html>"}))
          response (handler {:headers {"accept-encoding" "gzip, deflate, br"}})]
      (is (= "br" (get-in response [:headers "Content-Encoding"])))
      (is (bytes? (:body response)))
      (is (= "<html><body><h1>Hello</h1></body></html>"
             (br/decompress (:body response))))))

  (testing "does not compress when client does not accept br"
    (let [handler  (br/wrap-brotli
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "text/html"}
                        :body    "<html>hello</html>"}))
          response (handler {:headers {"accept-encoding" "gzip, deflate"}})]
      (is (nil? (get-in response [:headers "Content-Encoding"])))
      (is (string? (:body response)))))

  (testing "does not compress when no Accept-Encoding header"
    (let [handler  (br/wrap-brotli
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "text/html"}
                        :body    "<html>hello</html>"}))
          response (handler {:headers {}})]
      (is (nil? (get-in response [:headers "Content-Encoding"])))
      (is (string? (:body response)))))

  (testing "does not compress non-string bodies"
    (let [handler  (br/wrap-brotli
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "application/octet-stream"}
                        :body    (.getBytes "binary" "UTF-8")}))
          response (handler {:headers {"accept-encoding" "br"}})]
      (is (nil? (get-in response [:headers "Content-Encoding"])))
      (is (bytes? (:body response)))))

  (testing "does not double-compress already-encoded responses"
    (let [handler  (br/wrap-brotli
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type"     "text/html"
                                  "Content-Encoding" "gzip"}
                        :body    "already compressed"}))
          response (handler {:headers {"accept-encoding" "br"}})]
      (is (= "gzip" (get-in response [:headers "Content-Encoding"])))
      (is (= "already compressed" (:body response)))))

  (testing "passes through nil responses"
    (let [handler  (br/wrap-brotli (fn [_req] nil))
          response (handler {:headers {"accept-encoding" "br"}})]
      (is (nil? response)))))

;; ---------------------------------------------------------------------------
;; SSE channel brotli integration
;; ---------------------------------------------------------------------------

(deftest test-sse-channel-with-brotli
  (testing "register with compress? true creates brotli streams"
    (let [app-state* (atom (state/init-state))
          tab-id     "test-br-tab-1"]
      (state/get-or-create-tab! app-state* "sess" tab-id)
      (render/register-sse-channel! app-state* tab-id {:mock true} true)

      (let [tab-data (get-in @app-state* [:tabs tab-id])]
        (is (some? (:br-out tab-data)))
        (is (some? (:br-stream tab-data)))
        (is (= {:mock true} (:sse-channel tab-data))))

      (render/unregister-sse-channel! app-state* tab-id)))

  (testing "register with compress? false does not create brotli streams"
    (let [app-state* (atom (state/init-state))
          tab-id     "test-br-tab-2"]
      (state/get-or-create-tab! app-state* "sess" tab-id)
      (render/register-sse-channel! app-state* tab-id {:mock true} false)

      (let [tab-data (get-in @app-state* [:tabs tab-id])]
        (is (nil? (:br-out tab-data)))
        (is (nil? (:br-stream tab-data)))
        (is (= {:mock true} (:sse-channel tab-data))))

      (render/unregister-sse-channel! app-state* tab-id)))

  (testing "unregister cleans up brotli streams"
    (let [app-state* (atom (state/init-state))
          tab-id     "test-br-tab-3"]
      (state/get-or-create-tab! app-state* "sess" tab-id)
      (render/register-sse-channel! app-state* tab-id {:mock true} true)

      ;; Verify streams exist
      (is (some? (get-in @app-state* [:tabs tab-id :br-stream])))

      (render/unregister-sse-channel! app-state* tab-id)

      (is (nil? (get-in @app-state* [:tabs tab-id :br-out])))
      (is (nil? (get-in @app-state* [:tabs tab-id :br-stream]))))))

(deftest test-send-sse-with-brotli
  (testing "send-sse! compresses when brotli streams are present"
    (let [app-state* (atom (state/init-state))
          tab-id     "test-br-send-1"
          sent       (atom [])]
      (state/get-or-create-tab! app-state* "sess" tab-id)
      (render/register-sse-channel! app-state* tab-id {:mock true} true)

      (with-redefs [org.httpkit.server/send! (fn [_ch data _close?]
                                               (swap! sent conj data)
                                               true)]
        (let [message "event: datastar-patch-elements\ndata: elements <div>Hello</div>\n\n"]
          (render/send-sse! app-state* tab-id message)

          (is (= 1 (count @sent)))
          (let [payload (first @sent)]
                ;; Should be raw compressed bytes — the Content-Encoding header
                ;; was set on the initial response, subsequent sends are just
                ;; data frames in the same brotli stream.
            (is (bytes? payload))
            (is (pos? (alength ^bytes payload))))))

      (render/unregister-sse-channel! app-state* tab-id)))

  (testing "send-sse! sends plain text when no brotli streams"
    (let [app-state* (atom (state/init-state))
          tab-id     "test-br-send-2"
          sent       (atom [])]
      (state/get-or-create-tab! app-state* "sess" tab-id)
      (render/register-sse-channel! app-state* tab-id {:mock true} false)

      (with-redefs [org.httpkit.server/send! (fn [_ch data _close?]
                                               (swap! sent conj data)
                                               true)]
        (let [message "event: datastar-patch-elements\ndata: elements <div>Hello</div>\n\n"]
          (render/send-sse! app-state* tab-id message)

          (is (= 1 (count @sent)))
          ;; Should be the raw string, not a map
          (is (= message (first @sent)))))

      (render/unregister-sse-channel! app-state* tab-id))))
