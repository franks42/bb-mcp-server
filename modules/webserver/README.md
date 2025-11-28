# Webserver Module

Simple static file server with live reload and Hiccup template support. Use it to serve HTML/CSS/JS for human-facing dashboards and UIs.

## Quick Start

```clojure
(require '[webserver.core :as ws])

;; Start server (opens browser automatically)
(ws/start! {:port 9876 :root "./webroot"})

;; With options
(ws/start! {:port 9876
            :root "./webroot"
            :name "dashboard"
            :reload true        ;; Live reload
            :hiccup true        ;; Render .hiccup files
            :open-browser true})

;; List running servers
(ws/list-servers)
;; => [{:port 9876 :root "/path/to/webroot" :name "dashboard" ...}]

;; Stop server
(ws/stop! 9876)
(ws/stop! "dashboard")
(ws/stop-all!)
```

## Options

| Option | Default | Description |
|--------|---------|-------------|
| `:port` | `9876` | Port number |
| `:root` | `"./webroot"` | Directory to serve |
| `:name` | `"server-{port}"` | Server identifier |
| `:reload` | `false` | Enable live reload via WebSocket |
| `:hiccup` | `false` | Render `.hiccup` files as HTML |
| `:open-browser` | `true` | Open browser on start |

## Live Reload

When `:reload true`, the server:
1. Injects a reload script into HTML pages
2. Watches the root directory for changes
3. Sends reload signal via WebSocket
4. Browser refreshes automatically

Toggle at runtime:
```clojure
(ws/set-reload! 9876 true)   ;; Enable
(ws/set-reload! 9876 false)  ;; Disable
```

## Hiccup Templates

With `:hiccup true`, files ending in `.hiccup` are rendered as HTML.

**page.hiccup:**
```clojure
[:html
 [:head [:title "My Page"]]
 [:body [:h1 "Hello World"]]]
```

Access via: `http://localhost:9876/page.hiccup`

## Directory Structure

```
webroot/
├── index.html
├── css/
│   └── style.css
├── js/
│   └── app.js
└── page.hiccup
```

## Health Endpoint

Check server status:
```bash
curl http://localhost:9876/_health
# {"status": "ok", "root": "/path/to/webroot"}
```

## Multiple Servers

Run multiple servers on different ports:

```clojure
(ws/start! {:port 9876 :root "./dashboard" :name "main"})
(ws/start! {:port 9877 :root "./docs" :name "docs"})

(ws/list-servers)
;; => [{:port 9876 :name "main" ...}, {:port 9877 :name "docs" ...}]

(ws/stop! "main")
(ws/stop-all!)
```

## Testing

Unit tests:
```bash
bb test:webserver
```

Playwright browser tests:
```bash
cd modules/webserver/test/playwright
npm install
npx playwright test
```

## MIME Types

Supported: `.html`, `.css`, `.js`, `.json`, `.png`, `.jpg`, `.gif`, `.svg`, `.ico`, `.woff`, `.woff2`, `.ttf`, `.eot`, `.pdf`, `.mp4`, `.webm`, `.mp3`, `.ogg`, `.wav`, `.xml`, `.txt`, `.md`, `.yaml`, `.yml`
