(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [jepsen [cli :as cli]
             [tests :as tests]
             [generator :as gen]
             [independent :as independent]
             [nemesis :as nemesis]
             [checker :as checker]]
            [jepsen.os.debian :as debian]
            [jepsen.checker.timeline :as timeline]
            [jepsen.etcdemo
             [db :as db]
             [client :as client]]
            [knossos.model :as model])
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
          :nemesis         (nemesis/partition-random-halves)
          :checker   (checker/compose
                       {:perf  (checker/perf)
                        :indep (independent/checker
                                 (checker/compose
                                   {:linear   (checker/linearizable {:model (model/cas-register)
                                                                     :algorithm :linear})
                                    :timeline (timeline/html)}))})
          :generator  (->> (independent/concurrent-generator
                             10
                             (range)
                             (fn [k]
                               (->> (gen/mix [client/r client/w client/cas])
                                    (gen/stagger 1/50)
                                    (gen/limit 100))))
                           (gen/nemesis
                             (cycle [(gen/sleep 5)
                                     {:type :info, :f :start}
                                     (gen/sleep 5)
                                     {:type :info, :f :stop}]))
                           (gen/time-limit (:time-limit opts)))}))

(def cli-opts
  "Additional command line options."
  [["-q" "--quorum" "Use quorum reads, instead of reading from any primary."]])


(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  etcd-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args)
  )