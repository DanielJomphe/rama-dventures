(ns op-two-emits
  "See https://blog.redplanetlabs.com/2023/10/11/introducing-ramas-clojure-api/"
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
