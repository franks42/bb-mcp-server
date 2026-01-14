(ns scittle-cm6
    "Reusable Reagent wrapper for CodeMirror 6.

   Designed to run in Scittle browser environment.
   CM6 modules loaded via ES modules (esm.sh) in bootstrap HTML.

   Usage:
     (require '[scittle-cm6 :as cm6])

     ;; Read-only editor
     [cm6/editor {:value \"(+ 1 2)\"
                  :language :clojure
                  :read-only true}]

     ;; Editable with onChange
     [cm6/editor {:value @!code
                  :on-change #(reset! !code %)
                  :language :clojure}]"
    (:require [reagent.core :as r]))

;; =============================================================================
;; State
;; =============================================================================

;; Registry of active EditorView instances. {id -> EditorView}
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !editors (atom {}))

;; =============================================================================
;; CM6 Access
;; =============================================================================

(defn cm6-ready?
  "Check if CodeMirror 6 modules are loaded."
  []
  js/window.CM6_READY)

(defn- get-cm
  "Get CM6 module from global. Returns nil if not loaded."
  []
  (when (cm6-ready?)
    js/globalThis.CM))

;; =============================================================================
;; Editor Creation
;; =============================================================================

(defn- create-highlight-extension
  "Create a CM6 extension for line range highlighting.
   Uses StateField to track highlighted lines and provide decorations.
   Supports single line (highlight-line only) or range (highlight-line to highlight-end-line)."
  [highlight-line highlight-end-line]
  (let [cm (get-cm)]
    (when (and cm highlight-line (pos? highlight-line))
      (let [;; Get CM6 modules
            Decoration (.-Decoration cm)
            StateField (.-StateField cm)
            EditorView (.-EditorView cm)
            ;; Determine actual end line (default to start if no end specified)
            end-line (or highlight-end-line highlight-line)
            ;; Create line decoration type with CSS class
            line-deco (.line Decoration #js {:class "cm-highlighted-line"})
            ;; StateField that provides decorations for all lines in range
            highlight-field
            (.define StateField
                     #js {:create
                          (fn [state]
                            ;; Create decorations for all lines in range
                            (let [doc (.-doc state)
                                  line-count (.-lines doc)
                                  ;; Clamp to valid line range
                                  actual-start (max 1 (min highlight-line line-count))
                                  actual-end (max actual-start (min end-line line-count))]
                              (if (<= actual-start line-count)
                                ;; Build array of decorations for each line in range
                                (let [decorations #js []]
                                  (loop [line-num actual-start]
                                        (when (<= line-num actual-end)
                                          (let [line-obj (.line doc line-num)
                                                from (.-from line-obj)]
                                            (.push decorations (.range line-deco from)))
                                          (recur (inc line-num))))
                                  (.set Decoration decorations))
                                (.-none Decoration))))
                          :update
                          (fn [decorations _tr]
                            ;; Keep decorations static (they don't change with edits)
                            decorations)
                          :provide
                          (fn [field]
                            (.from (.-decorations EditorView) field))})]
        highlight-field))))

(defn- create-extensions
  "Create CM6 extensions based on options."
  [{:keys [language read-only on-change highlight-line highlight-end-line]}]
  (let [cm (get-cm)]
    (when cm
      (let [basic-setup (.-basicSetup cm)
            extensions #js [basic-setup]]
        ;; Add Clojure language if available
        (when (and (= language :clojure) (.-clojure cm))
          (.push extensions ((.-clojure cm))))
        ;; Add read-only if specified
        (when read-only
          (.push extensions (.of (.. cm -EditorState -readOnly) true)))
        ;; Add update listener for on-change
        (when on-change
          (.push extensions
                 (.of (.. cm -EditorView -updateListener)
                      (fn [update]
                        (when (.-docChanged update)
                          (on-change (.toString (.-doc (.-state update)))))))))
        ;; Add line highlighting extension (Phase 1.5E.12)
        (when-let [highlight-ext (create-highlight-extension highlight-line highlight-end-line)]
                  (.push extensions highlight-ext))
        extensions))))

(defn- create-editor!
  "Create a CM6 EditorView and mount it to container."
  [container opts]
  (let [cm (get-cm)]
    (when cm
      (let [state (.create (.-EditorState cm)
                           #js {:doc (or (:value opts) "")
                                :extensions (create-extensions opts)})
            view (new (.-EditorView cm)
                      #js {:state state
                           :parent container})]
        view))))

(defn- destroy-editor!
  "Destroy a CM6 EditorView."
  [view]
  (when view
    (.destroy view)))

(defn- set-value!
  "Update editor content without losing cursor position."
  [view value]
  (when (and view value)
    (let [current-doc (.toString (.-doc (.-state view)))]
      (when (not= current-doc value)
        (.dispatch view
                   #js {:changes #js {:from 0
                                      :to (count current-doc)
                                      :insert value}})))))

;; =============================================================================
;; Reagent Component
;; =============================================================================

(defn- scroll-to-line!
  "Scroll editor to center the given line (1-based) in view."
  [view line-num]
  (when (and view line-num (pos? line-num))
    (let [cm (get-cm)
          doc (.-doc (.-state view))
          line-count (.-lines doc)]
      (when (<= line-num line-count)
        (let [line-obj (.line doc line-num)
              pos (.-from line-obj)
              ;; EditorView.scrollIntoView is a function, not a method with .of
              scroll-effect ((.-scrollIntoView (.-EditorView cm)) pos #js {:y "center"})]
          ;; Dispatch scroll effect to center the line
          (.dispatch view #js {:effects scroll-effect}))))))

(defn editor
  "Reagent component for CodeMirror 6 editor.

   Props:
   - :id                 - Unique editor ID (auto-generated if not provided)
   - :value              - Editor content (string)
   - :language           - Language mode (:clojure supported)
   - :read-only          - Make editor read-only (default false)
   - :on-change          - Callback fn called with new value on changes
   - :highlight-line     - Start line (1-based) to highlight (Phase 1.5E.12)
   - :highlight-end-line - End line for multi-line highlight (optional)
   - :class              - Additional CSS class for container
   - :style              - Inline styles for container"
  [{:keys [id value language read-only on-change highlight-line highlight-end-line
           _class _style]}]
  (let [editor-id (or id (str "cm6-" (random-uuid)))
        !view (atom nil)              ; mutable ref for EditorView
        !container (atom nil)         ; mutable ref for DOM container
        !last-value (atom nil)        ; track last value to detect changes
        !last-highlight (atom nil)]   ; track last highlight range to detect changes
    (r/create-class
     {:display-name "cm6-editor"

      :component-did-mount
      (fn [_this]
        (when-let [container @!container]
                  (if (cm6-ready?)
                    (let [view (create-editor! container
                                               {:value value
                                                :language language
                                                :read-only read-only
                                                :on-change on-change
                                                :highlight-line highlight-line
                                                :highlight-end-line highlight-end-line})]
                      (reset! !view view)
                      (reset! !last-value value)
                      (reset! !last-highlight [highlight-line highlight-end-line])
                      (swap! !editors assoc editor-id view)
                      ;; Scroll to highlighted line after mount
                      (when highlight-line
                        ;; Small delay to ensure editor is fully rendered
                        (js/setTimeout #(scroll-to-line! view highlight-line) 50)))
                    (js/console.warn "[scittle-cm6] CM6 not ready, cannot mount editor"))))

      :component-will-unmount
      (fn [_this]
        (when-let [view @!view]
                  (destroy-editor! view)
                  (swap! !editors dissoc editor-id)))

      :reagent-render
      (fn [{:keys [value highlight-line highlight-end-line class style] :as _props}]
        ;; Update editor when value prop changes
        (when (and @!view (not= @!last-value value))
          (reset! !last-value value)
          (set-value! @!view value))
        ;; Recreate editor if highlight range changes (decorations are set at create time)
        (when (and @!view (not= @!last-highlight [highlight-line highlight-end-line]))
          (reset! !last-highlight [highlight-line highlight-end-line])
          ;; Need to recreate editor with new highlight extension
          (when-let [container @!container]
                    (destroy-editor! @!view)
                    (let [view (create-editor! container
                                               {:value (or @!last-value value)
                                                :language language
                                                :read-only read-only
                                                :on-change on-change
                                                :highlight-line highlight-line
                                                :highlight-end-line highlight-end-line})]
                      (reset! !view view)
                      (swap! !editors assoc editor-id view)
                      ;; Scroll to new highlighted line
                      (when highlight-line
                        (js/setTimeout #(scroll-to-line! view highlight-line) 50)))))
        [:div.cm-container
         {:ref #(reset! !container %)
          :class class
          :style (merge {:height "100%"} style)}])})))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn get-editor
  "Get EditorView instance by id."
  [id]
  (get @!editors id))

(defn get-value
  "Get current value from editor by id."
  [id]
  (when-let [view (get-editor id)]
            (.toString (.-doc (.-state view)))))

(defn focus!
  "Focus editor by id."
  [id]
  (when-let [view (get-editor id)]
            (.focus view)))
