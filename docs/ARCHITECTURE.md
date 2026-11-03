# Architecture

How the app runs in production. Stage is the same stack (smaller, no nginx/certbot); its traffic
enters through prod's nginx. Deployment and server setup: [CICD.md](CICD.md).

## Request routing

```mermaid
flowchart LR
    subgraph Internet["🌐 Internet"]
        direction TB
        U["👤 Visitors<br/><small>browser</small>"]
        B["🤖 Bots & crawlers<br/><small>link previews, AI search</small>"]
    end

    subgraph Server["🖥️ Server · vmi3440330"]
        direction LR
        NX{{"🔒 nginx edge<br/><small>:80 → :443 · TLS · certbot</small>"}}

        subgraph Prod["Prod · ergasiakos-odigos.gr"]
            direction TB
            FE["🅰️ frontend<br/><small>Angular SPA</small>"]
            BE["☕ backend<br/><small>Spring Boot</small>"]
            MS[("🔎 Meilisearch<br/><small>articles + vectors</small>")]
            CH[("🧪 ChromaDB<br/><small>off by default</small>")]
        end

        subgraph Stage["Stage · stage.ergasiakos-odigos.gr"]
            direction TB
            SFE["🅰️ stage-frontend"]
            SBE["☕ stage-backend"]
            SMS[("🔎 Meilisearch")]
        end
    end

    DS(["🧠 DeepSeek API"])
    X["⛔ 403"]

    U -- "https · www → 301" --> NX
    B --> NX

    NX -- "/" --> FE
    NX -- "/api/* · /sitemap.xml" --> BE
    NX -. "/api/admin/*" .-> X
    FE -- "/wiki/* for bots<br/>→ SEO HTML" --> BE
    BE -- "search · RAG" --> MS
    BE -. "COMPOSE_PROFILES=chroma" .-> CH
    BE -- "chat" --> DS

    NX -- "stage host" --> SFE
    NX -- "stage /api/*" --> SBE
    SBE --> SMS
    SBE -- "chat" --> DS

    classDef user fill:#eef2ff,stroke:#6366f1,color:#1e1b4b
    classDef edge fill:#dcfce7,stroke:#16a34a,stroke-width:2px,color:#14532d
    classDef app fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef data fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef off fill:#f3f4f6,stroke:#9ca3af,stroke-dasharray:5 4,color:#6b7280
    classDef ext fill:#f3e8ff,stroke:#9333ea,color:#3b0764
    classDef deny fill:#fee2e2,stroke:#dc2626,color:#7f1d1d

    class U,B user
    class NX edge
    class FE,BE,SFE,SBE app
    class MS,SMS data
    class CH off
    class DS ext
    class X deny

    style Internet fill:#fafafa,stroke:#d4d4d8
    style Server fill:#f8fafc,stroke:#64748b
    style Prod fill:#eff6ff,stroke:#3b82f6
    style Stage fill:#fffbeb,stroke:#f59e0b,stroke-dasharray:6 4
```

| Colour | Meaning |
|---|---|
| 🟩 green | public entry point (nginx, TLS for main domain, `www` and `stage.`) |
| 🟦 blue | application containers |
| 🟨 amber | search data (Meilisearch) |
| ⬜ grey, dashed | ChromaDB: defined but not running unless enabled |
| 🟪 purple | external service (DeepSeek) |

- **Wiki, calculators and the search box** don't use the LLM: articles are served by the backend,
  calculators are plain Java, and search always uses Meilisearch.
- **Rate limiting** applies to `/api/chat` and `/api/search`. `/api/admin/` is never reachable from
  the internet.

## Chat

```mermaid
sequenceDiagram
    autonumber
    actor U as 👤 Visitor
    box rgb(219, 234, 254) Backend
        participant BE as ☕ /api/chat
        participant T as 🧰 Tools
        participant R as 🔎 RagRetriever
    end
    participant LLM as 🧠 DeepSeek

    U->>BE: question + chat history
    Note over BE: rate limit + input guard
    BE->>LLM: system prompt + question + tool list
    rect rgb(254, 243, 199)
        loop as often as the model needs
            LLM->>T: search_articles · get_article · calculator
            T->>R: find relevant articles
            Note right of R: Meili by default,<br/>Chroma if APP_RAG_PROVIDER=chroma
            R-->>T: matching articles
            T-->>LLM: tool result
        end
    end
    LLM-->>BE: answer grounded in the articles
    BE-->>U: answer
```

Only the chat calls DeepSeek. The model decides when to look up articles or run a calculator (tool
calling), so answers are grounded in the guide rather than in the model's own knowledge.

## Ingest

```mermaid
flowchart LR
    MD["📚 102 markdown articles<br/><small>inside the backend image</small>"]
    ING["⚙️ POST /api/admin/ingest<br/><small>run on the server only</small>"]
    EMB["🧮 Embeddings<br/><small>all-MiniLM-L6-v2 · local ONNX in ./models</small>"]
    MS[("🔎 Meilisearch")]
    CH[("🧪 ChromaDB")]

    MD --> ING --> EMB --> MS
    EMB -. "if Chroma is running" .-> CH

    classDef src fill:#eef2ff,stroke:#6366f1,color:#1e1b4b
    classDef step fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef data fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef off fill:#f3f4f6,stroke:#9ca3af,stroke-dasharray:5 4,color:#6b7280
    class MD src
    class ING,EMB step
    class MS data
    class CH off
```

Run the ingest after a deploy that changes articles, and after enabling Chroma (see CICD.md).
