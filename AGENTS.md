# AGENTS.md — KBook

AI-native reading platform. React 19 frontend + Spring Boot 3.4 backend.

## Commands

### Frontend (`frontend/`)
- `npm install` — install deps (uses npm, not pnpm despite lockfile presence)
- `npm run dev` — Vite dev server on **port 15173**, proxies `/api` → `http://localhost:8181`
- `npm run build` — runs `tsc -b && vite build` (TypeScript project references, outputs to `dist/`)
- `npm run lint` — ESLint
- Path alias: `@/` → `src/`

### Backend (`backend/`)
- `mvn spring-boot:run` — starts on **port 8181** (not 8080)
- `mvn clean package -DskipTests` — build JAR to `target/kbook-server-1.0.0.jar`
- Java 17 required

### Docker (`deploy/`)
- `docker compose up -d` — MySQL, Redis, ES, Qdrant, backend (port 8080), Nginx
- Docker backend runs on **port 8080**, dev runs on **8181** — do not confuse

## Infrastructure (all required for backend to start)
| Service | Dev Port | Docker Port |
|---------|----------|-------------|
| MySQL 8 | 3306 | 3306 |
| Redis 7 | 6379 | 6379 |
| Elasticsearch 8.12 | 9201 | 9200 |
| Qdrant v1.12 | 6334 | 6334 |

**ES port mismatch**: dev default is `9201` (`application.yml`), Docker exposes `9200`. Set `ES_URIS` accordingly.

## Backend env defaults (dev)
- DB: `kbook-dev`, user `root`, password `123456`
- Redis password: `123456`, database `4`
- ES: `http://localhost:9201` (no auth)
- Qdrant: `localhost:6334` (gRPC)
- AI: Ollama at `http://localhost:11434`, model `gemma3n:e4b`, embedding `qwen3-embedding:0.6b`
- Book paths default to `G:/图书/{epub,pdf1,txt1}` — change for your machine
- Admin default: `admin@kbook` / `admin123456` (injected by `DataInitializer`)

## Architecture notes
- **API response envelope**: all responses wrapped in `Result<T>` → `{code, message, data}`. `code=0` = success. Frontend `request.ts` unwraps `data` on `code===0`.
- **Business codes**: `1001` = user pending approval, `1002` = user banned. Frontend handles these specially (no toast).
- **SSE endpoints**: AI chat (`/api/ai/chat`, `/api/books/{id}/chat`), admin scan (`/api/books/admin/scan`). Timeouts set to 1 hour across Vite proxy, Tomcat, and Nginx.
- **Circular dependency**: `AiToolService → RecommendService → EmbeddingService → AiProviderConfigService → AiToolService`. Resolved with `@Lazy` and `ObjectProvider`. Do not remove.
- **EmbeddingService lazy init**: breaks circular dep with `AiProviderConfigService`. Embedding model is lazily initialized.
- **Two Qdrant collections**: `kbook_books` (metadata vectors for recommendation) and `kbook_content` (chunked book content for RAG). 1024-dim vectors, int8 scalar quantization.
- **RAG chunking**: 800 chars per chunk, 200 char overlap. Configurable via `kbook.qdrant.chunk-size` / `chunk-overlap`.
- **JPA ddl-auto**: `update` — schema auto-migrates on startup. No manual migrations.
- **JWT**: access token 2h, refresh token 7d. Frontend auto-refreshes on 401 with request queuing.

## Frontend specifics
- State: Zustand stores (`auth`, `reader`, `progress`, `tts`, `ui`)
- Router: React Router 7 with `createBrowserRouter`, lazy-loaded pages, two layouts (`AppLayout` with TabBar, `BlankLayout` for reader/auth/admin)
- Route guards: `AuthGuard`, `AdminGuard`, `GuestGuard`
- Three reader renderers: `EpubRenderer` (epubjs), `PdfRenderer` (pdfjs-dist), `TxtRenderer`
- PWA enabled with `autoUpdate` registration
- No test files exist despite `@playwright/test` in devDependencies

## Deployment
- Production behind Nginx at `book.keiskei.top` (SSL via Let's Encrypt)
- Nginx proxies to backend on `127.0.0.1:8080`
- Frontend `dist/` served as static files by Nginx
- `deploy/nginx/kbook.conf` also routes other domains on same server — only `book.keiskei.top` is KBook
