# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-05
**Version:** v1.15.3
**Focus:** WinBox zoom creep fix - Complete

---

## 🟢 WinBox Zoom Creep Fix (2026-02-05) - COMPLETE

### Problem

Clicking the WinBox maximize/zoom button repeatedly caused widget dimensions to creep larger with each click:
- Symbol-list height: +16px/click
- Source (CM6) width: +19px/click, height: +13px/click
- Project-list: also creeping

Root cause: `measure-content-size` used measurements that included current container size, creating feedback loops.

### Solution

Fixed `measure-content-size` in `code_browser_v2.cljs` (lines 643-693):

| Content Type | Width Fix | Height Fix |
|--------------|-----------|------------|
| **Lists** | Measure `.list-item` with `nowrap; max-content`, use `offsetWidth` | Sum `offsetHeight` of all items (not `scrollHeight`) |
| **CM6** | Measure `.cm-line` with `nowrap; max-content; inline-block` | Shrink `.cm-scroller` to `1px`, read `scrollHeight` |
| **Fallback** | Unchanged | Shrink `widget-body` to `1px`, read `scrollHeight` |

### Files Changed

| File | Changes |
|------|---------|
| `modules/sente-browser/src/browser/code_browser_v2.cljs` | Rewrote `measure-content-size` (lines 643-693) |

### Verification

- clj-kondo: 0 errors, 0 warnings
- cljfmt: formatted correctly
- Playwright tests: All 6 widget types stable across 5 zoom clicks:
  - Project list (1 item): 150×138 stable
  - Namespace list (204 items): 398×858 stable
  - Symbol list long (20 items): 261×742 stable
  - Symbol list short (6 items): 152×308 stable
  - Source CM6 long (36 lines): 913×858 stable
  - Source CM6 short (8 lines): 666×275 stable

### Not Yet Done

- Git commit not created yet

---

## 🟢 CM6 Editor for Source View (2026-02-04) - COMPLETE

### Goal

Replace plain-text `<pre>` source view with a CodeMirror 6 read-only editor inside WinBox floating windows. Syntax highlighting, line numbers, code folding, and Clojure language support.

### Changes in v1.15.2

| Change | Description |
|--------|-------------|
| **`scittle-cm6` require** | Added `[scittle-cm6 :as cm6]` to code_browser_v2.cljs requires |
| **`source-content` rewrite** | Replaced `[:pre.source-code]` with `[cm6/editor {:value ... :language :clojure :read-only true}]` |
| **CM6 WinBox CSS** | 4 new rules: `.source-view` flex layout, `.cm-container`/`.cm-editor` fill, `.source-info` bar |

### Flex Layout Chain

`.winbox-widget-body` (flex column, h:100%) → `.source-view` (flex:1) → `.cm-container` (flex:1) → `.cm-editor` (h:100%)

### Files Changed

| File | Changes |
|------|---------|
| `modules/sente-browser/src/browser/code_browser_v2.cljs` | Added cm6 require, rewrote `source-content` |
| `modules/sente-browser/src/sente_browser/bootstrap.clj` | CM6 WinBox CSS rules |

### Verification

- clj-kondo: 0 errors, 0 warnings on both files
- cljfmt: All formatted correctly
- Tests: 34 tests, 492 assertions, 0 failures
- Playwright visual test: Syntax highlighting, line numbers, code folding, read-only, resize confirmed

---

## 🟢 Collapsible Vertical Breadcrumb Navigator (2026-02-04) - COMPLETE

### Goal

Replace flat horizontal breadcrumb (`. / ns / sym`) with a collapsible vertical breadcrumb that shows only the deepest segment when collapsed and all levels vertically when expanded, with clickable parent navigation.

### Changes in v1.15.1

| Change | Description |
|--------|-------------|
| **`!breadcrumb-expanded`** | New `defonce` atom tracking set of widget-ids with expanded breadcrumbs |
| **`build-breadcrumb-segments`** | New helper: parses URI into `[{:label :uri :level}]` segments |
| **`uri-breadcrumb` rewrite** | Collapsed: `▶handle-fetch`; Expanded: vertical indented list with clickable parents |
| **`widget-header` updated** | Passes `widget-id` to breadcrumb for per-widget expand/collapse state |
| **`close-widget!` cleanup** | Removes widget from `!breadcrumb-expanded` on close |
| **`unmount!` cleanup** | Resets `!breadcrumb-expanded` on unmount |
| **CSS styles** | 10 new breadcrumb rules: `.breadcrumb-collapsed`, `.breadcrumb-vertical`, `.breadcrumb-parent`, etc. |

### Files Changed

| File | Changes |
|------|---------|
| `modules/sente-browser/src/browser/code_browser_v2.cljs` | New atom, `build-breadcrumb-segments`, rewritten `uri-breadcrumb`, cleanup |
| `modules/sente-browser/src/sente_browser/bootstrap.clj` | Collapsible breadcrumb CSS styles |

### Verification

- clj-kondo: 0 errors, 0 warnings on both files
- cljfmt: All formatted correctly
- Tests: 34 tests, 492 assertions, 0 failures
- Playwright visual test: Collapsed/expanded toggle, parent navigation confirmed

---

## 🟢 URI Query Parameters & Widget Architecture (2026-02-04) - COMPLETE

### Goal

Make widget views fully addressable via URI query parameters (`?view=source&line=42`), so every widget state is encoded in the URI for hash routing and deep linking.

### Changes in v1.15.0

| Change | Description |
|--------|-------------|
| **URI query params** | `parse`/`build` support `?key=value&...` in uri.cljc |
| **`base-uri`** | Strip query params, return base URI only |
| **`with-query`** | Add/merge query params onto a URI string |
| **`derive-property`** | Server derives view from URI `?view=` > explicit `:property` > level default |
| **`handle-fetch` updated** | Uses `base-uri` for DB queries, resolves view from query params |
| **Widget `open-widget!`** | Derives type from `?view=`, ensures all widget URIs have `?view=` |
| **Click handlers** | Use `uri/with-query` instead of `{:type ... :uri ...}` |
| **Hash routing** | Full URI with query params in hash, chain respects `?view=` |
| **Bootstrap CSS** | Widget architecture styles (toolbar, container, header, breadcrumb) |
| **Bootstrap loader** | Loads `uri.cljc` via `/cljc/` route, then `code_browser_v2.cljs` |
| **`/cljc/` route** | Serves `.cljc` files from code-browser-v2 module to browser |

### URI Examples

| URI | View |
|-----|------|
| `dir://bb-mcp-server@abc123?view=ns-list` | Namespace list for project |
| `dir://bb-mcp-server@abc123/some.ns?view=aliases` | Aliases for namespace |
| `dir://bb-mcp-server@abc123/some.ns/my-fn?view=source&line=42` | Source with line hint |

### Files Changed

| File | Changes |
|------|---------|
| `modules/code-browser-v2/src/code_browser/uri.cljc` | Query param parsing/building, `base-uri`, `with-query` |
| `modules/code-browser-v2/test/code_browser/uri_test.cljc` | 4 new test groups (34 tests, 492 assertions total) |
| `modules/code-browser-v2/src/code_browser/handlers.clj` | `derive-property`, updated `handle-fetch` + dispatch |
| `modules/sente-browser/src/browser/code_browser_v2.cljs` | Widget type from query, click handlers, toolbar, hash routing |
| `modules/sente-browser/src/sente_browser/bootstrap.clj` | Widget CSS, `/cljc/` route, v2 loader |

### Verification

- clj-kondo: 0 errors, 0 warnings on all files
- cljfmt: All formatted correctly
- Tests: 34 tests, 492 assertions, 0 failures

---

## 🟢 nrepl-direct --target shorthand & browser routing (2026-02-03) - COMPLETE

### Goal

Simplify `bb nrepl-direct` CLI ergonomics by replacing verbose `--nickname X --service nrepl-proxy --connection browser-1` with compact `-t X/browser-1` shorthand. Add `list` subcommand and explicit `:connection` routing through the nrepl-proxy.

### Changes in v1.14.18

| Change | Description |
|--------|-------------|
| **`-t` / `--target`** | Compact target: `-t server` or `-t server/browser-1` |
| **`list` subcommand** | `bb nrepl-direct list -t server` lists connected browsers |
| **`:connection` routing** | Explicit browser routing in proxy (no session state needed) |
| **`resolve-browser-connection`** | Proxy resolves nickname/id to valid browser connection |
| **`scripts/open-browser.js`** | Headless Playwright browser launcher script |
| **SCITTLE guide updated** | Step 2 uses `open-browser.js`, added nrepl-direct section |
| **`bb.edn` fix** | Fixed `test:nrepl` path to `modules/mcp-nrepl/` |

### Target Format

| Target | Expands to |
|--------|-----------|
| `-t myserver` | `--nickname myserver --service nrepl-server` |
| `-t myserver/browser-1` | `--nickname myserver --service nrepl-proxy --connection browser-1` |

### Files Changed

| File | Changes |
|------|---------|
| `scripts/nrepl_direct_cli.clj` | Added `parse-target`, `cmd-list`, `-t`/`--target`, `--connection` |
| `src/bb_mcp_server/nrepl_direct/client.clj` | `:connection` param threaded through eval/load-file |
| `modules/nrepl-proxy-server/src/nrepl_proxy_server/server.clj` | `resolve-browser-connection`, explicit `:connection` in eval/load-file |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Step 2 uses `open-browser.js`, added nrepl-direct section |
| `scripts/open-browser.js` | New - headless Playwright browser launcher |
| `bb.edn` | Fixed test:nrepl module path |

### Verified End-to-End Flow (2026-02-03)

| Step | Command | Result |
|------|---------|--------|
| Start server | `bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn` | Running |
| Open browser | `node scripts/open-browser.js http://localhost:8091 10` | Connected as browser-3 |
| List browsers | `bb nrepl-direct list -t code-browser-dev` | Shows 3 browsers |
| Eval in browser | `bb nrepl-direct eval "(+ 1 2 3)" -t code-browser-dev/browser-3` | `6` |
| Load file | `bb nrepl-direct load-local-file /tmp/target-test.cljs -t code-browser-dev/browser-3` | `#'user/target-test` |
| Load CM6 | `bb nrepl-direct load-local-file .../scittle_cm6.cljs -t .../browser-3` | `#'scittle-cm6/focus!` |
| Load code browser | `bb nrepl-direct load-local-file .../code_browser.cljs -t .../browser-3` | `#'code-browser/unmount!` |
| Mount | `bb nrepl-direct eval "(code-browser/mount!)" -t .../browser-3` | 206 namespaces rendered |

---

## 🟢 nREPL Response Format Alignment (2026-01-20) - COMPLETE

### Changes in v1.14.17

| Change | Description |
|--------|-------------|
| **Flat response** | nrepl-direct now returns flat map (no nested `:response`) |
| **String status** | `:status` is now `"success"` not `:success` |
| **`:value-parsed`** | EDN-parsed result when possible |
| **`--output edn`** | Clean EDN output for scripting (exit 0/1) |
| **`--stdout2stderr`** | Show eval's stdout on stderr while piping |
| **base64 support** | `input-base64` and `output-base64` in nrepl-direct |

---

## 🟢 Direct nREPL Client (2026-01-20) - COMPLETE

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         bb nrepl-direct CLI                          │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐     ┌─────────────────┐     ┌──────────────┐  │
│  │   bencode.clj   │     │   client.clj    │     │ nrepl_direct │  │
│  │  encode/decode  │────▶│  session mgmt   │────▶│   _cli.clj   │  │
│  │                 │     │  eval/load-file │     │              │  │
│  └─────────────────┘     └─────────────────┘     └──────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### Commands

| Command | Description |
|---------|-------------|
| `eval <code>` | Evaluate Clojure code |
| `load-file <path>` | Load file from server's filesystem |
| `load-local-file <path>` | Read file locally, send as code (for browser) |
| `list` | List connected browsers via nrepl-proxy |
| `describe` | Show nREPL server capabilities |

---

## 🟢 v2 Code Browser Status (2026-02-04) - WORKING

- 34 unit tests pass (492 assertions)
- Widget architecture with URI-parameterized views
- Full browser navigation flow works (project → namespace → symbol → source)
- URI query params for addressable widget state (`?view=source`)
- Must clear database on code changes (`rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock`)

---

## Quick Resume

```bash
# Check server status
bb server:list

# Start code browser dev server
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# Open headless browser
node scripts/open-browser.js http://localhost:8091 10

# List connected browsers
bb nrepl-direct list -t code-browser-dev

# Eval in browser
bb nrepl-direct eval "(+ 1 2 3)" -t code-browser-dev/browser-1

# Load file to browser
bb nrepl-direct load-local-file path/to/file.cljs -t code-browser-dev/browser-1

# Run tests
bb test:module code-browser-v2
bb lint && bb format
```

---

## Phase R3: Feature Parity (In Progress)

| Task | Description | Status |
|------|-------------|--------|
| R3.1 | Symbol inspector (Source, Doc, Deps, Callers tabs) | ✅ Done |
| R3.2 | Aliases panel (separate alias/refer entities) | ✅ Done |
| R3.3 | Multi-file namespace support | ✅ Done |
| R3.4 | File watching / cache invalidation | Pending |
| R3.5 | Git status display | Pending |

---

## Key Files

| File | Purpose |
|------|---------|
| `scripts/nrepl_direct_cli.clj` | nrepl-direct CLI (eval, load-file, list, -t shorthand) |
| `src/bb_mcp_server/nrepl_direct/client.clj` | Standalone nREPL client library |
| `modules/nrepl-proxy-server/src/nrepl_proxy_server/server.clj` | nREPL proxy with browser routing |
| `scripts/open-browser.js` | Headless Playwright browser launcher |
| `modules/code-browser-v2/src/code_browser/uri.cljc` | URI parsing/building with query param support |
| `modules/code-browser-v2/src/code_browser/handlers.clj` | Stateless fetch API with view derivation |
| `modules/sente-browser/src/browser/code_browser_v2.cljs` | Widget-based browser UI |
| `modules/sente-browser/src/sente_browser/bootstrap.clj` | HTML/CSS/JS bootstrap + routes |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Scittle dev setup guide |
| `docs/bb-nrepl-direct-user-guide.md` | nrepl-direct usage guide |

---

*Last Updated: 2026-02-04*
