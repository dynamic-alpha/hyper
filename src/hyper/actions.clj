(ns hyper.actions
  "Action system for handling user interactions.
   
   Actions are anonymous functions registered with unique IDs.
   They are invoked via POST requests from the client.")

;; Action registry: {session-id {action-id {:fn f :session-id sid :tab-id tid}}}

;; TODO: Define actions atom
;; TODO: Implement register-action!
;; TODO: Implement execute-action!
;; TODO: Implement cleanup-session-actions!
;; TODO: Implement get-action
