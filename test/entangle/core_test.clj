(ns entangle.core-test
  (:require [clojure.core.async :as a]
            [clj-diff.core :as diff]
            [clojure.test :refer :all]
            [entangle.core :refer :all]))

(deftest state-change-handling
  (let [data-in (a/chan 1)
        data-out (a/chan 1)
        state (atom "")
        ack (start-sync state data-in data-out)]

    (testing "Changing an atom sends the patches to the other side"
      (reset! state "foo")
      (is (= {:version 0 :patch (diff/diff "" "foo")}
            (a/<!! data-out))))

    (testing "Sending a patch to the atom eventually alters it's value"
      (a/>!! data-in {:version 1 :patch (diff/diff "foo" "foobar")})
      (a/<!! ack)
      (is (= "foobar" @state))
      (is {:version 0 :patch (diff/diff "foo" "foobar")}
        (a/<!! data-out)))

    (testing "Cleans up eagerly when data-in closes"
      (a/close! data-in)
      (is (nil? (a/<!! ack)))
      (is (nil? (a/<!! data-in)))
      (is (nil? (a/<!! data-out))))))

(deftest entangling-two-atoms
  (let [A->B (a/chan 1) B->A (a/chan 1)
        stateA (atom "")
        stateB (atom "")
        ackA (start-sync stateA B->A A->B 0 :atom-a)
        ackB (start-sync stateB A->B B->A 0 :atom-b)]

    (testing "Changing an atoms value updates the other"
      (swap! stateA (fn [thing] (str thing "foo")))
      (a/<!! ackB)
      (is (= "foo" @stateB))

      (reset! stateB "bar")
      (a/<!! ackA)
      (a/<!! ackB)
      (is (= "bar" @stateA) "Reseting stateB will update stateA")
      )

    (testing "Changes to the same atom twice work"
      (reset! stateA "a")
      (a/<!! ackB)
      (a/<!! ackA)
      (reset! stateA "b")
      (a/<!! ackB)
      (a/<!! ackA)
      (reset! stateA "c")
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "c" @stateB)))


    (a/close! A->B)))

;; (deftest shutdown-on-error
;;   (let [A->B (a/chan 1) B->A (a/chan 1)
;;         stateA (atom "")
;;         stateB (atom "")
;;         ackA (start-sync stateA B->A A->B 1)
;;         ackB (start-sync stateB A->B B->A 2)]
;;     (testing "Exceptions shuts down the channels and removes the watch")

;;   )

(defn test-ns-hook
  "Because we're doing some IO-related stuff, we run the tests in a separate
  thread and bind the execution time.

  This way, we don't need to restart the repl because the repl thread has hung.
  "
  []
  (let [f (future (do (state-change-handling)
                      (entangling-two-atoms)))]
    (.get f 1000 java.util.concurrent.TimeUnit/MILLISECONDS)))
