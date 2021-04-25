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
             [client :as client]
             [set :as set]
             [support :as s]]
            [knossos.model :as model])
  (:import (jepsen.etcdemo.client Client)))


(def cli-opts
  "Additional command line options."
  [["-q" "--quorum" "Use quorum reads, instead of reading from any primary."]
   ["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   [nil "--ops-per-key NUM" "Maximum number of operations on any given key."
    :default  100
    :parse-fn client/parse-long
    :validate [pos? "Must be a positive integer."]]])


(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency ...), constructs a test map. Special options:

      :quorum       Whether to use quorum reads
      :rate         Approximate number of requests per second, per thread
      :ops-per-key  Maximum number of operations allowed on any given key."
  [opts]
  (let [quorum    (boolean (:quorum opts))
        workload  (set/workload opts)]
    (merge tests/noop-test
           opts
           {:pure-generators true
            :name            (str "etcd q=" quorum)
            :quorum          quorum
            :os              debian/os
            :db              (db/db "v3.1.5")
            :client          (Client. nil)
            :nemesis         (nemesis/partition-random-halves)
            :checker         (checker/compose
                               {:perf   (checker/perf)
                                :indep (independent/checker
                                         (checker/compose
                                           {:linear   (checker/linearizable
                                                        {:model (model/cas-register)
                                                         :algorithm :linear})
                                            :timeline (timeline/html)}))})
            :generator       (->> (independent/concurrent-generator
                                    10
                                    (range)
                                    (fn [k]
                                      (->> (gen/mix [client/r client/w client/cas])
                                           (gen/stagger (/ (:rate opts)))
                                           (gen/limit (:ops-per-key opts)))))
                                  (gen/nemesis
                                    (->> [(gen/sleep 5)
                                          {:type :info, :f :start}
                                          (gen/sleep 5)
                                          {:type :info, :f :stop}]
                                         cycle))
                                  (gen/time-limit (:time-limit opts)))}
           {:client    (:client workload)
            :checker   (:checker workload)
            :generator (gen/phases
                         (->> (:generator workload)
                              (gen/stagger (/ (:rate opts)))
                              (gen/nemesis
                                (cycle [(gen/sleep 5)
                                        {:type :info, :f :start}
                                        (gen/sleep 5)
                                        {:type :info, :f :stop}]))
                              (gen/time-limit (:time-limit opts)))
                         (gen/log "Healing cluster")
                         (gen/nemesis (gen/once {:type :info, :f :stop}))
                         (gen/log "Waiting for recovery")
                         (gen/sleep 10)
                         (gen/clients (:final-generator workload)))})))


(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  etcd-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args)
  )