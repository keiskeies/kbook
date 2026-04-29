<p align="center">
  <img src="book-open.svg" alt="KBook" width="80" height="80" />
</p>

<h1 align="center">KBook</h1>

<p align="center">
  <strong>The Right Book for Who You Are Right Now</strong>
</p>

<p align="center">
  AI-Native Reading Platform · AI truly "reads" every book before discussing every detail with you
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19-blue" alt="React 19" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-green" alt="Spring Boot 3.4" />
  <img src="https://img.shields.io/badge/LangChain4j-AI-orange" alt="LangChain4j" />
  <img src="https://img.shields.io/badge/Qdrant-Vector-purple" alt="Qdrant" />
  <img src="https://img.shields.io/badge/PWA-Ready-blueviolet" alt="PWA" />
  <a href="https://github.com/keiskeies/kbook/stargazers">
    <img src="https://img.shields.io/github/stars/keiskeies/kbook?style=social" alt="Stars" />
  </a>
</p>

<p align="center">
  <a href="./README.md">中文</a> · <a href="./README_EN.md">English</a>
</p>

<p align="center">
  <a href="#-quick-start"><strong>Quick Start</strong></a> · <a href="#-preview">Preview</a> · <a href="#-features">Features</a> · <a href="#how-ai-book-qa-works">How AI Q&A Works</a>
</p>

---

## Why KBook?

> **Traditional reading apps end at "the last page."**
> **KBook begins with the first thing you want to say after closing the book.**

Have you ever experienced these moments —

- Finished a great book, mind buzzing with thoughts, but nowhere to discuss them?
- A passage confused you, but search engines can't find book-specific deep analysis?
- Got recommended a "must-read" that completely misses your life stage?

KBook redefines the relationship between reader and book: **not read-and-leave, but talk-it-through.**

---

## ✨ The Right Book for Who You Are Right Now

A 30-year-old unmarried INTJ and a 35-year-old ESFJ with two kids shouldn't be reading the same "life guide."

KBook builds its recommendation engine on real life states — age, marital status, parenthood, MBTI — so every book precisely matches your current life chapter. More importantly, we reject the "read and leave" approach to shallow reading. KBook injects a book's entire content into a RAG vector knowledge base, so the AI truly "reads" the book before sitting down to discuss every detail with you:

- 📖 **Piercing Follow-ups** — "The author's argument in Chapter 3 is self-contradicted in Chapter 5 — what do you think?" AI retrieves across chapters, revealing the full argumentative arc.
- 🧠 **Socratic Dialogue** — Rather than handing you answers, AI uses the book's own intellectual framework to ask layered questions, pushing you toward independent thinking.
- 🔗 **Knowledge Graph Weaving** — AI compares across the other books on your shelf, surfacing hidden cross-book connections and building your personal cognitive network.
- ✍️ **Stress-Testing Viewpoints** — Introduce dissenting academic perspectives and watch AI dismantle the logical blind spots on both sides of the debate.

**At KBook, books aren't just read — they're talked through.**

---

### 🆚 KBook vs Traditional Reading Apps

| | Traditional Apps | KBook |
|---|---|---|
| **After reading** | Close the book, reading ends | Open AI dialogue, reading begins |
| **Search** | Book title & author only | Full-content vector search, locate passages with one sentence |
| **Recommendations** | Bestseller lists + tag filters | Life-stage-based precision (age / marriage / MBTI) |
| **AI** | Summaries, text-to-speech | RAG Q&A, Socratic dialogue, cross-book knowledge linking |
| **Depth** | Depends on reader alone | AI pushes you deeper until you truly understand |

---

### AI Book Q&A — How It Works

- 🔍 **RAG Precision Retrieval** — Full book content is automatically chunked, vectorized, and stored in Qdrant. Questions trigger millisecond-level recall of the most relevant original passages.
- 🤖 **Grounded in the Original** — AI answers strictly based on retrieved book content — no fabrication, no speculation, every claim traceable to the source.
- 💡 **Smart Suggested Questions** — Auto-generated personalized questions based on book tags (fiction: character dynamics / plot twists; non-fiction: core arguments / practical takeaways).
- 🔄 **Multi-Turn Streaming Dialogue** — SSE streaming output for deep, multi-round discussions about a single book. The more you ask, the deeper you go.
- 🎯 **Quality Gate** — Only books meeting the rating threshold generate content vectors, ensuring Q&A quality.

---

## 📸 Preview

<table>
  <tr>
    <td align="center"><b>📚 Book Detail & AI Q&A</b></td>
    <td align="center"><b>🎯 Recommendations</b></td>
    <td align="center"><b>👤 User Portrait</b></td>
  </tr>
  <tr>
    <td><img src="./doc/detail.png" alt="Book Detail & AI Q&A" width="240" /></td>
    <td><img src="./doc/recome.png" alt="Recommendations" width="240" /></td>
    <td><img src="./doc/self.png" alt="User Portrait" width="240" /></td>
  </tr>
</table>

---

## 🎯 Features

### 🤖 AI Intelligence

- **AI Book Q&A** — RAG vector search Q&A over a single book's full content, auto-generated suggested questions, answers grounded in original passages
- **AI Companion Chat** — Multi-turn streaming dialogue powered by LangChain4j, with @Tool invocation (search books / check bookshelf / check progress)
- **AI Metadata Generation** — Auto-extract tags, ratings, 8-dimension relevance (age / gender / marital status / children / MBTI)
- **Vision OCR** — Scanned PDFs automatically processed via LLM vision capabilities page by page
- **Multi-Model Support** — Admin console supports multiple AI providers (Ollama / OpenAI / DeepSeek etc.), switch with one click

### 📖 Immersive Reader

- **Multi-Format Engine** — Unified EPUB / PDF / TXT engine with adjustable font, size, line height, and theme colors
- **Progress Sync** — Real-time upload + offline batch sync, timestamp-based conflict resolution, seamless cross-device handoff
- **TTS Read-Aloud** — Text-chunked speech synthesis, play/pause/stop at your fingertips

### 🎯 Personalized Recommendations

- **User Profiling** — Registration-time collection + runtime updates, building multi-dimensional reading profiles
- **Vector Recall** — Qdrant stores book metadata vectors, cosine similarity recall based on user profiles
- **Collaborative Filtering** — Weighted by reading / collection / rating / completion behaviors, recommending books loved by similar users
- **Fusion Ranking** — Vector recall + collaborative filtering + popular fallback, multi-path fusion sorting

### 💬 Social & Community

- **Book Reviews** — Book/chapter-level comments, nested replies, likes, bookmarks
- **Follow System** — Follow/follower system, user profiles showing reading/read/reviews
- **Notifications** — Comment replies, like milestones, and tiered push notifications

### ⚙️ Admin Console

- **Directory Scan** — SSE streaming scan of local EPUB/PDF/TXT directories, auto-parse and ingest
- **File Upload** — Manual book file upload, auto-parse metadata + AI tag generation
- **Re-Parse** — One-click re-extraction of book metadata (author / description / cover / TOC)
- **AI Model Management** — Multi-provider CRUD, connection testing, one-click enable/disable
- **User Audit** — Invitation-based registration, admin review / batch operations / ban/unban

---

## 🏗️ Architecture

```
kbook/
├── frontend/                # React 19 + TypeScript + Vite
│   ├── src/
│   │   ├── components/      # UI Components
│   │   │   ├── auth/        # Route Guards (AuthGuard / AdminGuard / GuestGuard)
│   │   │   ├── book/        # Book Components (BookChatSheet / AI Q&A Panel)
│   │   │   ├── layout/      # Layout (TabBar / AppLayout / BlankLayout)
│   │   │   ├── reader/      # Reader Components (EpubRenderer / PdfRenderer / TxtRenderer / TtsFloatPlayer)
│   │   │   └── ui/          # shadcn/ui Base Components
│   │   ├── pages/           # Pages
│   │   │   ├── home/        # Home (Stats / Recommendations / Ranks / Categories)
│   │   │   ├── rank/        # Rankings (Read / Rating / New)
│   │   │   ├── ai/          # AI Assistant (Multi-turn Chat)
│   │   │   ├── bookshelf/   # Bookshelf
│   │   │   ├── book/        # Book Detail + AI Q&A Entry
│   │   │   ├── reader/      # Reader (EPUB / PDF / TXT)
│   │   │   ├── search/      # Search (ES Full-Text)
│   │   │   ├── reviews/     # Reviews
│   │   │   ├── follow/      # Follow / Followers
│   │   │   ├── notifications/ # Notifications
│   │   │   ├── profile/     # Profile / Reading History
│   │   │   ├── user/        # User Profile
│   │   │   ├── admin/       # Admin (Audit / Books / AI Config)
│   │   │   └── auth/        # Login / Register / Reset Password
│   │   ├── router/          # Route Config (Lazy Load + Guards)
│   │   ├── store/           # Zustand State Management
│   │   ├── api/             # API Layer
│   │   ├── types/           # TS Type Definitions
│   │   └── utils/           # Utilities (Axios Wrapper)
│   └── vite.config.ts       # PWA + Proxy Config
│
├── backend/                 # Spring Boot 3.4
│   └── src/main/java/com/kbook/
│       ├── common/          # Unified Response, Pagination, Exception Handling
│       ├── config/          # Security / JWT / CORS / Redis / Qdrant / LangChain4j
│       ├── controller/      # REST API Controllers
│       ├── document/        # Elasticsearch Document Definitions
│       ├── entity/          # JPA Entities
│       ├── repository/      # Data Access Layer
│       └── service/         # Business Logic Layer
│
└── deploy/                  # Deployment Config
    ├── docker-compose.yml   # Full-Stack Container Orchestration
    └── nginx/               # Nginx Reverse Proxy Config
```

---

## 🛠️ Tech Stack

### Frontend

| Tech | Version | Purpose |
|------|---------|---------|
| React + TypeScript | 19 / 5 | UI Framework |
| Vite | 7 | Build Tool |
| Tailwind CSS + shadcn/ui | 3.4 | Styling & Components |
| React Router DOM | 7 | Routing |
| Zustand | 5 | State Management |
| Axios | 1.x | HTTP Client (Interceptor + Token Refresh) |
| epubjs | 0.3.93 | EPUB Renderer |
| pdfjs-dist | 5.x | PDF Renderer |
| Lucide React | — | Icon Library |
| vite-plugin-pwa | — | PWA Offline Support |

### Backend

| Tech | Version | Purpose |
|------|---------|---------|
| Spring Boot | 3.4 | Application Framework |
| Java | 17 | Runtime |
| Spring Security + JWT | — | Stateless Auth |
| Spring Data JPA + Hibernate | — | ORM |
| LangChain4j | 1.13 | AI Chat / RAG / Embedding |
| epublib-core | 3.1 | EPUB Parsing |
| Apache PDFBox | — | PDF Parsing / Rendering / OCR |
| Maven | — | Build Tool |

### Infrastructure

| Component | Tech | Purpose |
|-----------|------|---------|
| RDBMS | MySQL 8 + HikariCP | Primary Data Store |
| Cache | Redis 7 (Lettuce) | Captcha / Session / Recommendation Cache |
| Search | Elasticsearch 8.12 | Full-Text Search / Highlight / Suggestion |
| Vector DB | Qdrant v1.12 | Book Metadata Vectors + RAG Content Vectors |
| Mail | QQ SMTP (SSL 465) | Captcha / Invitation / Notification |
| Reverse Proxy | Nginx (Alpine) | SSL / Compression / Rate Limit / SSE / Static |
| Container | Docker Compose | Full-Stack Deployment |

---

## How AI Book Q&A Works

```
┌──────────────┐     ┌────────────────┐     ┌───────────────────┐     ┌──────────────┐
│  User Question │ ──▶ │ Embed & Vectorize │ ──▶ │ Qdrant Similarity │ ──▶ │ LLM Generates │
└──────────────┘     └────────────────┘     └───────────────────┘     └──────────────┘
                                                   │
                            ┌──────────────────────┘
                            ▼
                    ┌───────────────┐
                    │  kbook_content │  ← Full book chunked at 800 chars
                    │  Qdrant Coll.  │  ← Each chunk independently vectorized
                    │  1024-dim vec  │  ← int8 scalar quantization
                    └───────────────┘
```

1. **Ingestion** — After book parsing, full text is chunked at 800 chars (200 char overlap), batch-embedded, and stored in the Qdrant `kbook_content` collection.
2. **Query** — User question → Embedding vectorization → Qdrant cosine similarity search for Top-8 passages.
3. **Response** — Retrieved original passages + book metadata + question → LLM generates grounded, evidence-based answers.
4. **Quality Gate** — Only books meeting the rating threshold generate content vectors; `contentEmbedded` flag controls the Q&A entry point on the frontend.

---

## 🔒 Security

- **JWT Stateless Auth** — Access Token 2h + Refresh Token 7d, 401 auto-refresh
- **Captcha** — Click-to-verify image captcha + email code dual verification, 60s rate limit + 10/day cap + 5min TTL
- **Password Security** — BCrypt hashing
- **Authorization** — `@PreAuthorize` role check + `SecurityConfig` path-level control
- **API Key Masking** — AI provider keys auto-masked in admin responses
- **Token Blacklist** — Logout tokens added to Redis blacklist

---

## 🚀 Quick Start

### Prerequisites

- Node.js 18+, Java 17, Maven 3.8+
- MySQL 8, Redis 7, Elasticsearch 8.12, Qdrant v1.12+

### Local Development

```bash
# Frontend
cd frontend && npm install && npm run dev

# Backend
cd backend && mvn spring-boot:run
```

### Docker Deployment

```bash
# Build frontend
cd frontend && npm run build

# Launch all services
cd deploy && docker compose up -d
```

Docker Compose includes: MySQL, Redis, Elasticsearch, Qdrant, Spring Boot Backend, Nginx (static assets + reverse proxy).

---

## 📡 API Overview

| Module | Prefix | Auth | Key Endpoints |
|--------|--------|------|---------------|
| Auth | `/api/auth` | Public / Auth | Login, Register, Captcha, Token Refresh, Password Reset |
| Home | `/api/home` | Auth | Aggregated Data (Stats / Recommendations / Ranks / Categories) |
| Books | `/api/books` | Public / Auth | Search, Detail, Rank, Cover, Rating, File Stream |
| **Book Q&A** | `/api/books/{id}/chat` | Auth | **RAG Streaming Q&A, Suggested Questions, History** |
| AI Assistant | `/api/ai` | Auth | Multi-turn Chat (SSE), Session Management |
| Bookshelf | `/api/bookshelf` | Auth | CRUD, Check, Count |
| Progress | `/api/progress` | Auth | Report, Batch Sync, Stats |
| Recommend | `/api/recommend` | Auth / Admin | Personalized Recommendations, Cache Clear, Vector Rebuild |
| Comments | `/api/comments` | Public / Auth | Book/Chapter Reviews, Replies, Likes, Bookmarks |
| Notifications | `/api/notifications` | Auth | List, Unread Count, Mark Read |
| User | `/api/user` | Auth | Profile, Avatar, User Profile Update |
| User Profile | `/api/user-profile` | Public | User Page, Reading / Read, Reviews |
| Follow | `/api/follow` | Public / Auth | Follow / Unfollow, Followers / Following |
| Admin | `/api/admin` | ADMIN | User Audit, Invite Registration, Email Bind |
| Book Admin | `/api/books/admin` | ADMIN | Scan, Upload, Re-Parse |
| AI Config | `/api/admin/ai-provider` | ADMIN | Multi-Model CRUD, Enable / Disable, Connection Test |
| Captcha | `/api/captcha` | Public | Click-to-Verify Generation / Validation |
| Health | `/api/health` | Public | Service Status |

---

## 🗺️ Roadmap

- [ ] AI Book Review Generation — Auto-draft reviews based on reading progress and annotations
- [ ] Cross-Book Dialogue — Open multiple books simultaneously, let AI discover hidden cross-book connections
- [ ] Reading Data Visualization — Heatmaps, tag clouds, knowledge graphs
- [ ] Multi-Language Support — Interface and AI Q&A multi-language adaptation
- [ ] Plugin System — Custom AI tools, reader themes, recommendation strategies

---

## 🤝 Contributing

Contributions are welcome! If you have ideas or find issues:

1. Fork this repository
2. Create a Feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## ⭐ Star History

If this project helps you, consider giving it a Star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=keiskeies/kbook&type=Date)](https://star-history.com/#keiskeies/kbook&Date)

---

## License

Private — All Rights Reserved
