(ns hello-world
  "See https://blog.redplanetlabs.com/2023/10/11/introducing-ramas-clojure-api/"
  (:require [com.rpl.rama :as r :refer [?<-]]))

(?<-
 (println "Hello, world!"))
