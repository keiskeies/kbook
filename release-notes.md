# KBook v1.1.2 发布说明

## 项目简介

**KBook** 是一款 AI 原生的阅读平台，让读书不再孤独。通过 RAG 向量知识库 + 多角色 AI 对话，让每本书都变成一场深度讨论。

> **赛博斗蛐蛐，读书也疯狂** —— 16 种性格 AI 围剿一本书，圆桌激辩、立场交锋，让读书变成一场真人秀。

---

## 核心功能

### 🤖 AI 智能引擎

- **AI 图书问答** — 针对单本书全量内容的 RAG 向量检索问答，自动生成推荐问题，基于原著片段精准回答，支持深入追问
- **AI 圆桌讨论** — 多 AI 角色围绕一本书展开多轮自主讨论，智能主持人控场，生成覆盖度报告
- **AI 辩论赛** — 16 种性格辩手（逻辑型 / 毒舌型 / 幽默型 / 理想主义型等）进行结构化多轮辩论，AI 评委四维度打分点评
- **AI 伴读助手** — 基于 LangChain4j 的多轮流式对话，支持 @Tool 调用（搜索图书 / 查书架 / 查进度 / 查阅读统计）
- **AI 元数据生成** — 自动提取标签、评分、八维度相关度
- **AI 速读卡片** — 一键生成书籍核心观点摘要

### 🎯 个性推荐

- **用户画像** — 注册采集 + 运行时更新，构建多维阅读画像
- **情绪与意图** — 支持按当下情绪和阅读意图动态调整推荐
- **向量召回 + 协同过滤** — 多路融合排序，匹配度评分

### ⚙️ 管理后台

- **目录扫描** — SSE 流式扫描本地 EPUB / PDF / TXT 目录
- **AI 模型管理** — 多提供商 CRUD、连接测试、一键切换
- **用户审核** — 邀请注册制，管理员审核

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **前端** | React 19 + TypeScript + Vite + Tailwind CSS + shadcn/ui |
| **后端** | Spring Boot 3.4 + Java 17 + LangChain4j |
| **数据库** | MySQL 8 + Redis 7 + Elasticsearch 8.12 + Qdrant v1.12 |
| **部署** | Docker Compose + Nginx |

---

## 本次更新内容 (v1.1.2)

### 新增功能
- 为辩论和圆桌讨论添加外部知识生成功能

### 优化改进
- 统一聊天组件文本样式
- 优化部署脚本的包检查和镜像构建逻辑

### Bug 修复
- 修复圆桌派/奇葩说讨论页面自动开始逻辑：数据加载完成前不会自动开始，仅在空数据且为会话创建者时自动开始
- 修复结束按钮显示逻辑：暂停状态下也可直接结束会话

---

## 快速开始

```bash
# 前端开发
cd frontend && npm install && npm run dev    # http://localhost:15173

# 后端开发
cd backend && mvn spring-boot:run            # http://localhost:8181

# Docker 部署
cd frontend && npm run build
cd deploy && docker compose up -d
```

### 默认管理员账号

- 邮箱: `admin@kbook`
- 密码: `admin123456`

---

## 项目地址

- **GitHub**: https://github.com/keiskeies/kbook
- **文档**: https://github.com/keiskeies/kbook/blob/master/README.md

---

**感谢使用 KBook！** 📚✨
