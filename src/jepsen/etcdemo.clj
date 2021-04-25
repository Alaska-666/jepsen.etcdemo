(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [jepsen [cli :as cli]
             [tests :as tests]
             [generator :as gen]]
            [jepsen.os.debian :as debian]
            [jepsen.etcdemo
             [db :as db]
             [client :as client]])
  (:import (jepsen.etcdemo.client Client)))

(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:pure-generators true
          :name            "etcd"
          :os              debian/os
          :db              (db/db "v3.1.5")
          :client          (Client. nil)
          :generator       (->> (gen/mix [client/r client/w client/cas])
                                (gen/stagger 1)
                                (gen/nemesis nil)
                                (gen/time-limit 15))}))


(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn etcd-test})
            args))