(ns jepsen.etcdemo.register
  (:require [clojure.tools.logging :refer :all]
            [jepsen [checker :as checker]]
            [knossos.model :as model])
  (:import (knossos.model Model)))

(defrecord CASRegister [value]
  Model
  (step [r op]
    (condp = (:f op)
      :write (CASRegister. (:value op))
      :cas   (let [[cur new] (:value op)]
               (if (= cur value)
                 (CASRegister. new)
                 (model/inconsistent (str "can't CAS " value " from " cur
                                    " to " new))))
      :read  (if (or (nil? (:value op))
                     (= value (:value op)))
               r
               (model/inconsistent (str "can't read " (:value op)
                                  " from register " value))))))