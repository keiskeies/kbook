# KBook - 智能移动阅读平台

前后端分离的移动端阅读应用，支持 TXT/EPUB/PDF 多格式阅读，集成 AI 阅读助手。

## 项目架构

```
kbook/
├── frontend/                # 前端 - React + TypeScript + Vite
│   ├── src/
│   │   ├── components/      # 通用组件
│   │   │   ├── auth/        # 路由守卫（AuthGuard/AdminGuard/GuestGuard）
│   │   │   ├── layout/      # 布局（TabBar/AppLayout/BlankLayout）
│   │   │   └── ui/          # shadcn/ui 基础组件
│   │   ├── constants/       # 常量定义
│   │   ├── hooks/           # 自定义 Hooks
│   │   ├── pages/           # 页面
│   │   │   ├── home/        # 首页
│   │   │   ├── rank/        # 榜单
│   │   │   ├── ai/          # AI 助理
│   │   │   ├── bookshelf/   # 书架
│   │   │   ├── profile/     # 个人中心
│   │   │   └── auth/        # 登录/注册
│   │   ├── router/          # 路由配置（懒加载+守卫）
│   │   ├── store/           # Zustand 状态管理
│   │   ├── types/           # TS 类型定义
│   │   └── utils/           # 工具函数（Axios封装）
│   └── vite.config.ts       # PWA + 代理配置
│
├── backend/                 # 后端 - Spring Boot 3
│   └── src/main/java/com/kbook/
│       ├── common/          # 统一响应、分页、异常处理
│       ├── config/          # Security、JWT、CORS、Redis、管理员注入
│       ├── controller/      # REST API 控制器
│       ├── entity/          # JPA 实体
│       ├── repository/      # 数据访问层
│       └── service/         # 业务逻辑层
│
└── docs/                    # 文档（后续阶段补充）
```

## 技术选型

| 层次 | 前端 | 后端 |
|------|------|------|
| 框架 | React 19 + TypeScript 5.9 | Spring Boot 3.3.5 + Java 17 |
| 构建 | Vite 7 | Maven |
| 样式 | Tailwind CSS 3.4 + shadcn/ui | - |
| 路由 | React Router 7 | Spring MVC |
| 状态 | Zustand | Spring Security + JWT |
| 请求 | Axios（拦截器+Token刷新） | - |
| 数据库 | - | MySQL 8 + HikariCP |
| 缓存 | - | Redis + Lettuce |
| PWA | vite-plugin-pwa | - |

## 核心模块职责

### 前端模块

| 模块 | 职责 |
|------|------|
| `components/auth` | 路由守卫：AuthGuard（认证+审核状态）、AdminGuard（管理员）、GuestGuard（游客） |
| `components/layout/TabBar` | 底部导航：首页/榜单/AI(居中凸出)/书架/我的 |
| `components/layout/AppLayout` | 主布局：内容区 + TabBar |
| `store/auth` | 认证状态：Token、UserInfo、登录/登出/hydrate |
| `utils/request` | Axios 封装：请求拦截(注入Token)、响应拦截(401刷新)、统一错误处理 |
| `router` | 路由配置：懒加载、嵌套布局、守卫包裹 |
| `constants` | 常量：路由路径、用户状态、图书格式、阅读主题、存储键名 |

### 后端模块

| 模块 | 职责 |
|------|------|
| `config/SecurityConfig` | Security 配置：JWT 无状态、接口权限、CSRF 禁用 |
| `config/JwtAuthenticationFilter` | JWT 过滤器：Token 提取、校验、SecurityContext 注入 |
| `config/JwtUtil` | JWT 工具：Access/Refresh Token 生成、解析、校验 |
| `config/CorsConfig` | 跨域：localhost + 生产域名白名单 |
| `config/RedisConfig` | Redis：JSON 序列化、String 序列化 |
| `config/DataInitializer` | 管理员注入：首次启动自动创建管理员 |
| `service/AuthService` | 认证：验证码发送/校验、双模式登录、注册、Token刷新、密码管理 |
| `service/UserService` | 用户：审核操作(单/批量)、资料更新、邮箱绑定 |
| `service/ReadingProgressService` | 进度：上报、查询、时间戳覆盖冲突解决 |
| `common/exception` | 异常：BusinessException + GlobalExceptionHandler |

## 安全策略

- **JWT 无状态认证**: Access Token 2h + Refresh Token 7d
- **Token 刷新**: 401 时自动使用 Refresh Token 换取新 Token
- **CORS 白名单**: 仅允许 localhost 和生产域名
- **验证码安全**: 60s 限频 + 每日 10 次上限 + 5 分钟过期
- **密码加密**: BCrypt
- **接口权限**: @PreAuthorize 角色校验

## 环境变量规范

所有敏感配置通过环境变量注入，前端使用 `VITE_` 前缀，后端直接使用变量名。详见各子项目 README。

## 开发启动

```bash
# 前端
cd frontend && npm install && npm run dev

# 后端
cd backend && mvn spring-boot:run
```

## 阶段规划

- [x] **阶段一**: 项目骨架与基础路由
- [ ] **阶段二**: 用户认证与审核状态机
- [ ] **阶段三**: 管理员体系与审核面板
- [ ] **阶段四**: 书架、图书元数据与进度同步
- [ ] **阶段五**: 阅读器引擎与参数配置
- [ ] **阶段六**: AI 助理与 LangChain4j 集成
- [ ] **阶段七**: ES 检索、榜单聚合与生产加固
