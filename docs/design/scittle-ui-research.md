# Scittle UI Components Research

**Research Date:** 2025-12-31
**Purpose:** UI components for bb-scittle-code-browser

---

## Summary

| Component | Recommended Approach | Bundle Size | Notes |
|-----------|---------------------|-------------|-------|
| Source viewer | CodeMirror 6 + clojure-mode | ~200kb gzipped | Best Clojure highlighting, read-only mode available |
| Namespace list | Reagent + native HTML | minimal | Multi-select with checkboxes |
| Vars list | Reagent + native HTML | minimal | Single-select with click |
| Filter inputs | Reagent + native `<input>` | minimal | Wildcard/regex filtering |

---

## CodeMirror 6 Integration

### Option 1: Scittlets `reagent/codemirror` (Recommended)

[ikappaki/scittlets](https://github.com/ikappaki/scittlets) provides ready-made Scittle components:

```bash
# Create new project with CodeMirror template
npx scittlets new reagent/codemirror

# Or add to existing HTML
npx scittlets add index.html reagent/codemirror
```

**Benefits:**
- Pre-packaged for Scittle
- CDN-ready dependencies
- Reagent integration included

### Option 2: Nextjournal clojure-mode (Manual)

[nextjournal/clojure-mode](https://github.com/nextjournal/clojure-mode) - Full CodeMirror 6 Clojure support.

**Installation:**
```html
<!-- Load via ES modules -->
<script type="module">
  import { default_extensions } from '@nextjournal/clojure-mode';
  import { EditorView } from '@codemirror/view';
  import { EditorState } from '@codemirror/state';
  globalThis.CodeMirror = { EditorView, EditorState, clojure: { default_extensions } };
  scittle.core.eval_script_tags();
</script>
```

**Usage in Scittle:**
```clojure
(require ["CodeMirror" :as cm])

(defn source-viewer [code]
  (let [state (cm/EditorState.create
                #js {:doc code
                     :extensions (cm/clojure/default_extensions)})]
    (cm/EditorView. #js {:state state
                         :parent (js/document.getElementById "source")})))
```

**Read-only mode (for viewer):**
```javascript
EditorView.editable.of(false)  // Add to extensions
```

### Option 3: highlight.js (Simpler, Lighter)

For read-only display without editing features:

```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/clojure.min.js"></script>
```

```clojure
(defn highlight-code [code]
  [:pre [:code.language-clojure {:ref #(when % (js/hljs.highlightElement %))}
         code]])
```

**Bundle size:** ~40kb vs ~200kb for CodeMirror
**Trade-off:** Less accurate Clojure highlighting than clojure-mode

### Comparison

| Feature | CodeMirror 6 | highlight.js |
|---------|-------------|--------------|
| Clojure accuracy | Excellent (Lezer parser) | Good |
| Bundle size | ~200kb gzipped | ~40kb |
| Line numbers | Built-in | Manual |
| Selection | Built-in | None |
| Future editing | Easy to enable | Not possible |

**Recommendation:** Start with CodeMirror 6 via scittlets - the bundle size difference is acceptable for the superior highlighting and future-proofing.

---

## List Components (Namespace/Vars)

### Reagent Multi-Select List

Simple implementation with native HTML + Reagent atoms:

```clojure
(ns code-browser.panels
  (:require [reagent.core :as r]))

(defn filterable-list
  "Multi-select list with filter input."
  [{:keys [items selected-atom filter-atom on-select]}]
  (let [filtered (filter #(matches-filter? @filter-atom %) items)]
    [:div.list-panel
     ;; Filter input
     [:input.filter {:type "text"
                     :placeholder "Filter..."
                     :value @filter-atom
                     :on-change #(reset! filter-atom (-> % .-target .-value))}]
     ;; List
     [:div.list-items
      (for [item filtered]
        ^{:key item}
        [:div.list-item
         {:class (when (contains? @selected-atom item) "selected")
          :on-click #(on-select item)}
         item])]]))

(defn namespace-list []
  (let [!namespaces (get-synced-atom :namespaces)
        !selected (r/atom #{})
        !filter (r/atom "")]
    [filterable-list
     {:items @!namespaces
      :selected-atom !selected
      :filter-atom !filter
      :on-select (fn [ns]
                   (swap! !selected
                          (fn [s] (if (contains? s ns)
                                    (disj s ns)
                                    (conj s ns)))))}]))
```

### Alternative: re-com Components

[re-com](https://github.com/day8/re-com) provides Bootstrap-styled components:

```clojure
(require '[re-com.multi-select :refer [multi-select]])

[multi-select
 :choices [{:id "app.core" :label "app.core"}
           {:id "app.db" :label "app.db"}]
 :model selected-ns-set
 :on-change #(reset! selected-ns %)]
```

**Trade-off:** re-com adds significant bundle size (~500kb). For simple lists, native HTML is lighter.

---

## Filter Implementation

### Wildcard Matching

```clojure
(defn wildcard->regex [pattern]
  (-> pattern
      (clojure.string/replace "." "\\.")
      (clojure.string/replace "*" ".*")
      (re-pattern)))

(defn matches-wildcard? [pattern text]
  (if (empty? pattern)
    true
    (re-find (wildcard->regex pattern) text)))

;; Usage
(matches-wildcard? "app.*" "app.core")  ;; => true
(matches-wildcard? "*db*" "app.db.query")  ;; => true
```

### Regex Matching (Optional Mode)

```clojure
(defn matches-regex? [pattern text]
  (try
    (boolean (re-find (re-pattern pattern) text))
    (catch :default _ false)))  ;; Invalid regex returns false
```

### Combined Filter Component

```clojure
(defn filter-input [{:keys [value on-change mode]}]
  [:div.filter-container
   [:input {:type "text"
            :value value
            :placeholder (if (= mode :regex) "Regex..." "Filter (* = wildcard)")
            :on-change #(on-change (-> % .-target .-value))}]
   [:select {:value (name mode)
             :on-change #(reset! !filter-mode (keyword (-> % .-target .-value)))}
    [:option {:value "wildcard"} "Wildcard"]
    [:option {:value "regex"} "Regex"]]])
```

---

## JavaScript Library Loading in Scittle

Since Scittle v0.7.30, external JS libraries can be loaded and required:

### Method 1: Global Libraries (Synchronous)

```html
<script src="library.js"></script>
<script type="application/x-scittle">
  (require ["LibraryName" :as lib])
  (lib/doSomething)
</script>
```

### Method 2: ES Modules (Asynchronous)

```html
<script src="scittle.js"></script>
<script>scittle.core.disable_auto_eval()</script>
<script type="module">
  import { EditorView } from "@codemirror/view";
  globalThis.EditorView = EditorView;
  scittle.core.eval_script_tags();
</script>
<script type="application/x-scittle">
  (require ["EditorView" :as ev])
  ;; Use ev here
</script>
```

---

## Recommended Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                       HTML + Scittle                                │
├─────────────────────────────────────────────────────────────────────┤
│  CDN Scripts:                                                       │
│  - scittle.js + scittle.reagent.js                                 │
│  - CodeMirror 6 (via ES module or scittlets)                       │
│  - @nextjournal/clojure-mode                                       │
├─────────────────────────────────────────────────────────────────────┤
│  Scittle Code (code_browser/*.cljs):                               │
│  - core.cljs: Main layout, atom sync                               │
│  - panels.cljs: namespace-list, vars-list, source-viewer           │
│  - filters.cljs: wildcard/regex matching                           │
│  - codemirror.cljs: CM6 wrapper component                          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Sources

- [nextjournal/clojure-mode](https://github.com/nextjournal/clojure-mode) - CodeMirror 6 Clojure support
- [ikappaki/scittlets](https://github.com/ikappaki/scittlets) - Ready-made Scittle components
- [Scittle JS Libraries Guide](https://github.com/babashka/scittle/blob/main/doc/js-libraries.md)
- [Better Clojure Highlighting](https://blog.michielborkent.nl/better-clojure-highlighting.html) - CM6 highlighting-only setup
- [Interactive Code Snippets](https://blog.jakubholy.net/2023/interactive-code-snippets-fulcro/) - SCI + CM6 integration
- [re-com Multi-Select](https://cljdoc.org/d/re-com/re-com/2.17.1/api/re-com.multi-select)
- [Reagent Forms](https://github.com/reagent-project/reagent-forms)
- [Maria.cloud](https://www.clojuriststogether.org/news/september-2022-monthly-update/) - Scittle + CM6 + ProseMirror

---

## Next Steps

1. **Try scittlets:** `npx scittlets new reagent/codemirror` to get a working CM6 + Scittle starter
2. **Implement list components:** Native Reagent with CSS styling
3. **Add filter logic:** Wildcard matching first, regex as option
4. **Wire to atom sync:** Connect UI to bidirectional synced atoms

---

*Last Updated: 2025-12-31*
