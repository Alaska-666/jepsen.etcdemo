(ns jepsen.etcdemo.client
  (:require [clojure.tools.logging :refer :all]
            [verschlimmbesserung.core :as v]
            [jepsen [client :as client]]
            [slingshot.slingshot :refer [try+]])
  (:import (java.net SocketTimeoutException)))


(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong s)))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (v/connect (client-url node)
                                 {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op]
    (let [[k v] (:value op)]
      (try+
        (case (:f op)
          :read (let [value (-> conn
                                (v/get k {:quorum? true})
                                parse-long)]
                  (assoc op :type :ok, :value (independent/tuple k value)))

          :write (do (v/reset! conn k v)
                     (assoc op :type :ok))

          :cas (let [[old new] v]
                 (assoc op :type (if (v/cas! conn k old new)
                                   :ok
                                   :fail))))

        (catch SocketTimeoutException e
          (assoc op
            :type  (if (= :read (:f op)) :fail :info)
            :error :timeout))

        (catch [:errorCode 100] e
          (assoc op :type :fail, :error :not-found)))))

  (teardown! [this test])

  (close! [_ test]
    ; If our connection were stateful, we'd close it here. Verschlimmmbesserung
    ; doesn't actually hold connections, so there's nothing to close.
    ))