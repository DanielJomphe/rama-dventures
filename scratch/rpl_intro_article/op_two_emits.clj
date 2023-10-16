(ns rpl-intro-article.op-two-emits
  "Copyright Red Planet Labs, 2023.
   RPL usually releases demos under the Apache License 2.0.
   See https://github.com/redplanetlabs/rama-demo-gallery/tree/master
   See https://blog.redplanetlabs.com/2023/10/11/introducing-ramas-clojure-api/"
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
