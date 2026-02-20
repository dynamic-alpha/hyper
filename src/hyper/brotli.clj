(ns hyper.brotli
  "Brotli compression for HTTP responses and SSE streams.

   Provides both one-shot compression (for page responses, via middleware)
   and streaming compression (for SSE connections where the LZ77 window is
   maintained across chunks for better ratios)."
  (:import (com.aayushatharva.brotli4j Brotli4jLoader)
           (com.aayushatharva.brotli4j.encoder Encoder Encoder$Parameters
                                               Encoder$Mode BrotliOutputStream)
           (com.aayushatharva.brotli4j.decoder BrotliInputStream)
           (java.io ByteArrayInputStream ByteArrayOutputStream IOException)))

(defonce ensure-br
  (Brotli4jLoader/ensureAvailability))

(defn encoder-params
  "Build encoder parameters.
   quality: 0-11 (default 5 for streaming, 11 for static)
   window-size: 0 or 10-24 (default 24)"
  ^Encoder$Parameters [{:keys [quality window-size]}]
  (doto (Encoder$Parameters/new)
    (.setMode Encoder$Mode/TEXT)
    (.setWindow (or window-size 24))
    (.setQuality (or quality 5))))

(defn compress
  "One-shot compress a string or byte array. Returns a byte array.
   Suitable for full page responses where max compression is preferred."
  ^bytes [data & {:as opts}]
  (let [input (if (string? data)
                (.getBytes ^String data "UTF-8")
                ^bytes data)]
    (Encoder/compress input (encoder-params opts))))

;; ---------------------------------------------------------------------------
;; Streaming compression (for SSE)
;; ---------------------------------------------------------------------------

(defn byte-array-out-stream
  "Create a ByteArrayOutputStream for use with compress-out-stream."
  ^ByteArrayOutputStream []
  (ByteArrayOutputStream.))

(defn compress-out-stream
  "Create a BrotliOutputStream wrapping a ByteArrayOutputStream.
   Used for streaming compression where the LZ77 window is maintained
   across multiple chunks (e.g. SSE events)."
  ^BrotliOutputStream [^ByteArrayOutputStream out-stream & {:as opts}]
  (BrotliOutputStream. out-stream (encoder-params opts) 16384))

(defn compress-stream
  "Write a string chunk into a streaming brotli compressor, flush, and
   return the compressed bytes. Resets the underlying ByteArrayOutputStream
   so the next call only returns newly compressed data.

   The BrotliOutputStream maintains its LZ77 window across calls, giving
   better compression ratios for repeated SSE fragments."
  ^bytes [^ByteArrayOutputStream out ^BrotliOutputStream br ^String chunk]
  (doto br
    (.write (.getBytes chunk "UTF-8"))
    (.flush))
  (let [result (.toByteArray out)]
    (.reset out)
    result))

(defn decompress
  "Decompress a complete brotli-compressed byte array back to a UTF-8 string."
  [^bytes data]
  (with-open [in  (BrotliInputStream. (ByteArrayInputStream. data))
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 4096)]
      (loop [n (.read in buf)]
        (when (pos? n)
          (.write out buf 0 n)
          (recur (.read in buf)))))
    (.toString out "UTF-8")))

(defn decompress-stream
  "Decompress brotli data that may be an incomplete stream (e.g. flushed
   streaming chunks). Uses eager output and catches IOException at EOF
   so partial data is still returned."
  [^bytes data]
  (with-open [in  (BrotliInputStream. (ByteArrayInputStream. data))
              out (ByteArrayOutputStream.)]
    (.enableEagerOutput in)
    (try
      (let [buf (byte-array 4096)]
        (loop [n (.read in buf)]
          (when (pos? n)
            (.write out buf 0 n)
            (recur (.read in buf)))))
      (catch IOException _))
    (.toString out "UTF-8")))

(defn close-stream
  "Close the brotli output stream. Safe to call on nil."
  [^BrotliOutputStream br]
  (when br
    (try (.close br) (catch Exception _))))

;; ---------------------------------------------------------------------------
;; Ring middleware
;; ---------------------------------------------------------------------------

(defn- accepts-br?
  "Check if the request's Accept-Encoding header includes brotli."
  [req]
  (when-let [accept (get-in req [:headers "accept-encoding"])]
    (some? (re-find #"\bbr\b" accept))))

(defn wrap-brotli
  "Ring middleware that compresses response bodies with brotli.
   Only compresses when:
   - Client sends Accept-Encoding: br
   - Response body is a string (typical for HTML/JSON)
   - Response is not already encoded

   Uses quality 11 (max) for one-shot responses since they're not
   latency-sensitive like streaming SSE."
  [handler]
  (fn [req]
    (let [response (handler req)]
      (if (and response
               (accepts-br? req)
               (string? (:body response))
               (not (get-in response [:headers "Content-Encoding"])))
        (-> response
            (update :body #(compress % :quality 11))
            (assoc-in [:headers "Content-Encoding"] "br"))
        response))))
