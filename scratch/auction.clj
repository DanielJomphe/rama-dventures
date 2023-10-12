(ns auction
  "See https://blog.redplanetlabs.com/2023/10/11/introducing-ramas-clojure-api/"
  (:import (com.rpl.rama.helpers
            ModuleUniqueIdPState
            TopologyScheduler))
  (:require [com.rpl.rama.ops :as ops]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [hyperfiddle.rcf :as rcf :refer [tap %]])
  (:use com.rpl.rama
        com.rpl.rama.path))

(defn owner-notification [listing-id winner-id amount]
  (if winner-id
    (str "Auction for listing " listing-id
         " finished with winner " winner-id
         " for the amount " amount)
    (str "Auction for listing " listing-id " finished with no winner")))

(defn winner-notification [user-id listing-id amount]
  (str "You won the auction for listing " user-id "/" listing-id " for the amount " amount))

(defn loser-notification [user-id listing-id]
  (str "You lost the auction for listing " user-id "/" listing-id))

(defn sorted-set-last [^java.util.SortedSet set]
  (.last set))

(defrecord Listing [user-id post expiration-time-millis])
(defrecord Bid [bidder-id user-id listing-id amount])
(defrecord ListingPointer [user-id listing-id])
(defrecord ListingWithId [id listing])

(defmodule AuctionModule [setup topologies]
  (declare-depot setup *listing-depot (hash-by :user-id))
  (declare-depot setup *bid-depot (hash-by :user-id))
  (declare-depot setup *listing-with-id-depot :disallow)

  (let [s (stream-topology topologies "auction")
        idgen (ModuleUniqueIdPState. "$$id")]
    (declare-pstate s $$user-listings {Long ; user ID
                                       (map-schema Long ; listing ID
                                                   String ; post
                                                   {:subindex? true})})
    (declare-pstate s $$listing-bidders {Long ; listing ID
                                         (set-schema Long ; user ID
                                                     {:subindex? true})})
    (declare-pstate s $$listing-top-bid {Long ; listing ID
                                         (fixed-keys-schema {:user-id Long
                                                             :amount Long})})
    (declare-pstate s $$user-bids {Long (map-schema ListingPointer
                                                    Long ; amount
                                                    {:subindex? true})})
    (.declarePState idgen s)

    (<<sources s
               (source> *listing-depot :> {:keys [*user-id *post] :as *listing})
               (java-macro! (.genId idgen "*listing-id"))
               (local-transform> [(keypath *user-id *listing-id) (termval *post)]
                                 $$user-listings)
               (depot-partition-append! *listing-with-id-depot
                                        (->ListingWithId *listing-id *listing)
                                        :append-ack)

               (source> *bid-depot :> {:keys [*bidder-id *user-id *listing-id *amount]})
               (local-select> (keypath *listing-id) $$finished-listings :> *finished?)
               (filter> (not *finished?))
               (local-transform> [(keypath *listing-id) NONE-ELEM (termval *bidder-id)]
                                 $$listing-bidders)
               (local-transform> [(keypath *listing-id)
                                  (selected? :amount (nil->val 0) (pred< *amount))
                                  (termval {:user-id *bidder-id :amount *amount})]
                                 $$listing-top-bid)
               (|hash *bidder-id)
               (->ListingPointer *user-id *listing-id :> *pointer)
               (local-transform> [(keypath *bidder-id *pointer) (termval *amount)] $$user-bids)))
  (let [mb (microbatch-topology topologies "expirations")
        scheduler (TopologyScheduler. "$$scheduler")]
    (declare-pstate mb $$finished-listings {Long Boolean})
    (declare-pstate mb $$notifications {Long (vector-schema String {:subindex? true})})
    (.declarePStates scheduler mb)

    (<<sources mb
               (source> *listing-with-id-depot :> %microbatch)
               (anchor> <root>)
               (%microbatch :> {*listing-id :id
                                {:keys [*user-id *expiration-time-millis]} :listing})
               (vector *user-id *listing-id :> *tuple)
               (java-macro! (.scheduleItem scheduler "*expiration-time-millis" "*tuple"))

               (hook> <root>)
               (java-macro!
                (.handleExpirations
                 scheduler
                 "*tuple"
                 "*current-time-millis"
                 (java-block<-
                  (identity *tuple :> [*user-id *listing-id])
                  (local-transform> [(keypath *listing-id) (termval true)]
                                    $$finished-listings)
                  (local-select> (keypath *listing-id)
                                 $$listing-top-bid
                                 :> {*winner-id :user-id *amount :amount})
                  (local-transform> [(keypath *user-id)
                                     AFTER-ELEM
                                     (termval (owner-notification *listing-id *winner-id *amount))]
                                    $$notifications)
                  (loop<- [*next-id -1 :> *bidder-id]
                          (yield-if-overtime)
                          (local-select> [(keypath *listing-id)
                                          (sorted-set-range-from *next-id {:max-amt 1000 :inclusive? false})]
                                         $$listing-bidders
                                         :> *users)
                          (<<atomic
                           (:> (ops/explode *users)))
                          (<<if (= (count *users) 1000)
                                (continue> (sorted-set-last *users))))
                  (|hash *bidder-id)
                  (<<if (= *bidder-id *winner-id)
                        (winner-notification *user-id *listing-id *amount :> *text)
                        (else>)
                        (loser-notification *user-id *listing-id :> *text))
                  (local-transform> [(keypath *bidder-id)
                                     AFTER-ELEM
                                     (termval *text)]
                                    $$notifications)))))))


(defn expiration [seconds]
  (+ (System/currentTimeMillis) (* seconds 1000)))

(rcf/set-timeout! 15000)
(rcf/tests
 ; better w/ with-open but for now let's keep this REPL-friendly
 (def ipc (rtest/create-ipc))

 (rtest/launch-module! ipc AuctionModule {:tasks 4 :threads 2})

 (def module-name (get-module-name AuctionModule))

 (def listing-depot   (foreign-depot  ipc module-name "*listing-depot"))
 (def bid-depot       (foreign-depot  ipc module-name "*bid-depot"))
 (def user-bids       (foreign-pstate ipc module-name "$$user-bids"))
 (def user-listings   (foreign-pstate ipc module-name "$$user-listings"))
 (def listing-bidders (foreign-pstate ipc module-name "$$listing-bidders"))
 (def listing-top-bid (foreign-pstate ipc module-name "$$listing-top-bid"))
 (def notifications   (foreign-pstate ipc module-name "$$notifications"))

 (def larry-id 0)
 (def hank-id 1)
 (def artie-id 2)
 (def beverly-id 3)

 (foreign-append! listing-depot (->Listing larry-id "Listing 1" (expiration 5)))

 (def larry1 (foreign-select-one [(keypath larry-id) LAST FIRST] user-listings))

 (foreign-append! bid-depot (->Bid hank-id    larry-id larry1 45))
 (foreign-append! bid-depot (->Bid artie-id   larry-id larry1 50))
 (foreign-append! bid-depot (->Bid beverly-id larry-id larry1 48))

 ;; wait slightly more than the expiration time for the listing to allow notifications
 ;; to be delivered
 (Thread/sleep 6000)
 (tap (str "Larry: "   (foreign-select [(keypath larry-id)   ALL] notifications)))
 (tap (str "Hank: "    (foreign-select [(keypath hank-id)    ALL] notifications)))
 (tap (str "Artie: "   (foreign-select [(keypath artie-id)   ALL] notifications)))
 (tap (str "Beverly: " (foreign-select [(keypath beverly-id) ALL] notifications)))

 (close! ipc)

 % := "Larry: [\"Auction for listing 0 finished with winner 2 for the amount 50\"]"
 % := "Hank: [\"You lost the auction for listing 0/0\"]"
 % := "Artie: [\"You won the auction for listing 0/0 for the amount 50\"]"
 % := "Beverly: [\"You lost the auction for listing 0/0\"]"
 )
