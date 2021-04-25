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


(defn register-workload
  "Tests linearizable reads, writes, and compare-and-set operations on
  independent keys."
  [opts]
  {:client    (Client. nil)
   :checker   (independent/checker
                (checker/compose
                  {:linear   (checker/linearizable {:model     (model/cas-register)
                                                    :algorithm :linear})
                   :timeline (timeline/html)}))
   :generator (independent/concurrent-generator
                10
                (range)
                (fn [k]
                  (->> (gen/mix [client/r client/w client/cas])
                       (gen/limit (:ops-per-key opts)))))})


(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {"set"      set/workload
   "register" register-workload})


(def cli-opts
  "Additional command line options."
  [["-w" "--workload NAME" "What workload should we run?"
    :missing  (str "--workload " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]
  ["-q" "--quorum" "Use quorum reads, instead of reading from any primary."]
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
      :ops-per-key  Maximum number of operations allowed on any given key
      :workload     Type of workload."
  [opts]
  (let [quorum    (boolean (:quorum opts))
        workload  ((get workloads (:workload opts)) opts)]
    (merge tests/noop-test
           opts
           {:pure-generators true
            :name       (str "etcd q=" quorum " "
                             (name (:workload opts)))
            :quorum     quorum
            :os         debian/os
            :db         (db/db "v3.1.5")
            :nemesis    (nemesis/partition-random-halves)
            :client     (:client workload)
            :checker    (checker/compose
                          {:perf     (checker/perf)
                           :workload (:checker workload)})})
    )
  )


(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  etcd-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args)
  )