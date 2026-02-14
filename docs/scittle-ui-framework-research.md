# Scittle Browser UI Framework Research

**Context**: Building browser-based ClojureScript/Scittle/SCI applications requiring resizable/movable window panels and code editor integration.

**Date**: February 2026

---

## Executive Summary

For Scittle-based browser applications needing floating windows and code editing, the recommended stack is:

- **Window Manager**: WinBox.js (stable, lightweight, CDN-ready)
- **Code Editor**: CodeMirror 5 (CDN-friendly, global JS access)
- **UI Framework**: Reagent (via Scittle plugin)

This combination requires no build tools and works entirely via CDN script tags with direct JS interop from ClojureScript.

---

## Window Manager Evaluation

### Maintenance Reality (as of Feb 2026)

| Library | Last Release | Stars | Status |
|---------|--------------|-------|--------|
| WinBox.js | May 2022 (v0.2.82) | 7k+ | ⚠️ Stable but stagnant |
| jsPanel4 | 2021 | 1.5k | ⚠️ Stable but stagnant |
| Golden Layout | Sporadic | 6k+ | ⚠️ Legacy feel |
| JSFrame.js | 2020 | ~500 | ❌ Abandoned |

**Key insight**: The floating window manager niche is small because modern web apps shifted to panels/sidebars (VS Code style) and modals. Most "actively maintained" drag libraries (Moveable, dnd-kit, pragmatic-drag-and-drop) focus on drag-drop sorting, not windowing.

### Actively Maintained Alternatives (Not Window Managers)

- **Moveable** (Daybrush): Drag/resize/rotate for design tools - https://github.com/daybrush/moveable
- **Gridstack.js**: Dashboard grid layouts - https://gridstackjs.com/
- **Pragmatic Drag and Drop** (Atlassian): Low-level primitives - https://github.com/atlassian/pragmatic-drag-and-drop
- **interact.js**: Drag/resize primitives - https://interactjs.io/

### Recommendation: WinBox.js

Despite being "stagnant," WinBox is effectively "done software" - it works well and is unlikely to break. For Scittle use:

**Pros**:
- Zero dependencies, ~12KB gzipped
- Full-featured: drag, resize, maximize, minimize, fullscreen, theming, modal mode
- Simple API via `new WinBox(options)`
- Direct JS interop works perfectly from Scittle

**Cons**:
- No new features expected
- Single maintainer (personal project)

**Resources**:
- GitHub: https://github.com/nextapps-de/winbox
- Demo: https://nextapps-de.github.io/winbox/
- CDN: `https://cdn.jsdelivr.net/npm/winbox@0.2.82/dist/winbox.bundle.min.js`

---

## Code Editor Evaluation

### CodeMirror 5 vs 6

| Aspect | CodeMirror 5 | CodeMirror 6 |
|--------|--------------|--------------|
| Scittle compatibility | ✅ Excellent (global `js/CodeMirror`) | ⚠️ Requires ES modules |
| CDN usage | ✅ Script tags | ⚠️ Import maps needed |
| Clojure mode | Basic syntax highlighting | Better via @nextjournal/clojure-mode |
| Maintenance | Legacy, security fixes only | Active development |

### Recommendation: CodeMirror 5

For pure Scittle (no build tools), CodeMirror 5 is the practical choice due to its CDN-friendly architecture.

**CDN Links**:
```html
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.css">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/theme/dracula.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/mode/clojure/clojure.min.js"></script>
```

**Optional - Parinfer for structural editing**:
```html
<script src="https://cdn.jsdelivr.net/npm/parinfer@3.13.1/parinfer.min.js"></script>
```

---

## Working Reference Implementation

**ape-cljs-playground** is the closest existing example:
- GitHub: https://github.com/jurjanpaul/ape-cljs-playground
- Live demo: https://jurjanpaul.github.io/ape-cljs-playground/
- Stack: Scittle + Reagent + CodeMirror 5 + Parinfer
- Features: Single HTML file, localStorage persistence
- Limitation: No floating windows (uses single editor panel)

---

## Recommended Stack: CDN Links

```html
<!DOCTYPE html>
<html>
<head>
  <!-- Scittle + Reagent -->
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.reagent.js"></script>
  
  <!-- WinBox -->
  <script src="https://cdn.jsdelivr.net/npm/winbox@0.2.82/dist/winbox.bundle.min.js"></script>
  
  <!-- CodeMirror 5 -->
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.css">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/theme/dracula.min.css">
  <script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.js"></script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/mode/clojure/clojure.min.js"></script>
  
  <!-- Optional: Parinfer -->
  <script src="https://cdn.jsdelivr.net/npm/parinfer@3.13.1/parinfer.min.js"></script>
</head>
<body>
  <div id="app"></div>
  <script type="application/x-scittle">
    ;; ClojureScript code here
  </script>
</body>
</html>
```

---

## Integration Pattern: Scittle + WinBox + CodeMirror

### Core Wrapper Functions

```clojure
(ns app.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

;; State management
(defonce app-state (r/atom {:windows {}
                            :editors {}}))

;; CodeMirror wrapper
(defn create-codemirror! 
  "Create a CodeMirror instance on a textarea element"
  [element opts]
  (js/CodeMirror.fromTextArea 
    element 
    (clj->js (merge {:mode "clojure"
                     :theme "dracula"
                     :lineNumbers true
                     :matchBrackets true}
                    opts))))

;; WinBox wrapper
(defn create-window! 
  "Create a WinBox window with options"
  [opts]
  (js/WinBox. (clj->js opts)))

;; Combined: Editor in a window
(defn open-editor-window! 
  "Open a new window containing a CodeMirror editor"
  [{:keys [title code width height x y]
    :or {title "Editor"
         code "(println \"Hello!\")"
         width "500px"
         height "400px"
         x "center"
         y "center"}}]
  (let [win-id (str (gensym "win-"))
        textarea-id (str (gensym "editor-"))
        editor-atom (atom nil)]
    
    (create-window!
      {:title title
       :width width
       :height height
       :x x
       :y y
       :class ["no-full"]  ; Optional: disable fullscreen button
       :html (str "<textarea id='" textarea-id "' style='width:100%;height:100%;'>" 
                  code 
                  "</textarea>")
       
       ;; Refresh CodeMirror on resize (critical!)
       :onresize (fn [w h]
                   (when-let [cm @editor-atom]
                     (.refresh cm)))
       
       ;; Cleanup on close
       :onclose (fn []
                  (swap! app-state update :windows dissoc win-id)
                  (swap! app-state update :editors dissoc win-id)
                  false)}) ; Return false to allow close
    
    ;; Initialize CodeMirror after DOM is ready
    (js/setTimeout
      (fn []
        (when-let [textarea (.getElementById js/document textarea-id)]
          (let [cm (create-codemirror! textarea {})]
            (reset! editor-atom cm)
            (swap! app-state assoc-in [:editors win-id] cm))))
      50)))
```

### Key Integration Points

1. **CodeMirror access**: `js/CodeMirror` global after script load
2. **WinBox access**: `js/WinBox` constructor
3. **Resize handling**: Must call `.refresh` on CodeMirror when window resizes
4. **DOM timing**: Use `js/setTimeout` to ensure DOM ready before initializing CodeMirror
5. **CLJS→JS conversion**: Use `clj->js` for all option maps

### Getting Editor Content

```clojure
(defn get-editor-content [win-id]
  (when-let [cm (get-in @app-state [:editors win-id])]
    (.getValue cm)))

(defn set-editor-content [win-id content]
  (when-let [cm (get-in @app-state [:editors win-id])]
    (.setValue cm content)))

(defn eval-editor-content! [win-id]
  (let [code (get-editor-content win-id)]
    (try
      (js/scittle.core.eval_string code)
      (catch js/Error e
        (js/console.error "Eval error:" e)))))
```

---

## WinBox API Quick Reference

### Constructor Options

```clojure
(js/WinBox. 
  (clj->js
    {:title "Window Title"
     :width "500px"      ; or number (pixels)
     :height "400px"
     :x "center"         ; or number, "left", "right"
     :y "center"         ; or number, "top", "bottom"
     :top 50             ; viewport boundaries
     :right 50
     :bottom 0
     :left 50
     :min false          ; start minimized
     :max false          ; start maximized
     :hidden false       ; start hidden
     :modal false        ; modal mode
     :background "#1e1e1e"
     :border 4
     :class ["no-full" "no-max" "my-theme"]
     
     ;; Content (choose one)
     :html "<div>Content</div>"
     :url "https://example.com"
     :mount (js/document.getElementById "existing-element")
     
     ;; Callbacks
     :oncreate (fn [opts] ...)
     :onshow (fn [] ...)
     :onhide (fn [] ...)
     :onfocus (fn [] ...)
     :onblur (fn [] ...)
     :onresize (fn [width height] ...)
     :onmove (fn [x y] ...)
     :onclose (fn [force] ...) ; return false to prevent close
     }))
```

### Instance Methods

```clojure
(let [win (js/WinBox. (clj->js {...}))]
  (.minimize win)           ; or (.minimize win false) to restore
  (.maximize win)
  (.fullscreen win)
  (.close win)
  (.close win true)         ; force close (bypass onclose)
  (.hide win)
  (.show win)
  (.focus win)
  (.blur win)
  (.move win x y)
  (.resize win w h)
  (.setTitle win "New Title")
  (.setBackground win "#ff0000")
  (.setUrl win "https://...")
  (.mount win element)
  (.addClass win "classname")
  (.removeClass win "classname")
  (.toggleClass win "classname"))
```

---

## CodeMirror 5 API Quick Reference

### Creation

```clojure
;; From textarea
(def cm (js/CodeMirror.fromTextArea textarea-element (clj->js opts)))

;; From div (replaces content)
(def cm (js/CodeMirror div-element (clj->js opts)))
```

### Common Options

```clojure
{:mode "clojure"
 :theme "dracula"           ; requires theme CSS
 :lineNumbers true
 :matchBrackets true
 :autoCloseBrackets true    ; requires addon
 :indentWithTabs false
 :indentUnit 2
 :tabSize 2
 :lineWrapping false
 :readOnly false            ; or "nocursor"
 :autofocus true
 :value "initial content"}
```

### Instance Methods

```clojure
(.getValue cm)                    ; get all content
(.setValue cm "new content")      ; replace all content
(.getSelection cm)                ; get selected text
(.replaceSelection cm "text")     ; replace selection
(.getCursor cm)                   ; {:line n :ch n}
(.setCursor cm line ch)
(.refresh cm)                     ; IMPORTANT: call after container resize
(.focus cm)
(.setOption cm "readOnly" true)
(.on cm "change" (fn [cm change] ...))
(.on cm "keydown" (fn [cm event] ...))
```

---

## Alternative Approaches Considered

### If WinBox becomes problematic:

1. **Build with Moveable**: Use Moveable for drag/resize, style your own window divs
2. **CSS-native resize**: `resize: both` CSS property + custom drag JS (~50 lines)
3. **Different paradigm**: Split panes (VS Code style) using `split.js`
4. **Gridstack.js**: For dashboard-style grid layouts

### If CodeMirror 5 is insufficient:

1. **CodeMirror 6 + bundler**: Use shadow-cljs, get @nextjournal/clojure-mode
2. **Monaco Editor**: VS Code's editor, heavier but more features
3. **Ace Editor**: Another mature option with CDN support

---

## Additional Resources

### Scittle
- Main repo: https://github.com/babashka/scittle
- JS Libraries guide: https://github.com/babashka/scittle/blob/main/doc/js-libraries.md
- Scittlets catalog: https://github.com/ikappaki/scittlets

### Related Projects
- SCI (interpreter): https://github.com/babashka/sci
- Babashka: https://babashka.org
- Reagent: https://reagent-project.github.io/

### Community Examples
- Clojure Civitas presentations: https://clojurecivitas.github.io/scittle/
- Borkdude's blog: https://blog.michielborkent.nl/

---

## Known Limitations

1. **No ClojureScript wrappers exist** for WinBox or similar - direct JS interop required
2. **CodeMirror 5 Clojure mode** is basic - no structural editing without Parinfer
3. **WinBox not maintained** - works but won't get new features
4. **Mobile support** varies - WinBox works but floating windows are awkward on touch

---

## Next Steps

1. Create minimal HTML prototype with WinBox + CodeMirror 5 + Scittle
2. Implement REPL evaluation (eval button or keyboard shortcut)
3. Add window management (save/restore layout to localStorage)
4. Consider Parinfer integration for better Clojure editing
5. Style customization (themes, colors)
