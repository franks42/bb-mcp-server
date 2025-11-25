# Clojure Development Standards

## Core Principles

1. **Honesty is Mandatory** - Always run code, report actual output, admit uncertainty
2. **Context Awareness** - Check project structure before making changes
3. **Verification Required** - Lint, format, and test after every change
4. **Telemetry Required** - Use `taoensso.trove` for all logging

## Code Style

### Threading Macros
Prefer threading macros for readability:
```clojure
;; Good
(-> user
    (assoc :last-login (now))
    (update :login-count inc))

;; Bad
(update (assoc user :last-login (now)) :login-count inc)
```

### Error Handling
Use structured errors with ex-info:
```clojure
(throw (ex-info "Payment failed"
               {:type :payment-error
                :order-id id
                :amount amt}))
```

### Keep Functions Small
Focus on single responsibilities. Destructure arguments to clarify expectations.

## Verification Workflow

After EVERY code change:
```bash
clj-kondo --lint <file>  # Must be 0 errors, 0 warnings
cljfmt check <file>      # Must have no formatting issues
bb test:modules          # Must pass all tests
```

## Telemetry

Every function with I/O or business logic must emit telemetry:
```clojure
(require '[taoensso.trove :as log])

(defn process-request [request]
  (log/log! {:level :info
             :id    ::process-request
             :msg   "Processing request"
             :data  {:request-id (:id request)}})
  (try
    (let [result (do-work request)]
      (log/log! {:level :info
                 :id    ::process-request-complete
                 :msg   "Request processed"
                 :data  {:request-id (:id request)}})
      result)
    (catch Exception e
      (log/log! {:level :error
                 :id    ::process-request-failed
                 :msg   "Request processing failed"
                 :error e
                 :data  {:request-id (:id request)}})
      (throw e))))
```

## Security

- Never hardcode secrets
- Load from environment variables
- Redact sensitive data in logs
- Validate input at system boundaries
