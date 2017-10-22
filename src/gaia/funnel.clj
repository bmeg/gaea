(ns gaia.funnel
  (:require
   [cheshire.core :as json]
   [taoensso.timbre :as log]
   [clj-http.client :as http]
   [protograph.kafka :as kafka]))

(defn parse-body
  [response]
  (let [body (:body response)
        parsed (json/parse-string body true)]
    (if (:error parsed)
      (log/info "error getting json for" parsed)
      parsed)))

(defn get-json
  [url]
  (let [response (http/get url {:throw-exceptions false})]
    (parse-body response)))

(defn post-json
  [url task]
  (log/info "post:" task)
  (let [body (json/generate-string task)
        _ (log/info "body" body)
        response
        (http/post
         url
         {:body body
          :content-type :json})]
    response))

(defn snip
  [s prefix]
  (if (.startsWith s prefix)
    (.substring s (inc (.length prefix)))
    s))

(defn render-output
  [path task-id {:keys [url path sizeBytes]}]
  (let [key (snip path path)]
    [key
     {:url url
      :size sizeBytes
      :state :complete
      :source task-id}]))

(defn render-outputs
  [path task-id outputs]
  (into
   {}
   (map
    (partial render-output path task-id)
    outputs)))

(defn apply-outputs!
  [path status task-id outputs]
  (let [rendered (render-outputs path task-id outputs)]
    (swap!
     status
     (fn [status]
       (merge status rendered)))
    rendered))

(defn declare-event!
  [producer message]
  (kafka/send-message
   producer
   "gaia-events"
   (json/generate-string message)))

(defn funnel-events-listener
  ([variables path status] (funnel-events-listener variables path status {}))
  ([variables path status kafka]
   (let [consumer (kafka/consumer (merge (:base kafka) (:consumer kafka)))
         producer (kafka/producer (merge (:base kafka) (:producer kafka)))

         listen
         (fn [funnel-event]
           (if-let [event (.value funnel-event)]
             (let [message (json/parse-string event true)]
               (log/info "funnel event" message)
               (if (= (:type message) "TASK_OUTPUTS")
                 (let [outputs (get-in message [:outputs :value])
                       applied (apply-outputs! path status (:id message) outputs)]
                   (doseq [[key output] applied]
                     (declare-event!
                      producer
                      {:key key
                       :output output
                       :variable (get variables key)})))))))]
     (kafka/subscribe consumer ["funnel-events"])
     {:funnel-events (future (kafka/consume consumer listen))
      :consumer consumer})))

(defn funnel-connect
  [{:keys [host path kafka] :as config}
   {:keys [commands variables] :as context}]
  (log/info "funnel connect" config)
  (let [tasks-url (str host "/v1/tasks")
        status (atom {})]
    {:funnel config
     :commands commands

     :status status
     :listener (funnel-events-listener variables path status kafka)

     :create-task
     (comp parse-body (partial post-json tasks-url))

     :list-tasks
     (fn []
       (get-json tasks-url))

     :get-task
     (fn [id]
       (get-json (str tasks-url "/" id)))

     :cancel-task
     (fn [id]
       (http/post
        (str tasks-url "/" id ":cancel")))}))

(defn funnel-path
  [funnel path]
  (let [prefix (get-in funnel [:funnel :prefix] "file://")
        base (get-in funnel [:funnel :path] "tmp")]
    (str prefix base "/" path)))

(defn funnel-io
  [funnel [key source]]
  (let [base {:name key
              ;; :description (str key source)
              :type "FILE"
              :path (name key)}]
    (cond
      (string? source) (assoc base :url (funnel-path funnel source))
      (:contents source) (merge base source)
      (:type source) (merge base source)
      :else source)))

(defn funnel-task
  [{:keys [commands] :as funnel}
   {:keys [key inputs outputs command]}]
  (if-let [execute (get commands command)]
    {:name key
     ;; :description (str key inputs outputs command)
     :inputs (map (partial funnel-io funnel) inputs)
     :outputs (map (partial funnel-io funnel) outputs)
     :executors [execute]}
    (log/error "no command named" command)))

(defn submit-task
  [funnel process]
  (let [task (funnel-task funnel process)
        task-id (:id ((:create-task funnel) task))
        computing (into
                   {}
                   (map
                    (fn [k]
                      [k {:source task-id :state :computing}])
                    (vals (:outputs process))))]
    (swap!
     (:status funnel)
     (fn [status]
       (merge computing status)))
    (log/info "funnel task" task-id task)))

(defn funnel-path
  [funnel key]
  (get-in @(:status funnel) [key :url]))

(defn pull-data
  [funnel inputs]
  (into
   {}
   (map
    (fn [[arg key]]
      [arg (funnel-path funnel key)])
    inputs)))

(defn stuff-data
  [data outputs]
  (into
   {}
   (map
    (fn [[out key]]
      [key (funnel-path data out)])
    outputs)))


























;; FUNNEL STORE ???????? simplicity may be better

;; (deftype FunnelStore [state]
;;   store/Store
;;   (absent? [store key]
;;     (empty? (get state key)))
;;   (computing? [store key]
;;     (= (get state key) :computing))
;;   (present? [store key]
;;     (= (get state key) :present)))

;; (deftype FunnelStore [state]
;;   store/Store
;;   (absent? [store key]
;;     (empty? (get state key)))
;;   (computing? [store key]
;;     (if-let [source (get state key)]
;;       (let [status ((:get-task funnel) source)]
;;         (not= (:state status) "COMPLETE"))))
;;   (present? [store key]
;;     (if-let [source (get state key)]
;;       (let [status ((:get-task funnel) source)]
;;         (= (:state status) "COMPLETE")))))













;; EXAMPLE FUNNEL DOCUMENT
;; -----------------------
;; 
;; {
;;   "name": "Input file contents and output file",
;;   "description": "Demonstrates using the 'contents' field for inputs to create a file on the host system",
;;   "inputs": [
;;     {
;;       "name": "cat input",
;;       "description": "Input to md5sum. /tmp/in will be created on the host system.",
;;       "type": "FILE",
;;       "path": "/tmp/in",
;;       "contents": "Hello World\n"
;;     }
;;   ],
;;   "outputs": [
;;     {
;;       "name": "cat stdout",
;;       "description": "Stdout of cat is captures to /tmp/test_out on the host system.",
;;       "url": "file:///tmp/cat_output",
;;       "type": "FILE",
;;       "path": "/tmp/out"
;;     }
;;   ],
;;   "executors": [
;;     {
;;       "image_name": "alpine",
;;       "cmd": ["cat", "/tmp/in"],
;;       "stdout": "/tmp/out"
;;     }
;;   ]
;; }
