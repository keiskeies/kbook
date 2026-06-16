<p align="center">
  <img src="book-open.svg" alt="KBook" width="80" height="80" />
</p>

<h1 align="center">KBook</h1>

<p align="center">
  <strong>Cage Match for Books — Powered by AI</strong>
</p>

<p align="center">
  16 distinct AI personalities battle over every book — roundtable debates, clashing viewpoints, reading as a reality show
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
  <a href="#-core-experiences"><strong>Core Experiences</strong></a> · <a href="#-feature-list">Feature List</a> · <a href="#how-ai-book-qa-works">How AI Q&A Works</a>
</p>

---

## Why KBook?

> **Traditional reading apps end at "the last page."**
> **KBook begins with the first thing you want to say after closing the book.**

Have you ever experienced these moments —

- Finished a great book, mind buzzing with thoughts, but nowhere to discuss them?
- A passage kept you up all night, yet nowhere on the internet offers deep, book-specific analysis?
- Got recommended a "must-read" that completely misses your life stage and personality?
- Facing a complex issue, you crave to see fierce clashes between different stances, not just one voice?

**KBook is built for those who truly want to understand a book.**

We reject the "read and leave" approach to shallow reading. KBook injects a book's entire content into a RAG vector knowledge base, so the AI truly "reads" the book — then sits down for a one-on-one deep chat, hosts a multi-role roundtable, or even stages a fiery debate between debaters of wildly different personalities. Books aren't just read — they're **talked through, debated, and fully understood**.

---

## 🔥 Four Core Experiences

### 1️⃣ AI Book Q&A — Talk All Night with an AI That Truly "Read" the Book

Drop a book into KBook. The AI devours it first (RAG vector knowledge base + 800-character intelligent chunking), then engages you in **penetrating dialogue**:

- **Cross-Chapter Soul-Searching** — "The author's argument in Chapter 3 is self-contradicted in Chapter 5 — what do you think?" AI retrieves across the entire book, revealing the full argumentative arc.
- **Socratic Dialogue** — Rather than handing you answers, AI uses the book's own intellectual framework to ask layered questions, pushing you toward independent thinking.
- **Stress-Testing Viewpoints** — Introduce dissenting academic perspectives and watch AI dismantle the logical blind spots on both sides of the debate.
- **Knowledge Graph Weaving** — AI compares across the other books on your shelf, surfacing hidden cross-book connections and building your personal cognitive network.

**For:** Deep readers bursting with questions after the last page; researchers who need to fully grasp a book quickly; readers who refuse to be fed generic AI fluff and demand every answer cite the original text.

<p align="center">
  <img src="./doc/AI图书问答1.jpg" alt="AI Book Q&A" width="280" />
  <img src="./doc/AI图书问答2.jpg" alt="Follow-up Questions" width="280" />
  <img src="./doc/AI图书问答3.jpg" alt="Multi-turn Dialogue" width="280" />
</p>

---

### 2️⃣ Roundtable Discussion — A Book Read Through N Different Lives

Reading alone is solitude; reading together is a feast. KBook's **Roundtable Discussion** brings multiple AI roles into multi-turn dialogue around a single book:

- **Autonomous Multi-Role Speaking** — Each AI role speaks from its own persona (life experience, professional background, personality tendency), offering independent perspectives.
- **Genuine Idea Clash** — Roles challenge, complement, and extend each other's points, creating real discursive tension.
- **Smart Moderator** — An AI moderator keeps the discussion on track and prevents it from going cold or off-topic.
- **Coverage Report** — After the discussion, a coverage analysis reveals which angles haven't been explored yet.

**For:** Curious readers who wonder "how do people from different backgrounds see this book?"; book club organizers looking for discussion inspiration; social learners who feel they can't fully grasp a book alone and crave hearing other perspectives.

<p align="center">
  <img src="./doc/圆桌派.jpg" alt="Roundtable Discussion" width="280" />
  <img src="./doc/圆桌派2.jpg" alt="Roundtable Role View" width="280" />
</p>

---

### 3️⃣ AI Debate — 16 Distinct Personalities Debate a Book Inside Out

This is KBook's most electrifying feature. We've designed **16 vividly distinct AI debater personalities** — Logical, Scathing, Witty, Idealistic, Pessimistically Cautious... — who engage in **structured multi-turn debates** around controversial ideas in a book:

- **Personalities That Leap Off the Screen** — The Scathing type mercilessly tears apart logical fallacies; the Witty type wraps sharp points in humor; the Logical type obsesses over every break in the evidentiary chain.
- **Multi-Round Attack and Defense** — Opening arguments, cross-examination, free debate, closing statements — a complete simulation of real debate tournament flow.
- **AI Judge Scoring** — After each round, AI judges score across four dimensions (argument, evidence, delivery, rebuttal) and generate detailed critique reports.
- **Watch the Debate, Then Read the Book** — Many users say: "After watching the AI debate, I realized I completely misread the book the first time."

**For:** Fans of debate shows and competitive argument; rational decision-makers who want to "see how both sides fight it out" before forming an opinion; readers who feel a book "sort of makes sense but something feels off" and want to use debate to sort out their thoughts.

<p align="center">
  <img src="./doc/奇葩说.jpg" alt="AI Debate" width="280" />
  <img src="./doc/奇葩说1.jpg" alt="AI Debate Role View" width="280" />
</p>

---

### 4️⃣ Persona-Based Recommendations — Only Someone Who Understands Your Life Can Recommend the Right Book

A 30-year-old unmarried INTJ and a 35-year-old ESFJ with two kids shouldn't be reading the same "life guide." KBook's recommendation engine is built on **real life state + current mood + reading intent** triple matching:

- **Life Persona** — Age, marital status, parenthood stage, MBTI, interest tags — building your multi-dimensional reading DNA.
- **Mood Awareness** — Happy, anxious, exhausted, lost... the same book gets completely different recommendation priority depending on your emotional state.
- **Reading Intent** — Are you looking to "recharge" (learn skills), "resonate" (find comfort), or "resolve" (get answers)? Different intents trigger different recommendation logics.
- **8-Dimension Match Score** — Every book shows its match score with your profile, quantifying the recommendation reason. No more "it's popular so we recommend it" nonsense.

**For:** Readers tired of homogeneous "everyone's reading this" recommendations; those in life transitions (career change, marriage, parenthood) who need the *right* book, not just a *good* book; anyone who believes reading is personal and demands recommendations that truly understand them.

<p align="center">
  <img src="./doc/推荐列表.jpg" alt="Recommendations" width="280" />
  <img src="./doc/个人画像.jpg" alt="User Portrait" width="280" />
</p>

---

### 🆚 KBook vs Traditional Reading Apps

| | Traditional Apps | KBook |
|---|---|---|
| **After reading** | Close the book, reading ends | Open AI dialogue, reading begins |
| **Discussion** | Skim through shallow comment sections | One-on-one deep chat / Multi-role roundtable / 16-personality debate |
| **Search** | Book title & author only | Full-content vector search, locate passages with one sentence |
| **Recommendations** | Bestseller lists + tag filters | Life-state-based precision (age / marriage / MBTI / mood / intent) |
| **AI** | Summaries, text-to-speech | RAG Q&A, Socratic dialogue, roundtable, AI debate, cross-book knowledge linking |
| **Depth** | Depends on reader alone | AI pushes you deeper through questioning, debating, and discussing until you truly understand |

---

## 🎯 Feature List

### 🤖 AI Intelligence

- **AI Book Q&A** — RAG vector search Q&A over a single book's full content, auto-generated suggested questions, answers grounded in original passages, follow-up question support
- **AI Roundtable Discussion** — Multi AI roles engage in autonomous multi-turn discussion around a book, with smart moderator and coverage report generation
- **AI Debate Tournament** — 16 distinct personality debaters (Logical / Scathing / Witty / Idealistic / Pessimistically Cautious etc.) in structured multi-turn debates, with AI judges scoring across four dimensions
- **AI Companion Chat** — Multi-turn streaming dialogue powered by LangChain4j, with @Tool invocation (search books / check bookshelf / check progress / check reading stats)
- **AI Metadata Generation** — Auto-extract tags, ratings, 8-dimension relevance (age / gender / marital status / children / MBTI)
- **AI Speed Read** — One-click generation of core viewpoint summaries, quickly assess whether a book is worth deep reading
- **Vision OCR** — Scanned PDFs automatically processed via LLM vision capabilities page by page
- **Multi-Model Support** — Admin console supports multiple AI providers (Ollama / OpenAI / DeepSeek etc.), switch with one click
- **Chat Styles** — Four dialogue styles (Casual / Deep / Concise / Witty) for different reading scenarios

<p align="center">
  <img src="./doc/AI助手.jpg" alt="AI Companion" width="280" />
  <img src="./doc/AI助手2.jpg" alt="AI Companion Multi-turn" width="280" />
</p>

### 🎯 Personalized Recommendations

- **User Profiling** — Registration-time collection + runtime updates, building multi-dimensional reading profiles (age / gender / marital status / children / MBTI / interest tags)
- **Mood & Intent** — Dynamic recommendation adjustment based on current mood (happy / anxious / exhausted / lost etc.) and reading intent (recharge / resonate / resolve etc.)
- **Vector Recall** — Qdrant stores book metadata vectors, cosine similarity recall based on user profiles
- **Collaborative Filtering** — Weighted by reading / collection / rating / completion behaviors, recommending books loved by similar users
- **Fusion Ranking** — Rule matching + vector recall + collaborative filtering + exploration discovery + popular fallback, multi-path fusion sorting
- **Match Score** — Each book displays 8-dimension match score with your profile, quantifying recommendation reasons

### ⚙️ Admin Console

- **Directory Scan** — SSE streaming scan of local EPUB/PDF/TXT directories, auto-parse and ingest, resume from breakpoint
- **File Upload** — Manual book file upload, auto-parse metadata + AI tag generation
- **Re-Parse** — One-click re-extraction of book metadata (author / description / cover / TOC)
- **AI Model Management** — Multi-provider CRUD, connection testing, one-click enable/disable
- **TTS Configuration** — Manage multiple TTS engines (Xiaomi / iFlytek), configure voice and parameters
- **User Audit** — Invitation-based registration, admin review / batch operations / ban/unban

---

## 🏗️ Architecture

```
kbook/
├── frontend/                # React 19 + TypeScript + Vite
│   ├── src/
│   │   ├── components/      # UI Components
│   │   │   ├── ai/          # AI Components
│   │   │   ├── auth/        # Route Guards
│   │   │   ├── book/        # Book Components (AI Q&A Panel / Match Score / Speed Read)
│   │   │   ├── common/      # Common Components
│   │   │   ├── home/        # Home Components
│   │   │   ├── layout/      # Layout
│   │   │   ├── reader/      # Reader Components
│   │   │   └── ui/          # shadcn/ui Base Components
│   │   ├── pages/           # Pages
│   │   │   ├── home/        # Home
│   │   │   ├── ai/          # AI Assistant
│   │   │   ├── book/        # Book Detail + AI Q&A + Roundtable + Debate
│   │   │   ├── reader/      # Reader
│   │   │   ├── profile/     # Profile / Reading History / Preferences
│   │   │   ├── admin/       # Admin Console
│   │   │   └── auth/        # Login / Register
│   │   ├── router/          # Route Config
│   │   ├── store/           # Zustand State Management
│   │   ├── api/             # API Layer
│   │   ├── hooks/           # Custom Hooks
│   │   ├── types/           # TS Type Definitions
│   │   └── utils/           # Utilities
│   └── vite.config.ts       # PWA + Proxy Config
│
├── backend/                 # Spring Boot 3.4
│   └── src/main/java/com/kbook/
│       ├── common/          # Unified Response, Pagination, Exception Handling
│       ├── config/          # Security / JWT / Redis / Qdrant / LangChain4j
│       ├── constants/       # AI Prompt Constants
│       ├── controller/      # REST API Controllers
│       ├── document/        # Elasticsearch Document Definitions
│       ├── dto/             # Request/Response DTOs
│       ├── entity/          # JPA Entities
│       ├── repository/      # Data Access Layer
│       └── service/         # Business Logic Layer
│           ├── ai/          # AI Services (BookChat / RoundTable / Debate)
│           ├── auth/        # Auth Services
│           ├── book/        # Book Services
│           ├── embedding/   # Embedding Services
│           ├── progress/    # Reading Progress Service
│           ├── recommend/   # Recommendation Service
│           ├── storage/     # File Storage Service
│           ├── tts/         # TTS Services
│           ├── user/        # User Services
│           └── video/       # Video Processing Service
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
| Axios | 1.x | HTTP Client |
| epubjs | 0.3.93 | EPUB Renderer |
| pdfjs-dist | 5.x | PDF Renderer |

### Backend

| Tech | Version | Purpose |
|------|---------|---------|
| Spring Boot | 3.4 | Application Framework |
| Java | 17 | Runtime |
| Spring Security + JWT | — | Stateless Auth |
| LangChain4j | 1.13 | AI Chat / RAG / Embedding |
| epublib-core | 3.1 | EPUB Parsing |
| Apache PDFBox | — | PDF Parsing / OCR |
| Maven | — | Build Tool |

### Infrastructure

| Component | Tech | Purpose |
|-----------|------|---------|
| RDBMS | MySQL 8 | Primary Data Store |
| Cache | Redis 7 | Session / Recommendation Cache |
| Search | Elasticsearch 8.12 | Full-Text Search |
| Vector DB | Qdrant v1.12 | Book Metadata + RAG Content Vectors |
| Reverse Proxy | Nginx | SSL / SSE / Static |
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
3. **Response** — Retrieved original passages + book metadata + user profile + question → LLM generates grounded, evidence-based answers.
4. **Follow-ups** — AI auto-generates follow-up question suggestions, guiding users to explore book content from different angles.
5. **Quality Gate** — Only books meeting the rating threshold generate content vectors; `contentEmbedded` flag controls the Q&A entry point on the frontend.

---

## 🔒 Security

- **JWT Stateless Auth** — Access Token 2h + Refresh Token 7d, 401 auto-refresh with request queuing
- **Captcha** — Click-to-verify image captcha + email code dual verification, 60s rate limit + 10/day cap + 5min TTL
- **Password Security** — BCrypt hashing
- **Authorization** — `@PreAuthorize` role check + `SecurityConfig` path-level control
- **API Key Masking** — AI provider keys auto-masked in admin responses
- **Token Blacklist** — Logout tokens added to Redis blacklist
- **Rate Limiting** — Redis token bucket rate limiting to prevent API abuse
- **Security Headers** — SecurityHeaderFilter injects X-Content-Type-Options / X-Frame-Options and other security response headers

---

## 🚀 Quick Start

### Prerequisites

- Node.js 18+, Java 17, Maven 3.8+
- MySQL 8, Redis 7, Elasticsearch 8.12, Qdrant v1.12+

### Local Development

```bash
# Frontend
cd frontend && npm install && npm run dev    # http://localhost:15173

# Backend
cd backend && mvn spring-boot:run            # http://localhost:8181
```

### Docker Deployment

```bash
# Build frontend
cd frontend && npm run build

# Launch all services
cd deploy && docker compose up -d
```

Docker Compose includes: MySQL, Redis, Elasticsearch, Qdrant, Spring Boot Backend, Nginx (static assets + reverse proxy).

### Default Admin Account

- Email: `admin@kbook`
- Password: `admin123456`

---

## 📡 API Overview

| Module | Prefix | Auth | Key Endpoints |
|--------|--------|------|---------------|
| Auth | `/api/auth` | Public / Auth | Login, Register, Captcha, Token Refresh, Password Reset |
| Home | `/api/home` | Auth | Aggregated Data (Stats / Recommendations / Ranks / Categories) |
| Books | `/api/books` | Public / Auth | Search, Detail, Rank, Cover, Rating, File Stream |
| **Book Q&A** | `/api/books/{id}/chat` | Auth | **RAG Streaming Q&A, Suggested Questions, History, Follow-ups** |
| **Roundtable** | `/api/round-table` | Auth | **Create Discussion, Role Speaking, Message Feed, Report Generation** |
| **AI Debate** | `/api/debate` | Auth | **Create Debate, Debater Speaking, Scoring, Report Generation** |
| AI Assistant | `/api/ai` | Auth | Multi-turn Chat (SSE), Session Management, @Tool Invocation |
| Progress | `/api/progress` | Auth | Report, Batch Sync, Stats |
| Recommend | `/api/recommend` | Auth / Admin | Personalized Recommendations, Cache Clear, Vector Rebuild |
| User | `/api/user` | Auth | Profile, Avatar, User Profile Update, Chat Style |
| Admin | `/api/admin` | ADMIN | User Audit, Invite Registration, Email Bind |
| Book Admin | `/api/books/admin` | ADMIN | Scan, Upload, Re-Parse |
| AI Config | `/api/admin/ai-provider` | ADMIN | Multi-Model CRUD, Enable / Disable, Connection Test |
| TTS Config | `/api/admin/tts` | ADMIN | TTS Engine CRUD, Parameter Configuration |
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

This project is licensed under a **Free for Personal Use, Commercial License Required** model:

- **Personal Users**: You may download, deploy, and use all features of this project free of charge for personal learning, research, and non-commercial purposes only.
- **Commercial Use**: Any use by businesses, organizations, or individuals for commercial operations, internal production environments, SaaS services, resale after modification, or any activity generating direct or indirect revenue **requires prior written authorization**. Please contact the project author to discuss cooperation and licensing.
- **Prohibited Actions**: Using this project or its derivatives for commercial purposes without authorization is strictly prohibited. Removing or altering copyright notices is not allowed.

**Unauthorized commercial use will be treated as infringement, and we reserve the right to pursue legal liability.**
