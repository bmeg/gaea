(ns gaia.swift
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [gaia.store :as store])
  (:import
   [org.javaswift.joss.client.factory AccountFactory]))

(defn get-container
  [account container]
  (.getContainer account container))

(defn container-exists?
  [container]
  (.exists container))

(defn swift-connect
  [{:keys [username password url method tenant-id tenant-name region container root]}]
  (let [factory (AccountFactory.)]
    (.setUsername factory username)
    (.setPassword factory password)
    (.setAuthUrl factory url)
    (if region (.setPreferredRegion factory region))
    (if tenant-id (.setTenantId factory tenant-id))
    (if tenant-name (.setTenantName factory tenant-name))
    (if method (.setAuthenticationMethod factory method))
    (let [account (.createAccount factory)
          contain (get-container account container)]
      {:account account
       :container contain
       :container-name container
       :root root})))

(defn get-object
  [container key]
  (.getObject container (name key)))

(defn key-exists?
  [{:keys [container]} key]
  (let [object (get-object container key)]
    (.exists object)))

(def encoded-slash #"%2F")
(def encoded-colon #"%3A")

(defn get-path
  [prefix object]
  (-> (.getPath object)
      (string/replace encoded-slash "/")
      (string/replace encoded-colon ":")
      (string/replace prefix "")))

(defn all-keys
  [{:keys [container container-name] :as swift}]
  (loop [dirs (.listDirectory container)
         all []]
    (if (empty? dirs)
      all
      (let [head (first dirs)
            remaining (rest dirs)
            base (re-pattern (str "/" container-name "/"))]
        (if (.isDirectory head)
          (recur
           (concat remaining (.listDirectory container head))
           all)
          (recur
           remaining
           (conj all (get-path base head))))))))

(defn create-container
  [account path]
  (let [container (.getContainer account path)]
    (.create container)
    (.makePublic container)
    container))

(defn list-containers
  [account]
  (let [containers (.list account)]
    (map #(.getName %) containers)))

(defn put-key
  [{:keys [container]} key path]
  (let [object (get-object container key)
        file (io/file path)]
    (.uploadObject object file)))

(defn get-key
  [{:keys [container]} key path]
  (let [object (get-object container key)
        file (io/file path)]
    (.downloadObject object file)))

(defn delete-key
  [{:keys [container]} key]
  (let [object (get-object container key)]
    (.delete object)))

(deftype SwiftStore [swift]
  store/Store
  (present?
    [store key]
    (key-exists? swift key))
  (computing? [store key] false)
  (protocol [store] (str "swift://" (:container-name swift)))
  (url-root [store] (:root swift))
  (existing-keys
    [store]
    (all-keys swift)))

(defn load-swift-store
  [config]
  (let [swift (swift-connect config)]
    (SwiftStore. swift)))


