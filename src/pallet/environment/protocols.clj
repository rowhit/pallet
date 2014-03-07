(ns pallet.environment.protocols
  "Environment protocols")

;;; # Environment
(defprotocol Environment
  "A protocol for accessing an environment."
  (environment [_] "Returns an environment map"))

(defn has-environment?
  "Predicate to test if x is capable of supplying an environment map."
  [x]
  (satisfies? Environment x))
