(ns rpl-demo-gallery.profile-module
  "Copyright Red Planet Labs, 2023.
   RPL usually releases demos under the Apache License 2.0.
   See https://github.com/redplanetlabs/rama-demo-gallery/tree/master/src/main/clj/rama/gallery
   
   I did not preserve original demo comments (very useful when learning).
   And rewrote tests in same file using Hyperfiddle RCF, using dumber but more REPL-friendly `def`s instead of `with` and `let`s.
   See https://github.com/redplanetlabs/rama-demo-gallery/blob/master/src/main/clj/rama/gallery/profile_module.clj
   See https://github.com/redplanetlabs/rama-demo-gallery/blob/master/src/test/clj/rama/gallery/profile_module_test.clj"
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest]
            [hyperfiddle.rcf :as rcf :refer [tap %]])
  (:import [com.rpl.rama.helpers ModuleUniqueIdPState]))

(defrecord Registration [uuid username pwd-hash])
(defrecord ProfileEdit  [field value])
(defrecord ProfileEdits [user-id edits])

(defn display-name-edit  [value] (->ProfileEdit :display-name  value))
(defn pwd-hash-edit      [value] (->ProfileEdit :pwd-hash      value))
(defn height-inches-edit [value] (->ProfileEdit :height-inches value))

(defmodule ProfileModule
  [setup topologies]
  (declare-depot setup  *registration-depot (hash-by :username))
  (declare-depot setup *profile-edits-depot (hash-by :user-id))

  (let [s (stream-topology topologies "profiles")
        id-gen (ModuleUniqueIdPState. "$$id")]
    (declare-pstate s $$username->registration {String ; username
                                                (fixed-keys-schema {:user-id Long
                                                                    :uuid String})})
    (declare-pstate s $$profiles {Long ; user ID
                                  (fixed-keys-schema {:username String
                                                      :pwd-hash String
                                                      :display-name String
                                                      :height-inches Long})})
    (.declarePState id-gen s)

    (<<sources s
               (source> *registration-depot :> {:keys [*uuid *username *pwd-hash]})
               (local-select> :username $$username->registration :> {*curr-uuid :uuid :as *curr-info})
               (<<if (or> (nil? *curr-info)
                          (= *curr-uuid *uuid))
                     (java-macro! (.genId id-gen "*user-id"))
                     (local-transform> [(keypath *username)
                                        (multi-path [:user-id  (termval *user-id)]
                                                    [:uuid     (termval *uuid)])]
                                       $$username->registration)
                     (|hash *user-id)
                     (local-transform> [(keypath *user-id)
                                        (multi-path [:username (termval *username)]
                                                    [:pwd-hash (termval *pwd-hash)])]
                                       $$profiles))

               (source> *profile-edits-depot :> {:keys [*user-id *edits]})
               (ops/explode *edits :> {:keys [*field *value]})
               (local-transform> [(keypath *user-id *field) (termval *value)] $$profiles))))


(defn register! [registration-depot username->registration username pwd-hash]
  (let [reg-uuid (str (java.util.UUID/randomUUID))
        _ (foreign-append! registration-depot (->Registration reg-uuid username pwd-hash))
        {:keys [uuid user-id]} (foreign-select-one (keypath username) username->registration)]
    (if (= reg-uuid uuid)
      user-id
      (throw (ex-info "Username already registered" {})))))

(rcf/tests

 ; better w/ with-open but for now let's keep this REPL-friendly
 (def ipc (rtest/create-ipc))

 (rtest/launch-module! ipc ProfileModule {:tasks 4 :threads 2})
 (def module-name (get-module-name ProfileModule))
 (def registration-depot     (foreign-depot  ipc module-name "*registration-depot"))
 (def profile-edits-depot    (foreign-depot  ipc module-name "*profile-edits-depot"))
 (def username->registration (foreign-pstate ipc module-name "$$username->registration"))
 (def profiles               (foreign-pstate ipc module-name "$$profiles"))
 (def alice-id               (register! registration-depot username->registration "alice" "hash1"))
 (def bob-id                 (register! registration-depot username->registration "bob"   "hash2"))
 (try
   (register! registration-depot username->registration "alice", "hash3")
   (catch Exception e))

 (foreign-select-one (keypath alice-id :username) profiles) := "alice"
 (foreign-select-one (keypath bob-id :username) profiles)   := "bob"

 (foreign-append! profile-edits-depot
                  (->ProfileEdits alice-id
                                  [(display-name-edit "Alice Smith")
                                   (height-inches-edit 65)
                                   (pwd-hash-edit "hash4")]))
 (foreign-select-one (keypath alice-id) profiles)
 := {:username "alice"
     :display-name "Alice Smith"
     :height-inches 65
     :pwd-hash "hash4"}

 (foreign-append! profile-edits-depot
                  (->ProfileEdits alice-id
                                  [(display-name-edit "Alicia Smith")]))
 (foreign-select-one (keypath alice-id) profiles)
 :=
 {:username "alice"
  :display-name "Alicia Smith"
  :height-inches 65
  :pwd-hash "hash4"}

 (foreign-append! profile-edits-depot
                  (->ProfileEdits bob-id
                                  [(display-name-edit "Bobby")]))
 (foreign-select-one (keypath bob-id) profiles)
 :=
 {:username "bob"
  :display-name "Bobby"
  :pwd-hash "hash2"}

 (close! ipc))
