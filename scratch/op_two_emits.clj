(ns op-two-emits
  "See https://blog.redplanetlabs.com/2023/10/11/introducing-ramas-clojure-api/"
  (:require [hyperfiddle.rcf :as rcf :refer [tap %]])
  (:use com.rpl.rama))

(deframaop foo [*a]
  (:> (inc *a))
  (:> (dec *a)))

(rcf/tests

 (?<-
  (foo 5 :> *v)
  (tap *v))

 % := 6
 % := 4)
