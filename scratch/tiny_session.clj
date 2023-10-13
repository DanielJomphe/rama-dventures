(ns tiny-session
  "From Jake Carson Zerrer on 2023-10-12 (1 day after RPL released the Clojure API)
   With permission:

   'Made a tiny rama session management module (that basically just rips off /
    simplifies the gallery profile_module) just to see what it feels like.'
   
   https://clojurians.slack.com/archives/C7Q9GSHFV/p1697136881084169"
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama :as r]
            [com.rpl.rama.test :as rtest]
            [hyperfiddle.rcf :as rcf]))

(defrecord CreateSession [uuid refresh-token expires-at])

(defn new-session [uuid refresh-token expires-at]
  (->CreateSession uuid refresh-token expires-at))

(defmodule SessionModule
  [setup topologies]
  (declare-depot setup *create-session-depot (r/hash-by :uuid))

  (let [s (r/stream-topology topologies "sessions")]
    (declare-pstate s $$sessions
                    {String ;; uuid
                     (fixed-keys-schema
                      {:refresh-token String
                       :expires-at java.time.Instant})})

    (<<sources
     s
     (source> *create-session-depot :>
              {:keys [*uuid *refresh-token *expires-at]})

     (local-select> *uuid $$sessions :> *curr-session)

     ;; Session creation should be idempotent
     (<<if (nil? *curr-session)
           (local-transform> [(keypath *uuid)
                              (multi-path [:refresh-token (termval *refresh-token)]
                                          [:expires-at    (termval *expires-at)])]
                             $$sessions)))))

(rcf/tests
 (def ipc (rtest/create-ipc))
 (rtest/launch-module! ipc SessionModule {:tasks 4 :threads 2})

 (def module-name (get-module-name SessionModule))
 (def create-session-depot (foreign-depot  ipc module-name "*create-session-depot"))
 (def sessions-pstate      (foreign-pstate ipc module-name "$$sessions"))

 (foreign-select-one (keypath "test-uuid") sessions-pstate)
 := nil

 (foreign-append! create-session-depot (new-session "test-uuid" "abc" (java.time.Instant/now)))

 (foreign-select-one (keypath "test-uuid") sessions-pstate)
 :=
 {:refresh-token "abc"
  :expires-at _}

 (foreign-append! create-session-depot (new-session "test-uuid" "def" (java.time.Instant/now)))

 (foreign-select-one (keypath "test-uuid") sessions-pstate)
 :=
 {:refresh-token "abc"
  :expires-at _}

 (foreign-append! create-session-depot (new-session "other" "ghi" (java.time.Instant/now)))
 (foreign-select-one (keypath "test-uuid") sessions-pstate)
 :=
 {:refresh-token "abc"
  :expires-at _}

 (foreign-select-one (keypath "unknown-session") sessions-pstate)
 := nil
 (.close ipc))
