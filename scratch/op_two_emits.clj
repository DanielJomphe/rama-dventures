(ns op-two-emits
  (:require [com.rpl.rama :as r]
            [hyperfiddle.rcf :as rcf :refer [tap %]]))

(r/deframaop foo [*a]
  (:> (inc *a))
  (:> (dec *a)))

(rcf/tests
 
 (r/?<-
  (foo 5 :> *v)
  (tap *v))

 % := 6
 % := 4)
