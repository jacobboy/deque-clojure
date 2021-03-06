;;;; Copyright © 2013 Paul Stadig. All rights reserved.
;;;;
;;;; This Source Code Form is subject to the terms of the Mozilla
;;;; Public License, v. 2.0. If a copy of the MPL was not distributed
;;;; with this file, You can obtain one at
;;;; http://mozilla.org/MPL/2.0/.
;;;;
;;;; This Source Code Form is "Incompatible With Secondary Licenses",
;;;; as defined by the Mozilla Public License, v. 2.0.
(ns name.stadig.deque
  (:refer-clojure :exclude [assert last])
  (:require [clojure.core :as clj]
            [name.stadig.deque.protocol :as proto])
  (:import (clojure.lang Counted IHashEq IMeta IObj IPersistentCollection
                         IPersistentList IPersistentStack ISeq Indexed Seqable
                         Sequential)
           (java.io Serializable)
           (java.util Arrays List))
  (:gen-class))

(set! *warn-on-reflection* true)

(defmacro assert
  ([value]
     #_`(clj/assert ~value))
  ([value msg]
     #_`(clj/assert ~value ~msg)))

(declare persistent-deque)
(declare empty-deque)

(defn array-color [^objects array]
  (case (alength array)
    0 :red
    1 :yellow
    4 :yellow
    5 :red
    :green))

(defn compare-color
  [color1 color2]
  (case color1
    :green (case color2
             :green 0
             :yellow 1
             :red 1)
    :yellow (case color2
              :green -1
              :yellow 0
              :red 1)
    :red (case color2
           :green -1
           :yellow -1
           :red 0)))

(defn xor [a b]
  (or (and a (not b)) (and b (not a))))

(defn color [bottom? ^objects prefix ^objects suffix]
  (assert (or (not bottom?) (not (empty? prefix)) (not (empty? suffix))))
  (let [prefix-color (array-color prefix)
        suffix-color (array-color suffix)]
    (if (and bottom? (xor (zero? (alength prefix)) (zero? (alength suffix))))
      (if (pos? (alength prefix))
        prefix-color
        suffix-color)
      (if (neg? (compare-color prefix-color suffix-color))
        prefix-color
        suffix-color))))

(defn green?
  ([deque]
     (green? (and (identical? empty-deque (proto/child deque))
                  (identical? empty-deque (proto/substack deque)))
             (proto/prefix deque)
             (proto/suffix deque)))
  ([bottom? prefix suffix]
     (= :green (color bottom? prefix suffix))))

(defn yellow?
  ([deque bottom?]
     (yellow? (and bottom?
                   (identical? empty-deque (proto/child deque))
                   (identical? empty-deque (proto/substack deque)))
              (proto/prefix deque)
              (proto/suffix deque)))
  ([bottom? prefix suffix]
     (= :yellow (color bottom? prefix suffix))))

(defn red?
  ([deque]
     (red? (and (identical? empty-deque (proto/child deque))
                (identical? empty-deque (proto/substack deque)))
           (proto/prefix deque)
           (proto/suffix deque)))
  ([bottom? prefix suffix]
     (= :red (color bottom? prefix suffix))))

(defn child-yellow? [deque bottom?]
  (let [child (proto/child deque)]
    (yellow? (and bottom?
                  (identical? empty-deque (proto/substack deque))
                  (identical? empty-deque (proto/child child))
                  (identical? empty-deque (proto/substack child)))
             (proto/prefix child)
             (proto/suffix child))))

(defn semiregular? [deque bottom?]
  (if (identical? empty-deque deque)
    true
    (do (assert (or (identical? empty-deque (proto/substack deque))
                    (and (not (identical? empty-deque (proto/child deque)))
                         (child-yellow? deque false)))
                (str "if a deque's substack is not empty, then its child "
                     "must be yellow"))
        (assert (or (identical? empty-deque (proto/substack deque))
                    (green? (proto/substack deque))
                    (red? (proto/substack deque)))
                (str "if a deque's substack is not empty, then its substack "
                     "must be red or green"))
        (assert (or (not (red? deque))
                    (identical? empty-deque (proto/child deque))
                    (green? (proto/child deque))
                    (and (child-yellow? deque bottom?)
                         (or (identical? empty-deque (proto/substack deque))
                             (green? (proto/substack deque)))))
                (str "if a deque is red, then its child/substack must be "
                     "empty or green"))
        (assert (or (identical? empty-deque (proto/child deque))
                    (not (child-yellow? deque bottom?))
                    (identical? empty-deque (proto/child (proto/child deque)))
                    (child-yellow? (proto/child deque) bottom?))
                "if a deque is yellow, then its child must be yellow")
        (assert (semiregular? (proto/child deque)
                              (and bottom?
                                   (identical? empty-deque
                                               (proto/substack deque)))))
        (assert (semiregular? (proto/substack deque) true))
        true)))

(defn regular? [deque]
  (if (identical? empty-deque deque)
    true
    (do (assert (or (green? deque)
                    (and (yellow? deque true)
                         (or (identical? empty-deque (proto/child deque))
                             (green? (proto/child deque))
                             (and (child-yellow? deque true)
                                  (or (identical? empty-deque
                                                  (proto/substack deque))
                                      (green? (proto/substack deque)))))))
                "the topmost non-yellow deque must be green")
        (assert (semiregular? deque true))
        true)))

(defn array-slice [array ^long start ^long end]
  (let [a (object-array (- end start))]
    (System/arraycopy array start a 0 (- end start))
    a))

(defn array-pop [^objects this]
  (let [dst (object-array (dec (alength this)))]
    (System/arraycopy this 1 dst 0 (alength dst))
    dst))

(defn array-push [^objects this value]
  (let [dst (object-array (inc (alength this)))]
    (System/arraycopy this 0 dst 1 (alength this))
    (aset dst 0 value)
    dst))

(defn array-inject [^objects this value]
  (let [dst (Arrays/copyOf this (inc (alength this)))]
    (aset dst (alength this) value)
    dst))

(defn array-eject [^objects this]
  (Arrays/copyOf this (dec (alength this))))

(defn array-last [^objects this]
  (aget this (dec (alength this))))

(defn two-buffer-case [^objects pi ^objects pi1 ^objects si1 ^objects si]
  (let [[pi1 ^objects si1]
        (if (zero? (alength pi1))
          [(array-inject pi1 (aget si1 0))
           (array-pop si1)]
          [pi1 si1])

        [si1 pi1]
        (if (zero? (alength si1))
          [(array-inject si1 (array-last pi1))
           (array-eject pi1)]
          [si1 pi1])

        [^objects pi1 ^objects pi]
        (if (>= (alength pi) 4)
          [(array-push pi1 (array-slice pi (- (alength pi) 2) (alength pi)))
           (array-slice pi 0 (- (alength pi) 2))]
          [pi1 pi])

        [si1 ^objects si]
        (if (>= (alength si) 4)
          [(array-inject si1 (array-slice si 0 2))
           (array-slice si 2 (alength si))]
          [si1 si])

        [pi pi1]
        (if (<= (alength pi) 1)
          [(let [v (Arrays/copyOf pi (+ (alength pi) 2))
                 first ^objects (aget pi1 0)]
             (aset v (alength pi) (aget first 0))
             (aset v (inc (alength pi)) (aget first 1))
             v)
           (array-pop pi1)]
          [pi pi1])

        [si si1]
        (if (<= (alength si) 1)
          [(let [v (object-array (+ (alength si) 2))
                 last ^objects (array-last si1)]
             (aset v 0 (aget last 0))
             (aset v 1 (aget last 1))
             (System/arraycopy si 0 v 2 (alength si))
             v)
           (array-eject si1)]
          [si si1])]
    [pi pi1 si1 si]))

(defn one-buffer-case [^objects pi ^objects pi1 ^objects si1 ^objects si]
  (let [[pi1 si1]
        (if (= (alength si1) 1)
          [(array-inject pi1 (aget si1 0))
           (array-pop si1)]
          [pi1 si1])

        [pi1 ^objects pi]
        (if (>= (alength pi) 4)
          [(array-push pi1 (array-slice pi (- (alength pi) 2) (alength pi)))
           (array-slice pi 0 (- (alength pi) 2))]
          [pi1 pi])

        [^objects pi1 ^objects si]
        (if (>= (alength si) 4)
          [(array-inject pi1 (array-slice si 0 2))
           (array-slice si 2 (alength si))]
          [pi1 si])

        [pi pi1]
        (if (<= (alength pi) 1)
          [(let [v (Arrays/copyOf pi (+ (alength pi) 2))
                 first ^objects (aget pi1 0)]
             (aset v (alength pi) (aget first 0))
             (aset v (inc (alength pi)) (aget first 1))
             v)
           (array-pop pi1)]
          [pi pi1])

        [si pi1]
        (if (<= (alength si) 1)
          [(let [v (object-array (+ (alength si) 2))
                 last ^objects (array-last pi1)]
             (aset v 0 (aget last 0))
             (aset v 1 (aget last 1))
             (System/arraycopy si 0 v 2 (alength si))
             v)
           (array-eject pi1)]
          [si pi1])]
    [pi pi1 si1 si]))

(defn no-buffer-case [^objects pi ^objects pi1 ^objects si1 ^objects si]
  (let [[^objects pi pi1]
        (if (= (alength pi1) 1)
          [(let [v (Arrays/copyOf pi (+ (alength pi) 2))
                 first ^objects (aget pi1 0)]
             (aset v (alength pi) (aget first 0))
             (aset v (inc (alength pi)) (aget first 1))
             v)
           (array-pop pi1)]
          [pi pi1])

        [pi si1]
        (if (= (alength si1) 1)
          [(let [v (Arrays/copyOf pi (+ (alength pi) 2))
                 first ^objects (aget si1 0)]
             (aset v (alength pi) (aget first 0))
             (aset v (inc (alength pi)) (aget first 1))
             v)
           (array-pop si1)]
          [pi si1])

        [pi si]
        (if (= (alength si) 1)
          [(array-inject pi (aget si 0))
           (array-pop si)]
          [pi si])]
    [pi pi1 si1 si]))

(defn regularize [^objects prefix child substack ^objects suffix]
  (let [pi prefix
        pi1 (proto/prefix child)
        si1 (proto/suffix child)
        si suffix]
    (assert (or (and (pos? (alength pi1))
                     (pos? (alength si1)))
                (identical? empty-deque (proto/child child)))
            "level i + 1 may not be red")
    (let [[pi ^objects pi1 ^objects si1 si]
          (cond
           (>= (+ (alength pi1) (alength si1)) 2)
           (two-buffer-case pi pi1 si1 si)
           (and (<= (+ (alength pi1) (alength si1)) 1)
                (or (>= (alength pi) 2)
                    (>= (alength si) 2)))
           (one-buffer-case pi pi1 si1 si)
           (and (<= (+ (alength pi1) (alength si1)) 1)
                (<= (alength pi) 1)
                (<= (alength si) 1))
           (no-buffer-case pi pi1 si1 si)
           :else
           (throw (Exception. "null case")))]
      (if (or (pos? (alength pi1)) (pos? (alength si1)))
        (let [child-child (proto/child child)
              child-substack (proto/substack child)
              ch-yellow? (yellow? (and (identical? empty-deque substack)
                                       (identical? empty-deque child-child)
                                       (identical? empty-deque child-substack))
                                  pi1 si1)]
          (cond (and (identical? empty-deque substack) ch-yellow?)
                (cond (and (identical? empty-deque child-substack)
                           (not (identical? empty-deque child-child))
                           (not (child-yellow? child true)))
                      (let [new-child (persistent-deque pi1 si1)]
                        (assert (yellow? new-child
                                         (identical? empty-deque substack)))
                        (let [deque (persistent-deque pi
                                                      new-child
                                                      child-child
                                                      si)]
                          (assert (regular? deque))
                          deque))
                      (not (identical? empty-deque child-substack))
                      (let [new-child (persistent-deque pi1
                                                        child-child
                                                        empty-deque
                                                        si1)]
                        (assert (yellow? new-child
                                         (identical? empty-deque substack)))
                        (let [deque (persistent-deque pi
                                                      new-child
                                                      child-substack
                                                      si)]
                          (assert (regular? deque))
                          deque))
                      :else
                      (let [deque (persistent-deque
                                   pi
                                   (persistent-deque pi1
                                                     child-child
                                                     child-substack
                                                     si1)
                                   substack
                                   si)]
                        (assert (regular? deque))
                        deque))
                (and (not (identical? empty-deque substack)) (not ch-yellow?))
                (do (assert (identical? empty-deque child-substack))
                    (let [deque (persistent-deque
                                 pi
                                 (if (identical? empty-deque child-child)
                                   (persistent-deque pi1
                                                     substack
                                                     empty-deque
                                                     si1)
                                   (persistent-deque pi1
                                                     child-child
                                                     substack
                                                     si1))
                                 empty-deque
                                 si)]
                      (assert (regular? deque))
                      deque))
                :else
                (let [deque (persistent-deque
                             pi
                             (persistent-deque pi1
                                               child-child
                                               child-substack
                                               si1)
                             substack
                             si)]
                  (assert (regular? deque))
                  deque)))
        (let [deque (persistent-deque pi si)]
          (assert (regular? deque))
          deque)))))

(defn set-prefix [deque ^objects prefix]
  (let [child (proto/child deque)
        substack (proto/substack deque)
        suffix (proto/suffix deque)]
    (cond
     (and (zero? (alength prefix))
          (identical? empty-deque child)
          (identical? empty-deque substack)
          (zero? (alength suffix)))
     empty-deque
     (and (not (identical? empty-deque child)) (red? child))
     (do (assert (identical? empty-deque substack)
                 (str "if a node's child is not yellow, then its substack "
                      "must be empty"))
         (persistent-deque prefix
                           (regularize (proto/prefix child)
                                       (proto/child child)
                                       (proto/substack child)
                                       (proto/suffix child))
                           substack
                           suffix))
     (and (not (identical? empty-deque substack)) (red? substack))
     (persistent-deque prefix
                       child
                       (regularize (proto/prefix substack)
                                   (proto/child substack)
                                   (proto/substack substack)
                                   (proto/suffix substack))
                       suffix)
     (red? (and (identical? empty-deque child)
                (identical? empty-deque substack))
           prefix
           suffix)
     (regularize prefix child substack suffix)
     :else
     (persistent-deque prefix child substack suffix))))

(defn set-suffix [deque ^objects suffix]
  (let [prefix (proto/prefix deque)
        child (proto/child deque)
        substack (proto/substack deque)]
    (cond
     (and (zero? (alength prefix))
          (identical? empty-deque child)
          (identical? empty-deque substack)
          (zero? (alength suffix)))
     empty-deque
     (and (not (identical? empty-deque child)) (red? child))
     (do (assert (identical? empty-deque substack)
                 (str "if a node's child is not yellow, then its substack "
                      "must be empty"))
         (persistent-deque prefix
                           (regularize (proto/prefix child)
                                       (proto/child child)
                                       (proto/substack child)
                                       (proto/suffix child))
                           substack
                           suffix))
     (and (not (identical? empty-deque substack)) (red? substack))
     (persistent-deque prefix
                       child
                       (regularize (proto/prefix substack)
                                   (proto/child substack)
                                   (proto/substack substack)
                                   (proto/suffix substack))
                       suffix)
     (red? (and (identical? empty-deque child)
                (identical? empty-deque substack))
           prefix
           suffix)
     (regularize prefix child substack suffix)
     :else
     (persistent-deque prefix child substack suffix))))

(defn deque-count [deque substack-count]
  (let [prefix (proto/prefix deque)
        child (proto/child deque)
        substack (proto/substack deque)
        suffix (proto/suffix deque)
        c substack-count]
    (let [c (if (not (identical? empty-deque substack))
              (deque-count substack c)
              c)
          c (if (not (identical? empty-deque child))
              (deque-count child c)
              c)]
      (+ (* 2 c) (alength prefix) (alength suffix)))))

(defn deque-nth [deque index more not-found]
  (let [prefix (proto/prefix deque)
        child (proto/child deque)
        substack (proto/substack deque)
        suffix (proto/suffix deque)
        i index]
    (if (< i (alength prefix))
      (nth prefix i)
      (let [i (- i (alength prefix))]
        (if (not (identical? empty-deque child))
          (let [substack (if (identical? empty-deque substack)
                           more
                           substack)
                child-count (* 2 (deque-count child (count substack)))]
            (if (< i child-count)
              (let [t (nth (deque-nth child (quot i 2) substack) (mod i 2))]
                (if t
                  t
                  (let [i (- i child-count)]
                    (if (< i (alength suffix))
                      (nth suffix i)
                      not-found))))
              (let [i (- i child-count)]
                (if (< i (alength suffix))
                  (nth suffix i)
                  not-found))))
          (if (not (identical? empty-deque more))
            (let [child-count (* 2 (count more))]
              (if (< i child-count)
                (let [t (nth (nth more (quot i 2)) (mod i 2))]
                  (if t
                    t
                    (let [i (- i child-count)]
                      (if (< i (alength suffix))
                        (nth suffix i)
                        not-found))))
                (let [i (- i child-count)]
                  (if (< i (alength suffix))
                    (nth suffix i)
                    not-found))))
            (if (< i (alength suffix))
              (nth suffix i)
              not-found)))))))

(deftype PersistentDeque [^objects prefix child substack ^objects suffix]
  Object
  IMeta
  (meta [this] {})
  IObj
  (withMeta [this meta] this)
  Seqable
  (seq [this] this)
  IPersistentCollection
  (count [this]
    (if (identical? empty-deque this)
      0
      (deque-count this 0)))
  (cons [this v]
    (if (and (zero? (alength prefix))
             (identical? empty-deque child)
             (identical? empty-deque substack))
      (set-suffix this (array-push suffix v))
      (set-prefix this (array-push prefix v))))
  (empty [this] empty-deque)
  (equiv [this other])
  ISeq
  (first [this]
    (first (if (and (zero? (alength prefix))
                    (identical? empty-deque child)
                    (identical? empty-deque substack))
             suffix
             prefix)))
  (next [this] (seq (pop this)))
  (more [this] (pop this))
  Sequential
  #_List
  Serializable
  IHashEq
  (hasheq [this])
  IPersistentStack
  (peek [this] (first this))
  (pop [this]
    (if (and (zero? (alength prefix))
             (identical? empty-deque child)
             (identical? empty-deque substack))
      (set-suffix this (array-pop suffix))
      (set-prefix this (array-pop prefix))))
  IPersistentList
  Indexed
  (nth [this n not-found]
    (deque-nth this n empty-deque not-found))
  proto/INode
  (prefix [this] prefix)
  (child [this] child)
  (substack [this] substack)
  (suffix [this] suffix)
  proto/IDeque
  (last [this]
    (array-last (if (and (zero? (alength suffix))
                         (identical? empty-deque child)
                         (identical? empty-deque substack))
                  prefix
                  suffix)))
  (inject [this v]
    (if (and (zero? (alength suffix))
             (identical? empty-deque child)
             (identical? empty-deque substack))
      (set-prefix this (array-inject prefix v))
      (set-suffix this (array-inject suffix v))))
  (eject [this]
    (if (and (zero? (alength suffix))
             (identical? empty-deque child)
             (identical? empty-deque substack))
      (set-prefix this (array-eject prefix))
      (set-suffix this (array-eject suffix)))))

(defn persistent-deque
  ([prefix suffix]
     (persistent-deque prefix empty-deque empty-deque suffix))
  ([^objects prefix child substack ^objects suffix]
     (when-not (and (zero? (alength prefix))
                    (identical? empty-deque child)
                    (identical? empty-deque substack)
                    (zero? (alength suffix)))
       (assert (or (not (empty? prefix)) (not (empty? suffix))))
       (assert (<= (count prefix) 5))
       (assert (<= (count suffix) 5))
       (assert (or (identical? empty-deque substack)
                   (not (identical? empty-deque child)))
               (str "if a deque's substack is not empty, then its child must "
                    "not be empty")))
     (PersistentDeque. prefix child substack suffix)))

(def empty-buffer (object-array 0))

(deftype EmptyDeque []
  Object
  IMeta
  (meta [this] {})
  IObj
  (withMeta [this meta] this)
  Seqable
  (seq [this] nil)
  IPersistentCollection
  (count [this])
  (cons [this v] (persistent-deque empty-buffer (array-push empty-buffer v)))
  (empty [this] empty-deque)
  (equiv [this other])
  ISeq
  (first [this] nil)
  (next [this] nil)
  (more [this] this)
  Sequential
  List
  Serializable
  IHashEq
  (hasheq [this])
  IPersistentStack
  (peek [this] nil)
  (pop [this] nil)
  IPersistentList
  proto/INode
  (prefix [this] empty-buffer)
  (child [this] this)
  (substack [this] this)
  (suffix [this] empty-buffer)
  proto/IDeque
  (last [this] nil)
  (inject [this v]
    (persistent-deque empty-buffer (array-inject empty-buffer v)))
  (eject [this] nil))

(def empty-deque (EmptyDeque.))

(defn -main [& args]
  (into empty-deque (range)))
