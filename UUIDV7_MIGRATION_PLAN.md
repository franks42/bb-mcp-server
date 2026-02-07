# UUIDv7 Migration Plan

**Goal:** Replace ALL UUID v4 (`random-uuid` / `java.util.UUID/randomUUID`) and
**delete** the hand-rolled `mcp-nrepl.utils.uuid-v7` — use only
`com.github.franks42/uuidv7` v0.5.0 everywhere (server and browser).

**Library:** `com.github.franks42.uuidv7.core` (`.cljc` — JVM, Babashka, CLJS, nbb, scittle)

**Key API:**

| Function | Returns | Notes |
|----------|---------|-------|
| `(uuidv7)` | Native UUID object | Monotonically ordered within ms |
| `(str (uuidv7))` | UUID string | For string-keyed maps |
| `(uuidv7? u)` | boolean | Validates version 7 |
| `(extract-ts u)` | long (epoch ms) | Timestamp extraction |
| `(extract-inst u)` | Date/inst | Human-readable timestamp |
| `(make-generator)` | `(fn [] uuid)` | Independent monotonic sequence |

**Why:** Temporally sortable UUIDs give us built-in ordering for logs, events,
audit trails, and debugging. The embedded timestamp means every ID tells you
*when* it was created. Having one portable implementation across server (BB/JVM)
and browser (scittle) eliminates the split between `random-uuid` and the
hand-rolled uuid_v7.

---

## Phase 1: Add dependency + scittle serving

### 1.1 Add to `bb.edn`

Add `com.github.franks42/uuidv7 {:mvn/version "0.5.0"}` to `:deps`.

### 1.2 Load `uuidv7/core.cljc` in browser via CDN script tag

`bootstrap.clj` has TWO HTML pages. In **both**, add the uuidv7 script tag
as the **first scittle library loaded** — right after scittle core + FakeWebSocket,
before trove and everything else. The library has zero deps so it can load
this early. This ensures trove logging, sente channel setup, and all subsequent
code can use uuidv7 IDs.

**Page 1 (basic nREPL HTML):** Insert after FakeWebSocket (line ~114), before scittle.nrepl:

```
  <!-- 2. FakeWebSocket -->
  ...FakeWebSocket script...

  <!-- 3. UUIDv7 (zero deps — load first so all code can use temporal IDs) -->
  <script src="https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.5.0/src/com/github/franks42/uuidv7/core.cljc"
          type="application/x-scittle"></script>

  <!-- 4. Scittle nREPL -->
```

**Page 2 (code-browser HTML):** Insert after FakeWebSocket (line ~562), before React/scittle plugins:

```
  <!-- 2. FakeWebSocket -->
  ...FakeWebSocket script...

  <!-- 3. UUIDv7 (zero deps — load first so all code can use temporal IDs) -->
  <script src="https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.5.0/src/com/github/franks42/uuidv7/core.cljc"
          type="application/x-scittle"></script>

  <!-- 4. React + ReactDOM -->
```

No `/cljc/` route changes needed. Scittle v0.6.17+ handles `.cljc` reader
conditionals. After loading, ALL subsequent browser code can use:

```clojure
(require '[com.github.franks42.uuidv7.core :as uuidv7])
(uuidv7/uuidv7)  ;=> #uuid "0195xxxx-..."
```

**Loading order (both pages):**
scittle.js → FakeWebSocket → **uuidv7** → scittle.nrepl → trove → sente-lite → app code

---

## Phase 2: Delete hand-rolled `mcp-nrepl.utils.uuid-v7`

### 2.1 DELETE `modules/mcp-nrepl/src/mcp_nrepl/utils/uuid_v7.clj`

Remove the entire 182-line file. No wrapper, no delegation — clean break.

### 2.2 Update the 4 consumer files

Each file currently does `(:require [mcp-nrepl.utils.uuid-v7 :as uuid])`.
Replace with `(:require [com.github.franks42.uuidv7.core :as uuidv7])` and
update call sites:

#### 2.2a `modules/mcp-nrepl/src/mcp_nrepl/client/messaging.clj`

- **ns require:** `[mcp-nrepl.utils.uuid-v7 :as uuid]` → `[com.github.franks42.uuidv7.core :as uuidv7]`
- **Line 8-11 `generate-id`:** Currently calls `(uuid/uuid-v7-with-tag :tag tag)`.
  Rewrite to: `(str (uuidv7/uuidv7) "-" tag)`
- **Line 183:** No change needed — calls `(generate-id)` which is updated above.

#### 2.2b `modules/mcp-nrepl/src/mcp_nrepl/state/connection.clj`

- **ns require:** `[mcp-nrepl.utils.uuid-v7 :as uuid]` → `[com.github.franks42.uuidv7.core :as uuidv7]`
- **Line 76:** `(uuid/uuid-v7-string)` → `(str (uuidv7/uuidv7))`
- **Line 325:** `(uuid/uuid-v7-string)` → `(str (uuidv7/uuidv7))`

#### 2.2c `modules/mcp-nrepl/src/mcp_nrepl/state/messages.clj`

- **ns require:** `[mcp-nrepl.utils.uuid-v7 :as uuid]` → `[com.github.franks42.uuidv7.core :as uuidv7]`
- **Line 150:** `(uuid/uuid-v7-with-tag :tag "msg")` → `(str (uuidv7/uuidv7) "-msg")`

#### 2.2d `modules/sente-browser/src/sente_browser/server.clj`

- **ns require:** `[mcp-nrepl.utils.uuid-v7 :as uuid]` → `[com.github.franks42.uuidv7.core :as uuidv7]`
- **Line 73:** `(uuid/uuid-v7-string)` → `(str (uuidv7/uuidv7))`

### 2.3 Grep for any remaining references to `mcp-nrepl.utils.uuid-v7`

Check docs, design docs, test fixtures, and comments. Remove all references.

---

## Phase 3: Replace the central HTTP utility — `http-core.util/generate-uuid`

### 3.1 `modules/http-core/src/http_core/util.clj` (line 54)

- Add `[com.github.franks42.uuidv7.core :as uuidv7]` to `:require`
- Change `generate-uuid` body from `(str (java.util.UUID/randomUUID))` to `(str (uuidv7/uuidv7))`

**Downstream consumers (no code changes needed — they call `generate-uuid`):**
- `modules/mcp-http/src/mcp_http/session.clj:48` — `(util/generate-uuid)` for MCP session IDs
- `modules/streamable-http/src/streamable_http/util.clj:11` — re-exports `generate-uuid`

### 3.2 Verify test: `modules/http-core/test/http_core/util_test.clj`

Existing test checks UUID format via regex. UUIDv7 strings are standard UUID
format — test should pass. Consider adding a `(uuidv7/uuidv7? ...)` assertion.

---

## Phase 4: Replace `random-uuid` / `java.util.UUID/randomUUID` in all remaining files

Each file: add `[com.github.franks42.uuidv7.core :as uuidv7]` to `:require`,
replace UUID calls.

### 4.1 `src/bb_mcp_server/mcp_client.clj` (4 call sites: lines 76, 113, 317, 380)

- Add require
- Replace `(random-uuid)` → `(uuidv7/uuidv7)` at all 4 sites
- JSON-RPC `:id` accepts any value — UUID object serialized to string by cheshire

### 4.2 `src/bb_mcp_server/nrepl_direct/client.clj` (line 92)

- Add require
- Replace `(str (java.util.UUID/randomUUID))` → `(str (uuidv7/uuidv7))`

### 4.3 `modules/nrepl-proxy-server/src/nrepl_proxy_server/session.clj` (line 28)

- Add require
- Replace `(str (java.util.UUID/randomUUID))` → `(str (uuidv7/uuidv7))`

### 4.4 `modules/message-bus/src/message_bus/core.clj` (line 40)

- Add require
- Replace `(str (random-uuid))` → `(str (uuidv7/uuidv7))`

### 4.5 `modules/claude-manager/src/claude_manager/registry.clj` (line 125)

- Add require
- Replace `(subs (str (random-uuid)) 0 8)` → `(subs (str (uuidv7/uuidv7)) 0 8)`
- Bonus: first 8 chars are now timestamp high bits — tells you *when* the request was made

### 4.6 `modules/ai-orchestrator/src/ai_orchestrator/registry.clj` (line 107)

- Same pattern as 4.5

### 4.7 `modules/sente-browser/src/sente_browser/bootstrap.clj` (lines 146, 860)

- Add require
- Replace `(random-uuid)` → `(uuidv7/uuidv7)` at both `get-or-create-session-id` definitions
- Note: there are TWO duplicate definitions — consider deduplicating

### 4.8 `modules/sente-browser/src/browser/scittle_cm6.cljs` (line 183)

- Add `(require '[com.github.franks42.uuidv7.core :as uuidv7])` to the scittle ns
- Replace `(random-uuid)` → `(uuidv7/uuidv7)`
- Depends on Phase 1.2 (CDN script tag loads uuidv7 before this file)

### 4.9 `modules/claude-manager/test/mock_claude.clj` (line 19)

- Add require
- Replace `(subs (str (random-uuid)) 0 8)` → `(subs (str (uuidv7/uuidv7)) 0 8)`

### 4.10 `modules/claude-subprocess-provider/test/mock_claude.clj` (line 19)

- Same as 4.9

---

## Phase 5: Cleanup & verification

### 5.1 Grep for ALL remaining UUID v4 references

```bash
grep -rn "random-uuid\|java\.util\.UUID/randomUUID\|java\.util\.UUID\|mcp-nrepl\.utils\.uuid" \
  src/ modules/ scripts/ --include="*.clj" --include="*.cljs" --include="*.cljc"
```

Any hits must be addressed or documented as intentional exceptions.

### 5.2 Lint + format + test

```bash
clj-kondo --lint src modules
cljfmt check src modules
bb test:modules
```

All must pass with 0 errors, 0 warnings.

### 5.3 Verify scittle loading

Start a dev server, open a browser, confirm that `(uuidv7/uuidv7)` works in
the browser console and that CM6 editors render correctly.

---

## Summary: Files to change

| # | File | Change | Risk |
|---|------|--------|------|
| 0 | `bb.edn` | Add uuidv7 dep | None |
| 1 | `modules/sente-browser/.../bootstrap.clj` | CDN script tag for uuidv7 + replace `random-uuid` | Low |
| 2 | `modules/mcp-nrepl/.../utils/uuid_v7.clj` | **DELETE** | None |
| 3 | `modules/mcp-nrepl/.../client/messaging.clj` | Require uuidv7, inline tag logic | Low |
| 4 | `modules/mcp-nrepl/.../state/connection.clj` | Require uuidv7, replace calls | Low |
| 5 | `modules/mcp-nrepl/.../state/messages.clj` | Require uuidv7, replace calls | Low |
| 6 | `modules/sente-browser/.../server.clj` | Require uuidv7, replace calls | Low |
| 7 | `modules/http-core/.../util.clj` | `generate-uuid` → uuidv7 | Low |
| 8 | `src/bb_mcp_server/mcp_client.clj` | 4× `random-uuid` → `uuidv7` | Low |
| 9 | `src/bb_mcp_server/nrepl_direct/client.clj` | `UUID/randomUUID` → `uuidv7` | Low |
| 10 | `modules/nrepl-proxy-server/.../session.clj` | `UUID/randomUUID` → `uuidv7` | Low |
| 11 | `modules/message-bus/.../core.clj` | `random-uuid` → `uuidv7` | Low |
| 12 | `modules/claude-manager/.../registry.clj` | 8-char prefix → uuidv7 | Low |
| 13 | `modules/ai-orchestrator/.../registry.clj` | 8-char prefix → uuidv7 | Low |
| 14 | `modules/sente-browser/.../scittle_cm6.cljs` | Browser `random-uuid` → uuidv7 | Low |
| 15 | `modules/claude-manager/test/mock_claude.clj` | Test mock → uuidv7 | None |
| 16 | `modules/claude-subprocess-provider/test/mock_claude.clj` | Test mock → uuidv7 | None |

**Total: 1 file deleted, 16 files changed**

---

## Risks & Considerations

1. **Scittle loading order:** The CDN `<script>` tag for `uuidv7/core.cljc` must
   appear before any browser scripts that use it. Scittle v0.6.17+ required for
   `.cljc` reader conditional support.

2. **UUID string format:** UUIDv7 strings are standard UUID format
   (`xxxxxxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx`). Any validation regex checking for
   version `4` at position 14 would break — check the http-core test.

3. **Tag suffix pattern:** The `uuid-v7-with-tag` pattern (`"<uuid>-eval"`) is
   used in 2 files. This is inlined as `(str (uuidv7/uuidv7) "-" tag)` — simple
   string concat, no wrapper function needed.

4. **Thread safety:** The library uses `swap!` on an atom — thread-safe on JVM/BB.

5. **No transitive deps:** The library's `deps.edn` has `{:deps {}}`.

6. **Backwards compatibility:** Existing UUIDs stored in databases or logs are
   v4. New ones will be v7. Both are valid UUID format — no migration needed for
   stored data. Any code that reads/compares UUIDs by string equality is unaffected.
