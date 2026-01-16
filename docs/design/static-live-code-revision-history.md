# Static & Live Code Revision History

> **Status:** Design Draft
> **Created:** 2026-01-15
> **Related:** `live-static-state-design-implementation.md`, `datalevin-options.md`

---

## Vision

Track code changes across three dimensions:
1. **Static (Disk)** - File-based source as saved on disk
2. **Git History** - Committed revisions with diffs
3. **Live (Runtime)** - Dynamic changes via REPL eval

Enable a unified view where developers can see:
- What code is on disk vs. what's in the running runtime
- How code evolved over time (git commits)
- Which forms were eval'd ad-hoc and when
- Provenance: "Where did this definition come from?"

---

## Architecture Layers

### Layer 1: Static Analysis (Current - Phase 1.5)

What we have today:
- clj-kondo analysis of files on disk
- LSP for symbol navigation
- File watcher for change detection
- Namespace → symbols → source mapping

**Limitation:** Only sees what's saved to disk.

### Layer 2: Git Integration (Planned)

Add git-aware features:
- Show modified/staged/untracked status per file (Phase 1.5E.2 ✓)
- Show diff between working copy and HEAD
- Show diff between HEAD and any commit
- Navigate commit history for a namespace/symbol
- "When was this function last changed?"

**Implementation approach:**
- Shell out to `git diff`, `git log`, `git show`
- Parse unified diff format
- Overlay diff info on symbol display

### Layer 3: Datalog Code Storage (Future)

Store source code in a datalog database (Datalevin):

```clojure
;; Schema concepts
{:form/id        uuid
 :form/ns        string     ; namespace name
 :form/name      string     ; symbol name (nil for side-effects)
 :form/type      keyword    ; :defn, :def, :defmacro, etc.
 :form/source    string     ; the source code text
 :form/hash      string     ; content hash for dedup
 :form/line      int        ; original line in file
 :form/file      string     ; source file path
 :form/timestamp instant    ; when stored
 :form/origin    keyword}   ; :file, :repl, :eval
```

**Benefits:**
- Query across all code: "Find all functions calling `foo`"
- Track form-level history, not just file-level
- Eval a namespace = sequence of form evals from DB
- Store REPL explorations alongside file code

### Layer 4: Runtime Change Tracking (Future)

Intercept eval to track live changes:

```clojure
;; Conceptual - intercept all evals
(defn tracked-eval [form]
  (let [result (eval form)
        ns-name (str *ns*)
        form-name (when (and (seq? form)
                            (symbol? (second form)))
                    (str (second form)))]
    ;; Record to datalog
    (store-form! {:form/ns ns-name
                  :form/name form-name
                  :form/source (pr-str form)
                  :form/origin :repl
                  :form/timestamp (java.time.Instant/now)})
    result))
```

**Track:**
- Every REPL eval with timestamp
- Which ns was current
- Whether it redefined something
- Delta from disk version

---

## UI/UX Concepts

### Symbol Timeline View

For any symbol, show its history:

```
my-fn timeline:
├─ [disk] v3 - current on disk (modified)
├─ [repl] v3.1 - eval'd 2 mins ago (adds logging)
├─ [git] v3 - HEAD (commit abc123, yesterday)
├─ [git] v2 - commit def456 (last week)
└─ [git] v1 - initial commit
```

### Diff View

Side-by-side or unified diff showing:
- Disk vs Runtime (what you'd lose on restart)
- Disk vs Git HEAD (uncommitted changes)
- Any two versions

### Provenance Indicator

In symbol list, show origin:
- 📁 From file (matches disk)
- 🔄 Modified in REPL (differs from disk)
- ⚠️ Stale (file changed since last load)

### Multi-File Namespace View

When a namespace spans multiple files:
- Show all contributing files
- Group symbols by source file
- Indicate load order

---

## Implementation Phases

### Phase A: Git Diff Integration
- Add "Show diff" button to source panel
- Compare current file to HEAD
- Compare current file to any commit
- Show commit history for file

### Phase B: Form-Level Tracking
- Parse files into individual forms
- Store forms in Datalevin
- Query forms across namespaces
- Track form-level history

### Phase C: REPL Integration
- Intercept nREPL eval responses
- Store eval'd forms with origin=:repl
- Show runtime vs disk diff
- "Sync to disk" action

### Phase D: Unified Timeline
- Merge git + file + repl history
- Navigate any point in time
- "Restore to version X"
- Export REPL session to file

---

## Key Insights

1. **clojure.core vars are special** - They're implicitly referred, not explicitly. Use `(keys (ns-publics 'clojure.core))` to get the list dynamically - never hardcode.

2. **`:refer-clojure :exclude` not in kondo** - clj-kondo's analysis output doesn't expose which core vars were excluded. This is a limitation for shadow detection.

3. **Multi-file namespaces are common in REPL** - When you `(in-ns 'foo)` and define things, you're adding to a namespace from a different "file" (the REPL). This is the same concept as multiple files contributing to one ns.

4. **Forms are the unit of change** - Not files, not lines. A single `defn` is the atomic unit that gets eval'd, versioned, and tracked.

---

## Open Questions

1. **Storage location** - Where should the datalog DB live? Per-project? Global?

2. **Garbage collection** - How long to keep REPL history? All forever? Time-based?

3. **Collaboration** - If multiple developers are REPL-ing, how to merge histories?

4. **Performance** - Storing every eval could get large. Compress? Dedupe by content hash?

5. **Recovery** - Can we reconstruct lost REPL work from the DB?

---

## Related Work

- **clj-ns-browser** - Inspiration for live introspection
- **CIDER** - Emacs REPL tracking
- **Cursive** - IntelliJ code navigation
- **Datomic** - Time-travel database concepts
- **Git** - Version control model

---

## Notes

*This document captures the vision discussed on 2026-01-15. Implementation will be incremental, starting with git diff integration in the code browser.*
