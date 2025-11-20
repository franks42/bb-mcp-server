# telemere-lite

Lightweight telemetry wrapper around BB's built-in logging for AI-observable structured output.

## Features

- **Cross-platform**: Works with both Babashka and Scittle
- **Structured logging**: JSON output for AI visibility
- **BB startup monitoring**: Captures system info and module loading
- **Performance tracking**: Built-in timing macros
- **File-based output**: Uses BB's built-in Timbre for file logging

## Usage

```clojure
(require '[telemere-lite.core :as tel])

;; Initialize telemetry (automatically configures file logging)
(tel/startup!)

;; Basic logging
(tel/log! :info "Application started" {:version "1.0.0"})
(tel/log! :error "Failed to connect" {:host "localhost" :port 8080})

;; Module loading telemetry
(tel/module-load! "my-module")
(Thread/sleep 100) ;; simulate loading
(tel/module-loaded! "my-module" 100)

;; Performance tracking
(tel/with-timing "expensive-operation"
  (Thread/sleep 1000))

;; Error tracking
(tel/error! "Database error" {:error-type "connection-timeout"})
```

## Output

Logs are written to `logs/telemetry.jsonl` in structured JSON format:

```json
{"timestamp":"2025-10-25T00:17:48Z","level":"info","ns":"telemere-lite.core","msg":["BB startup initiated",{"bb-version":"1.12.209","java-version":"25"}],"context":null}
```

## Configuration

- `*telemetry-enabled*` - Enable/disable telemetry (default: true)
- `*log-level*` - Minimum log level (default: :info)
- `*output-file*` - Log file path (default: "logs/telemetry.jsonl")