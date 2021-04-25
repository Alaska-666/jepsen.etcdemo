(ns jepsen.etcdemo.client
  (:require [clojure.tools.logging :refer :all]
            [verschlimmbesserung.core :as v]
            [jepsen [client :as client]]
            [slingshot.slingshot :refer [try+]]))


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

  (invoke! [this test op]
    (case (:f op)
      :read (let [value (-> conn
                            (v/get "foo" {:quorum? true})
                            parse-long)]
              (assoc op :type :ok, :value value))
      :write (do (v/reset! conn "foo" (:value op))
                 (assoc op :type :ok))
      :cas (try+
             (let [[old new] (:value op)]
               (assoc op :type (if (v/cas! conn "foo" old new)
                                 :ok
                                 :fail)))
             (catch [:errorCode 100] ex
               (assoc op :type :fail, :error :not-found)))))

  (teardown! [this test])

  (close! [_ test]
    ; If our connection were stateful, we'd close it here. Verschlimmmbesserung
    ; doesn't actually hold connections, so there's nothing to close.
    ))