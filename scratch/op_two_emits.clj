(ns op-two-emits
  (:require [com.rpl.rama :as r]
            [hyperfiddle.rcf :as rcf]))

(rcf/enable!)

(r/deframaop foo [*a]
  (:> (inc *a))
  (:> (dec *a)))

(rcf/tests
 
 (r/?<-
  (foo 5 :> *v)
  (rcf/tap *v))

 rcf/% := 6
 rcf/% := 4)
