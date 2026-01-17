# Code Browser v2 Datalevin Schema

**Version:** 0.1.0
**Last Updated:** 2026-01-17
**Status:** Implementation Phase R0

---

## Design Decisions

This schema implements:
- **D3**: URI-centric design - every entity addressable via URI
- **D5**: Metadata only - no source code strings stored (content in LRU cache)
- **D12**: Minimal schema upfront, extend as needed

---

## URI Format

```
<source>://<project>@<version>/<namespace>/<symbol>
```

**Examples:**
```
dir://bb-mcp-server@abc123/bb-mcp-server.main/register!
jar://taoensso.trove@1.0.0/taoensso.trove/log!
github://taoensso/trove@v1.2.0/taoensso.trove.core/init!
nrepl://localhost:7888@01950a3b-1234-7def/user/my-fn
```

**Source Types:**
| Source | Description | Version Type |
|--------|-------------|--------------|
| `dir` | Local directory (git-versioned) | Static (git SHA) |
| `jar` | Maven JAR file | Static (Maven version) |
| `github` | GitHub repository | Static (tag/SHA) |
| `nrepl` | Live nREPL connection | Temporal (UUIDv7) |

---

## Entity Hierarchy

```
Project (source://project@version)
  └── Namespace (source://project@version/namespace)
        └── Symbol (source://project@version/namespace/symbol)
```

Each level has its own URI serving as the primary identifier.

---

## Schema Definition

```clojure
{;; === URI Identity ===
 ;; Every entity is addressable via URI (D3)
 :uri/string       {:db/unique :db.unique/identity
                    :db/doc "Full URI string - primary identifier"}
 :uri/source       {:db/doc "Source type: :dir :jar :github :nrepl"}
 :uri/project      {:db/doc "Project identifier"}
 :uri/version      {:db/doc "Version/SHA/snapshot-id"}
 :uri/version-type {:db/doc "Version type: :static or :temporal"}
 :uri/namespace    {:db/doc "Namespace name (if applicable)"}
 :uri/symbol       {:db/doc "Symbol name (if applicable)"}

 ;; === Hierarchy Refs ===
 :uri/parent       {:db/valueType :db.type/ref
                    :db/doc "Parent entity (symbol→ns, ns→project)"}

 ;; === Project Attributes ===
 :project/root-path   {:db/doc "Filesystem path for :dir sources"}
 :project/jar-path    {:db/doc "JAR file path for :jar sources"}
 :project/github-url  {:db/doc "GitHub URL for :github sources"}
 :project/nrepl-host  {:db/doc "nREPL host:port for :nrepl sources"}
 :project/namespaces  {:db/valueType :db.type/ref
                       :db/cardinality :db.cardinality/many
                       :db/doc "Namespaces in this project"}

 ;; === Namespace Attributes ===
 :ns/name         {:db/doc "Namespace name (e.g., 'clojure.core')"}
 :ns/file         {:db/doc "Primary source file path"}
 :ns/files        {:db/cardinality :db.cardinality/many
                   :db/doc "All source files (for multi-file namespaces)"}
 :ns/doc          {:db/doc "Namespace docstring"}
 :ns/symbols      {:db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/many
                   :db/doc "Symbols defined in this namespace"}
 :ns/aliases      {:db/cardinality :db.cardinality/many
                   :db/doc "Alias mappings [{:alias x :ns y}]"}
 :ns/refers       {:db/cardinality :db.cardinality/many
                   :db/doc "Referred symbols [{:sym x :from-ns y}]"}

 ;; === Symbol Attributes (METADATA ONLY - D5) ===
 :symbol/name     {:db/doc "Symbol name"}
 :symbol/type     {:db/doc "Symbol type: :defn :def :defmacro :defmulti :defmethod :defprotocol :defrecord :deftype :ns etc"}
 :symbol/file     {:db/doc "Source file path (content fetched on demand)"}
 :symbol/line     {:db/doc "Start line number"}
 :symbol/end-line {:db/doc "End line number (for source extraction)"}
 :symbol/col      {:db/doc "Start column"}
 :symbol/doc      {:db/doc "Docstring (small, ok to store)"}
 :symbol/arglists {:db/doc "Argument lists for functions/macros"}
 :symbol/private? {:db/doc "Is this a private var?"}
 :symbol/macro?   {:db/doc "Is this a macro?"}
 :symbol/dynamic? {:db/doc "Is this a dynamic var?"}

 ;; === Relationships ===
 :symbol/deps     {:db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/many
                   :db/doc "Symbols this symbol calls/uses"}
 :symbol/callers  {:db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/many
                   :db/doc "Symbols that call/use this symbol"}

 ;; === Protocol/Multimethod Specifics ===
 :symbol/protocol      {:db/valueType :db.type/ref
                        :db/doc "For protocol methods: ref to protocol"}
 :symbol/dispatch-val  {:db/doc "For defmethod: dispatch value as string"}
 :symbol/impls         {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many
                        :db/doc "For protocols/multimethods: implementations"}}
```

---

## Common Queries

### List All Projects
```clojure
'[:find [(pull ?p [:uri/string :uri/project :uri/version :uri/source
                   :project/root-path]) ...]
  :where [?p :uri/source _]
         [?p :uri/project _]
         (not [?p :uri/namespace _])]
```

### Namespaces for Project
```clojure
'[:find [(pull ?ns [:uri/string :ns/name :ns/file :ns/doc]) ...]
  :in $ ?project-uri
  :where [?p :uri/string ?project-uri]
         [?p :project/namespaces ?ns]]
```

### Symbols for Namespace
```clojure
'[:find [(pull ?sym [:uri/string :symbol/name :symbol/type :symbol/line
                     :symbol/doc :symbol/arglists :symbol/private?]) ...]
  :in $ ?ns-uri
  :where [?ns :uri/string ?ns-uri]
         [?ns :ns/symbols ?sym]]
```

### Symbol Detail
```clojure
'[:find (pull ?sym [*]) .
  :in $ ?sym-uri
  :where [?sym :uri/string ?sym-uri]]
```

### Symbol Callers (who calls this?)
```clojure
'[:find [(pull ?caller [:uri/string :symbol/name :symbol/type]) ...]
  :in $ ?sym-uri
  :where [?sym :uri/string ?sym-uri]
         [?caller :symbol/deps ?sym]]
```

### Symbol Dependencies (what does this call?)
```clojure
'[:find [(pull ?dep [:uri/string :symbol/name :symbol/type]) ...]
  :in $ ?sym-uri
  :where [?sym :uri/string ?sym-uri]
         [?sym :symbol/deps ?dep]]
```

### Find Symbol by Name
```clojure
'[:find [(pull ?sym [:uri/string :symbol/name :symbol/type :uri/namespace]) ...]
  :in $ ?name
  :where [?sym :symbol/name ?name]]
```

---

## Example Data

### Project Entity
```clojure
{:uri/string "dir://bb-mcp-server@abc123"
 :uri/source :dir
 :uri/project "bb-mcp-server"
 :uri/version "abc123"
 :uri/version-type :static
 :project/root-path "/Users/frank/Development/bb-mcp-server"}
```

### Namespace Entity
```clojure
{:uri/string "dir://bb-mcp-server@abc123/bb-mcp-server.main"
 :uri/source :dir
 :uri/project "bb-mcp-server"
 :uri/version "abc123"
 :uri/version-type :static
 :uri/namespace "bb-mcp-server.main"
 :uri/parent [:uri/string "dir://bb-mcp-server@abc123"]
 :ns/name "bb-mcp-server.main"
 :ns/file "src/bb_mcp_server/main.clj"
 :ns/doc "MCP server entry point"}
```

### Symbol Entity
```clojure
{:uri/string "dir://bb-mcp-server@abc123/bb-mcp-server.main/start!"
 :uri/source :dir
 :uri/project "bb-mcp-server"
 :uri/version "abc123"
 :uri/version-type :static
 :uri/namespace "bb-mcp-server.main"
 :uri/symbol "start!"
 :uri/parent [:uri/string "dir://bb-mcp-server@abc123/bb-mcp-server.main"]
 :symbol/name "start!"
 :symbol/type :defn
 :symbol/file "src/bb_mcp_server/main.clj"
 :symbol/line 42
 :symbol/end-line 67
 :symbol/doc "Start the MCP server with given config."
 :symbol/arglists "([config])"}
```

---

## Notes

1. **No source code stored** (D5): The `:symbol/file`, `:symbol/line`, and `:symbol/end-line` attributes provide coordinates for fetching source on demand from an LRU cache.

2. **URI as identity** (D3): The `:uri/string` attribute is the unique identifier. Use lookup refs like `[:uri/string "dir://..."]` for navigation.

3. **Static vs Temporal**: Static sources (dir, jar, github) have immutable versions. Temporal sources (nrepl) use UUIDv7 snapshots and may be garbage collected.

4. **Bidirectional deps**: Both `:symbol/deps` (outgoing) and `:symbol/callers` (incoming) are stored for efficient queries.

---

*Last Updated: 2026-01-17*
