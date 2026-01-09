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

(defn- create-extensions
  "Create CM6 extensions based on options."
  [{:keys [language read-only on-change]}]
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

(defn editor
  "Reagent component for CodeMirror 6 editor.

   Props:
   - :id         - Unique editor ID (auto-generated if not provided)
   - :value      - Editor content (string)
   - :language   - Language mode (:clojure supported)
   - :read-only  - Make editor read-only (default false)
   - :on-change  - Callback fn called with new value on changes
   - :class      - Additional CSS class for container
   - :style      - Inline styles for container"
  [{:keys [id value language read-only on-change _class _style]}]
  (let [editor-id (or id (str "cm6-" (random-uuid)))
        !view (atom nil)           ; mutable ref for EditorView
        !container (atom nil)      ; mutable ref for DOM container
        !last-value (atom nil)]    ; track last value to detect changes
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
                                                :on-change on-change})]
                      (reset! !view view)
                      (reset! !last-value value)
                      (swap! !editors assoc editor-id view))
                    (js/console.warn "[scittle-cm6] CM6 not ready, cannot mount editor"))))

      :component-will-unmount
      (fn [_this]
        (when-let [view @!view]
                  (destroy-editor! view)
                  (swap! !editors dissoc editor-id)))

      :reagent-render
      (fn [{:keys [value class style] :as _props}]
        ;; Update editor when value prop changes
        (when (and @!view (not= @!last-value value))
          (reset! !last-value value)
          (set-value! @!view value))
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
