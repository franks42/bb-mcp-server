# Code Retrieval Tool Design (Datalevin-Powered)

**Date:** November 26, 2025
**Status:** Draft
**Context:** Designing a "Smart Codebase Expert" tool that uses Datalevin to store and retrieve Clojure code as semantic units (forms) rather than raw text.

## 1. The Concept

Standard RAG (Retrieval Augmented Generation) splits text files into arbitrary chunks. This is suboptimal for code, where context (namespace, function boundaries, call graphs) is critical.

This design proposes a **Structure-Aware Code Store** using Datalevin.
*   **Ingestion:** Parse Clojure files into top-level forms.
*   **Storage:** Store each form as an entity with metadata (docstring, arity, calls).
*   **Retrieval:** Expose MCP tools that allow an LLM to query this graph.

## 2. Datalevin Schema

We treat every top-level form (function, def, macro) as a distinct entity.

```clojure
(def schema
  {:code/id          {:db/valueType :db.type/keyword  :db/unique :db.unique/identity} ; e.g. :my.ns/my-func
   :code/ns          {:db/valueType :db.type/keyword  :db/index true}                 ; e.g. :my.ns
   :code/name        {:db/valueType :db.type/symbol   :db/index true}                 ; e.g. my-func
   :code/type        {:db/valueType :db.type/keyword}                                 ; :defn, :def, :defmacro
   :code/source      {:db/valueType :db.type/string}                                  ; Full source code
   :code/doc         {:db/valueType :db.type/string   :db/fulltext true}              ; Docstring
   :code/file        {:db/valueType :db.type/string}                                  ; Relative path
   :code/line        {:db/valueType :db.type/long}                                    ; Start line
   :code/calls       {:db/valueType :db.type/keyword  :db/cardinality :db.cardinality/many} ; Refs to other :code/id
   :code/purity      {:db/valueType :db.type/keyword}                                 ; :pure, :impure, :unknown (heuristic)
   :code/hash        {:db/valueType :db.type/string}                                  ; Content hash for change detection
   })

## 3. MCP Tool Interface

These are the tools exposed to the LLM (e.g., Claude).

### Tool 1: `get_code_context`
Retrieves the source code and metadata for a specific symbol.

*   **Input:**
    ```json
    {
      "symbol": "my.ns/my-func"
    }
    ```
*   **Output:**
    ```json
    {
      "source": "(defn my-func [x] ...)",
      "doc": "Calculates X...",
      "file": "src/my/ns.clj",
      "line": 10
    }
    ```

### Tool 2: `find_usages`
Finds all forms that call a specific function (Reverse Lookup).

*   **Input:**
    ```json
    {
      "symbol": "my.ns/my-func"
    }
    ```
*   **Output:**
    ```json
    [
      {"id": "other.ns/caller-func", "file": "src/other/ns.clj", "line": 50},
      {"id": "test.ns/test-my-func", "file": "test/my/ns_test.clj", "line": 12}
    ]
    ```

### Tool 3: `explore_namespace`
Gives a high-level overview of a namespace to help the LLM orient itself.

*   **Input:**
    ```json
    {
      "ns": "my.ns"
    }
    ```
*   **Output:**
    ```json
    {
      "description": "Core logic for X...",
      "public_vars": [
        {"name": "my-func", "type": "defn", "doc": "Calculates X..."},
        {"name": "config", "type": "def", "doc": "Global config..."}
      ]
    }
    ```

### Tool 4: `semantic_search`
Finds code based on natural language description (using Datalevin fulltext search on docstrings).

*   **Input:**
    ```json
    {
      "query": "calculate total price"
    }
    ```
*   **Output:**
    ```json
    [
      {"id": "sales.core/calc-total", "doc": "Calculates total price including tax...", "score": 0.8}
    ]
    ```

## 4. Ingestion Strategy (The "Indexer")

We need a background process (or startup task) that indexes the codebase.

1.  **Scan:** Walk the `src` and `test` directories.
2.  **Parse:** Use **`edamame`** (built-in to Babashka) to parse each file.
    *   *Why `edamame`?* It is fast, compatible with `bb`, and provides precise location metadata (`:row`, `:col`, `:end-row`, `:end-col`).
    *   *Technique:* Read the file as a string. Parse with `{:all true :location? true}`. Use the location metadata to substring the original file content, preserving comments and formatting for `:code/source`.
3.  **Analyze:**
    *   Identify top-level forms (check if the first symbol is `defn`, `def`, `ns`, etc.).
    *   Extract docstrings (from the form data).
    *   Analyze function bodies to find calls to other known symbols (static analysis).
    *   **Purity Heuristic:** Check for known side-effecting symbols (`reset!`, `swap!`, `println`, `jdbc/execute!`, `slurp`, `spit`).
        *   If found -> `:impure`
        *   If only pure core functions found -> `:pure` (optimistic)
        *   **Telemetry Exception:** If the *only* side effects are logging/telemetry (`log/info`, `t/trace!`), we label it **`:pure-ish`** (or `:telemetry-safe`).
            *   *Reasoning:* These side effects are "observational" and do not change the business logic state.
        *   Else -> `:unknown`
4.  **Transact:** Update Datalevin. Use `:code/hash` to skip unchanged forms.

## 5. Example Workflow

**User:** "How does the `start-server` function work and what calls it?"

**LLM Action Plan:**
1.  Call `get_code_context("bb-mcp-server.core/start-server")`.
    *   *Result:* Receives the full source code of `start-server`.
2.  Call `find_usages("bb-mcp-server.core/start-server")`.
    *   *Result:* Receives list `["bb-mcp-server.main/-main", "test.integration/test-startup"]`.
3.  **LLM Response:** "The `start-server` function initializes the router and starts the Jetty server. It is primarily called by the main entry point in `bb-mcp-server.main` and the integration test suite."

## 6. Scope & Limitations (Crucial)

It is important to define what this tool is **NOT**:

1.  **Not a Runtime Tracer:** We do **not** `eval` the code during indexing. We do not track the values of atoms or the state of the application. We only track **static definitions**.
2.  **Not a Dependency Manager:** We do not calculate the "build order" or handle "hot reloading" logic.
3.  **The "Map vs. Traffic" Analogy:**
    *   This tool builds the **Map** (Static Analysis): "Road A connects to Road B."
    *   It does *not* track the **Traffic** (Runtime State): "There is a car on Road A moving at 50mph."

4.  **No Macro Expansion:**
    *   `edamame` parses the code **as written**. It does not expand macros.
    *   *Benefit:* The LLM sees the high-level abstraction (e.g., `(def-component ...)`), which is usually more semantic and readable than the expanded Clojure core code.
    *   *Limitation:* If a macro hides a function call deep in its implementation (and that implementation isn't visible in the form), the "Call Graph" might miss it. This is an acceptable trade-off for a retrieval tool.

**Handling Sequence:**
Since `edamame` provides line numbers (`:row`), we store this as `:code/line`.
*   When `explore_namespace` is called, results are **sorted by line number**.
*   This preserves the author's logical flow (e.g., helpers defined before usage) without requiring complex dependency resolution logic.

## 7. Comparison with Existing Tools
*   **`mcp-server-git`:** Can read files, but doesn't understand code structure. LLM has to read the whole file to find one function.
*   **`mcp-server-postgres`:** Could store code, but requires setting up a heavy DB. Datalevin is embedded and perfect for this.
*   **LSP (Language Server Protocol):** Similar capabilities, but LSP is designed for *IDEs* (cursor position, completion), not *LLMs* (semantic query). This tool bridges that gap.

## 8. Operational Strategy (The "Human-AI Hybrid" Model)

### Q: How does the Expert know about this?
**A: The System Prompt.**
We do not rely on the expert "figuring it out." We explicitly instruct it in its `system-prompt`:

> "You are a Clojure Expert with access to a **Structural Code Database**.
> 1.  **DO NOT** rely on your training data for this codebase. It is outdated.
> 2.  **DO NOT** read raw files unless necessary.
> 3.  **ALWAYS** use `get_code_context` to read functions.
> 4.  **ALWAYS** use `find_usages` before changing a function to check for breaking changes."

### Q: How does the Expert WRITE code?
**A: File-First (Git is Truth).**
We must avoid the "Split Brain" problem where the DB says one thing and the file says another.
*   **The Rule:** The File System (and Git) remains the **Source of Truth**.
*   **The Workflow:**
    1.  Expert calls `edit_file` (standard MCP tool).
    2.  File is updated on disk.
    3.  **File Watcher** (or post-tool hook) triggers `index_file(path)`.
    4.  Datalevin is updated to reflect the new state of the file.

*Why not write to DB directly?*
*   It breaks standard tooling (VS Code, Git, grep).
*   It requires complex "Code Generation" (DB -> String) which loses formatting/comments unless perfectly preserved.
*   It makes it hard for humans to intervene.

## 9. Alternative: The "DB-First" Vision (Deep Dive)

**The Concept:**
What if we treat the Database as the **Source of Truth** and files as merely "temporary views" or "export artifacts"? This moves the architecture towards a "Lisp Machine" or "Smalltalk" environment.

### How far could we take this?

1.  **Code as Data (Homoiconicity):**
    *   We don't store strings of code; we store the *AST* or the *Forms*.
    *   A "Namespace" is no longer a file; it is a **Query**: `(find-forms-by-ns :my.app)`.
    *   "Files" are generated on-demand for humans to read, but the AI edits the DB entities directly.

2.  **Granular Versioning:**
    *   Git versions *files*. Datalevin versions *entities*.
    *   We could track the history of a *single function* independent of the file it lives in.
    *   "Blame" becomes precise: "Who changed this specific form?"

3.  **The "Projectional" Workflow:**
    *   **AI:** Updates the DB directly (`{:db/id 123 :code/source "(defn new-impl ...)"}`).
    *   **Human:** Opens a "Virtual File" in VS Code. A plugin (or FUSE drive) queries the DB and renders the text. When the human saves, it parses and updates the DB.
    *   **Runtime:** A custom bootloader (`(boot-from-db!)`) reads forms from Datalevin and `eval`s them, bypassing the file system entirely.

### The "One Form Per Line" Strategy (Canonical Storage)

A powerful intermediate step is to adopt a **Canonical Storage Format** where every top-level form is stored as a single line (whitespace compressed).

*   **Addressing:** "Form 4" is always "Line 4". No need for complex row/col tracking.
*   **Diffing:** Git diffs become semantic. "Replaced Line 4" means "Replaced this function". No noise from internal whitespace changes.
*   **AI Reliability:** LLMs are better at "Replace Line 4" than "Replace lines 12-45".
*   **Presentation:**
    *   **Human:** `cljfmt` runs on open/save.
    *   **AI:** Can receive `cljfmt` version for reading (better comprehension) or "One Line" version for editing (precise targeting).

### The Trade-offs (Reality Check)

| Feature | File-First (Current) | DB-First (Visionary) |
| :--- | :--- | :--- |
| **Tooling** | Works with VS Code, grep, GitHub, CI/CD | Requires custom adapters for everything |
| **Collaboration** | `git merge` works | Need to invent "Database Merging" |
| **Runtime** | Standard `bb` / `clj` | Custom bootloader required |
| **AI Power** | High (Structural Search) | **Maximum** (Direct Graph Manipulation) |

**Verdict:**
While "DB-First" is the ultimate "AI-Native" architecture, it requires rebuilding the entire developer ecosystem (Editors, Version Control, CI).
**Recommendation:** Stick to **File-First** for now to remain compatible with the world, but build the **Indexer** to be so fast that it *feels* like DB-First to the AI.
**Formatting Strategy:** Use `cljfmt` consistently. It is the "native dialect" of LLMs (training bias). We don't need a special "AI Format"; standard idiomatic Clojure is optimal for AI comprehension.

### The "FQN vs. Alias" Strategy

**The Problem:**
Humans use aliases (`str/join`) for brevity. LLMs and Databases prefer Fully Qualified Names (`clojure.string/join`) for precision and graph integrity.

**The Solution:**
1.  **Storage (Graph):** The `:code/calls` attribute MUST store FQNs. This ensures the dependency graph is accurate.
2.  **Storage (Source):** The `:code/source` attribute stores the code *as written* (preserving aliases).
3.  **AI Directive:**
    *   **Reading:** The AI sees the original source (with aliases). It is smart enough to resolve them using the `ns` declaration at the top of the file.
    *   **Writing:** We instruct the AI to **follow the patterns in the file**.
        *   If the file uses `[clojure.string :as str]`, the AI should use `str/join`.
        *   If the file uses FQNs, the AI should use FQNs.
    *   *Why?* Consistency is king. Mixing FQNs and aliases in the same file confuses humans and linters.

**Preferred Alias Table:**
We can maintain a project-level `alias-map` (e.g., in `bb.edn` or `system.edn`):
```clojure
{:aliases {clojure.string str
           clojure.java.io io
           babashka.fs fs}}
```
*   **Presentation:** When generating a new file or refactoring, we can use this map to suggest standard aliases.
*   **Linting:** We can enforce these aliases to keep the codebase uniform.

### The "Post-Human" Codebase (Future Vision)

**The Shift:**
As AI agents write more code, the priority shifts from "Human-Writeability" to "AI-Comprehensibility" and "Human-Readability (on demand)".

**The "Personalized View" Concept:**
If the DB stores FQNs (the "Canonical Form"), then the "File" becomes a **Personalized View**.
*   **User A** prefers `[clojure.string :as str]`.
*   **User B** prefers `[clojure.string :as s]`.
*   **The System:**
    1.  Stores `(clojure.string/join ...)` in the DB.
    2.  When User A opens the file, the "Projection Engine" renders `(str/join ...)`.
    3.  When User B opens the file, it renders `(s/join ...)`.

**Conclusion:**
This confirms the **DB-First** direction. By storing the *semantic* code (FQNs) in the DB, we decouple the *logic* from the *style*.
*   **AI:** Reads/Writes FQNs (unambiguous).
*   **Human:** Reads/Writes Aliases (ergonomic).
*   **The Bridge:** The MCP Tool translates between the two on the fly.

### The "Componentized Namespace" Model

**The Problem:**
Standard Clojure namespaces mix **Definitions** (pure functions) with **Execution** (top-level side effects, `defonce`, `println`). This makes "Loading" a fragile, order-dependent operation.

**The Solution:**
We can instruct the AI to follow a strict **Component-Like Lifecycle**:

1.  **No Top-Level Side Effects:**
    *   Banned: `(def _ (start-server!))`
    *   Banned: `(println "Loading ns...")`
    *   Allowed: `(def config {...})` (Pure data)

2.  **Explicit Initialization:**
    *   If a namespace needs to "start" something, it must define an `init!` or `start!` function.
    *   The Runtime (or the AI Orchestrator) calls this function explicitly after loading.

3.  **Implicit Dependencies:**
    *   Since we use FQNs, `require` is just for the runtime loader.
    *   The DB Graph knows the true dependency tree (`:code/calls`).

**Benefit:**
This makes the codebase **Order-Independent** for definitions. The AI can insert a new function anywhere in the DB without worrying about "breaking the script execution flow." It turns the codebase into a **Bag of Functions** rather than a **Script**.
