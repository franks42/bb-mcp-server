# Webserver Module Implementation Plan

**Status:** Planning
**Version:** 0.1.0
**Module Name:** `webserver`

---

## Overview

Simple static file server for human-facing output. Allows AI agents and modules to present data to users via a web browser.

---

## Features

| Feature | Priority | Description |
|---------|----------|-------------|
| Static file serving | P0 | HTML/CSS/JS/assets with MIME types |
| Configurable root | P0 | Custom webroot directory |
| Configurable port | P0 | Default 9876 |
| Multiple servers | P0 | Run concurrent servers on different ports |
| Auto-open browser | P0 | Opens default browser on start |
| Health endpoint | P0 | `/_health` for monitoring |
| Live reload | P1 | WebSocket-based auto-refresh |
| Hiccup templates | P1 | Render `.hiccup` files to HTML |
| Directory listing | P2 | Optional, when no index.html |

---

## Implementation Steps

### Phase 1: Core Server (P0)

| # | Task | Acceptance Criteria |
|---|------|---------------------|
| 1.1 | Create module structure | `modules/webserver/`, module.edn |
| 1.2 | Implement MIME type map | Common types: html, css, js, json, png, jpg, svg, etc. |
| 1.3 | Implement file handler | Serve files from root, 404 for missing |
| 1.4 | Implement index.html fallback | Serve index.html for directory requests |
| 1.5 | Implement `start!` function | Returns server instance |
| 1.6 | Implement `stop!` function | By port or name |
| 1.7 | Implement `list-servers` | Returns all running servers |
| 1.8 | Implement auto-open browser | Uses `open` command on macOS |
| 1.9 | Add health endpoint | `/_health` returns `{:status "ok"}` |
| 1.10 | Server registry | Atom tracking running servers |

### Phase 2: Live Reload (P1)

| # | Task | Acceptance Criteria |
|---|------|---------------------|
| 2.1 | WebSocket endpoint | `/_reload` for clients |
| 2.2 | File watcher | Watch root directory for changes |
| 2.3 | Inject reload script | Auto-inject JS into HTML responses |
| 2.4 | Broadcast on change | Send "reload" to all connected clients |
| 2.5 | Runtime toggle | `set-reload!` to enable/disable |

### Phase 3: Hiccup Templates (P1)

| # | Task | Acceptance Criteria |
|---|------|---------------------|
| 3.1 | Detect .hiccup files | Serve as HTML with proper MIME |
| 3.2 | Read and render | `(hiccup/html (read-string content))` |
| 3.3 | Error handling | Show error page on invalid hiccup |

### Phase 4: Testing (Playwright)

| # | Task | Acceptance Criteria |
|---|------|---------------------|
| 4.1 | Install Playwright | Via npm |
| 4.2 | Test static file serving | Load index.html, verify content |
| 4.3 | Test MIME types | CSS/JS load correctly |
| 4.4 | Test live reload | Modify file, verify browser refreshes |
| 4.5 | Test multiple servers | Two servers on different ports |

---

## API

```clojure
(require '[webserver.core :as ws])

;; Start server (opens browser)
(ws/start! {:port 9876 :root "./webroot"})

;; Full options
(ws/start! {:port 9876
            :root "./webroot"
            :name "dashboard"      ; optional identifier
            :reload true           ; live reload (default: false)
            :open-browser true     ; auto-open (default: true)
            :hiccup true})         ; render .hiccup files (default: false)

;; List running servers
(ws/list-servers)
;; => [{:name "dashboard" :port 9876 :root "./webroot" :reload true}]

;; Toggle live reload at runtime
(ws/set-reload! 9876 true)
(ws/set-reload! "dashboard" false)

;; Stop server
(ws/stop! 9876)
(ws/stop! "dashboard")
(ws/stop-all!)
```

---

## Directory Structure

```
webroot/
├── index.html        # Default page
├── css/
│   └── style.css
├── js/
│   └── app.js
├── assets/           # Images, fonts
├── data/             # JSON data files
└── templates/        # .hiccup files (optional)
```

---

## Module Structure

```
modules/webserver/
├── module.edn
├── README.md              # User guide
├── src/webserver/
│   ├── core.clj           # Public API
│   ├── server.clj         # http-kit server
│   ├── handler.clj        # Request handling, MIME types
│   ├── reload.clj         # Live reload WebSocket + watcher
│   └── hiccup.clj         # Hiccup rendering (optional)
└── test/
    ├── webserver/
    │   └── core_test.clj  # Unit tests
    ├── playwright/
    │   └── browser_test.js # Playwright tests
    └── run_tests.clj
```

---

## MIME Types

```clojure
(def mime-types
  {"html" "text/html"
   "htm"  "text/html"
   "css"  "text/css"
   "js"   "application/javascript"
   "json" "application/json"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "svg"  "image/svg+xml"
   "ico"  "image/x-icon"
   "woff" "font/woff"
   "woff2" "font/woff2"
   "ttf"  "font/ttf"
   "eot"  "application/vnd.ms-fontobject"
   "txt"  "text/plain"
   "md"   "text/markdown"
   "xml"  "application/xml"})
```

---

## Live Reload Implementation

```clojure
;; Injected into HTML responses when reload=true
(def reload-script
  "<script>
   (function() {
     var ws = new WebSocket('ws://' + location.host + '/_reload');
     ws.onmessage = function(e) { if (e.data === 'reload') location.reload(); };
     ws.onclose = function() { setTimeout(function() { location.reload(); }, 1000); };
   })();
   </script>")

;; File watcher (using babashka.fs)
(defn start-watcher! [root notify-fn]
  (let [watcher (fs/watch root)]
    (future
      (doseq [event (fs/watch-seq watcher)]
        (notify-fn event)))
    watcher))
```

---

## Dependencies

- `org.httpkit/http-kit` - Already available
- `hiccup/hiccup` - Need to verify bb compatibility
- `babashka.fs` - Already available (file watching)

---

## Testing with Playwright

```javascript
// playwright/browser_test.js
const { test, expect } = require('@playwright/test');

test('serves index.html', async ({ page }) => {
  await page.goto('http://localhost:9876');
  await expect(page).toHaveTitle(/Test Page/);
});

test('loads CSS correctly', async ({ page }) => {
  await page.goto('http://localhost:9876');
  const bg = await page.evaluate(() =>
    getComputedStyle(document.body).backgroundColor
  );
  expect(bg).toBe('rgb(240, 240, 240)');
});

test('live reload works', async ({ page }) => {
  await page.goto('http://localhost:9876');
  // Modify file externally, verify page reloads
});
```

---

*Created: 2025-11-27*
