# Review of Datalevin MCP Options & Interface Design

This document captures observations and recommendations based on the review of `datalevin-options.md`, specifically focusing on the proposed MCP tool interface.

## 1. General Observations

### 1.1 The "EDN Native" Decision
Sticking to EDN strings for input/output is the correct architectural decision for a Clojure/Babashka environment.
*   **Why:** Mapping Datalog's rich set of types (keywords, sets, UUIDs, instants) to JSON creates a significant "impedance mismatch" and loss of fidelity.
*   **Requirement:** Robust error handling for the EDN parser is critical. If the AI generates invalid EDN (e.g., missing a closing bracket), the error message returned to the tool must be specific enough for the AI to self-correct.

### 1.2 `find-by` Utility
Including `find-by` (Category B) is an excellent design choice.
*   **Why:** Writing correct Datalog for simple lookups (e.g., "get user by email") is a common source of syntax errors for LLMs. `find-by` abstracts this pattern, reducing cognitive load and error rates.

### 1.3 Comparison with `datomic-mcp`
The proposed design is significantly more "agentic" than the reference `datomic-mcp` implementation due to **Category C (Datalevin-Specific)** tools.
*   `search-text` and `search-vector` effectively turn the database into a native RAG engine, which is a major differentiator for AI workflows.

## 2. Missing Tool Specifications

The following tools were listed in the summary tables of `datalevin-options.md` but lacked detailed specifications in Section 9.3.

### `describe-entity`
```clojure
{:name        "describe-entity"
 :description "Returns all attributes and values for a specific entity.
               Useful for exploring the shape of data without writing a pull pattern.
               Returns a map of attributes to values."
 :input       {:eid 123} ; Accepts number (EID) or unique identifier lookup
 :output      {:db/id 123
               :person/name "Alice"
               :person/email "alice@example.com"
               :person/friends [{:db/id 456} {:db/id 789}]}}
```

### `kv-get`
```clojure
{:name        "kv-get"
 :description "Retrieve a value from the underlying Key-Value store.
               Provides direct access to the LMDB layer, bypassing the Datalog engine."
 :input       {:key "config/system-settings"} ; Key can be any EDN value
 :output      {:value {:theme "dark" :notifications true}}}
```

### `kv-range`
```clojure
{:name        "kv-range"
 :description "Scan a range of keys in the Key-Value store.
               Returns a sequence of [key value] pairs.
               Useful for iterating over prefixes or time-series data stored in KV."
 :input       {:start "config/"  ; Start key (inclusive)
               :end   "config0"  ; End key (exclusive), or nil for open-ended
               :limit 50}        ; Max results to return
 :output      [["config/a" 1] ["config/b" 2]]}
```

## 3. Suggested Enhancements

### 3.1 Time Travel Support
Datalevin supports time travel (`as-of`, `since`), but the current tool definitions do not expose this.
*   **Recommendation:** Add an optional `:as-of` parameter (accepting a timestamp or tx-id) to `query`, `pull`, `find-by`, and `describe-entity`.
*   **Use Case:** Allows the AI to answer questions like "What was the state of this entity yesterday?" or "How did this configuration look before the last update?"

### 3.2 `transact` Output Verbosity
*   **Observation:** Returning the full `tx-data` (all datoms added/retracted) in the transaction result might be too verbose for large updates and could overflow context windows.
*   **Recommendation:** The output should focus on metadata: `tx-id`, `tempids` (mappings for new entities), and a count of affected datoms.

### 3.3 "Safe-Read" Wrapper
*   **Recommendation:** Implement a wrapper around the EDN parser for all tool inputs. This wrapper should catch `clojure.lang.ExceptionInfo` or reader errors and return a structured, helpful error message to the model (e.g., "Invalid EDN: Unmatched delimiter at line 1, column 15") rather than a generic 500 error.
