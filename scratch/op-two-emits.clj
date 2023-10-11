(use 'com.rpl.rama)

(deframaop foo [*a]
  (:> (inc *a))
  (:> (dec *a)))

(?<-
 (foo 5 :> *v)
 (println *v))
