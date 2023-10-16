(ns rpl-intro-article.word-count
  "Copyright Red Planet Labs, 2023.
   RPL usually releases demos under the Apache License 2.0.
   See https://github.com/redplanetlabs/rama-demo-gallery/tree/master
   See https://blog.redplanetlabs.com/2023/10/11/introducing-ramas-clojure-api/"
  (:require [clojure.string :as str]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [hyperfiddle.rcf :as rcf :refer [tap %]])
  (:use com.rpl.rama
        com.rpl.rama.path))

(defmodule WordCountModule [setup topologies]
  (declare-depot setup *sentences-depot :random)
  (let [s (stream-topology topologies "word-count")]
    (declare-pstate s $$word-counts {String Long})
    (<<sources s
               (source> *sentences-depot :> *sentence)
               (str/split (str/lower-case *sentence) #" " :> *words)
               (ops/explode *words :> *word)
               (|hash *word)
               (+compound $$word-counts {*word (aggs/+count)}))))

(rcf/set-timeout! 8000)
(rcf/tests
 ; better w/ with-open but for now let's keep this REPL-friendly
 (def ipc (rtest/create-ipc))

 (rtest/launch-module! ipc WordCountModule {:tasks 4 :threads 2})

 (def word-counts    (foreign-pstate ipc (get-module-name WordCountModule) "$$word-counts"))
 (def sentences-depot (foreign-depot ipc (get-module-name WordCountModule) "*sentences-depot"))

 (foreign-append! sentences-depot "Hello world")
 (foreign-append! sentences-depot "hello hello goodbye")
 (foreign-append! sentences-depot "Alice says hello")

 (tap (foreign-select-one (keypath "hello")   word-counts)) ; => 4
 (tap (foreign-select-one (keypath "goodbye") word-counts)) ; => 1

 (close! ipc)

 % := 4
 % := 1)
