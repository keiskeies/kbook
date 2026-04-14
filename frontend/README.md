# KBook 前端

智能移动阅读平台前端，基于 React + TypeScript + Vite + Tailwind CSS + shadcn/ui。

## 技术栈

- **框架**: React 19 + TypeScript 5.9
- **构建**: Vite 7
- **样式**: Tailwind CSS 3.4 + shadcn/ui
- **路由**: React Router 7（路由守卫 + 懒加载）
- **状态**: Zustand
- **请求**: Axios（拦截器 + Token 刷新）
- **PWA**: vite-plugin-pwa

## 目录结构

```
src/
├── components/         # 通用组件
│   ├── auth/           # 认证相关组件（路由守卫）
│   ├── layout/         # 布局组件（TabBar、AppLayout）
│   └── ui/             # shadcn/ui 基础组件
├── constants/          # 常量定义（路由、状态、存储键名）
├── hooks/              # 自定义 Hooks
├── pages/              # 页面组件
│   ├── ai/             # AI 助理页
│   ├── auth/           # 登录/注册页
│   ├── bookshelf/      # 书架页
│   ├── home/           # 首页
│   ├── profile/        # 个人中心页
│   └── rank/           # 榜单页
├── router/             # 路由配置
├── store/              # Zustand 状态管理
├── types/              # TypeScript 类型定义
└── utils/              # 工具函数（请求封装等）
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `VITE_API_BASE_URL` | API 基础地址 | `http://localhost:8080/api` |
| `VITE_APP_NAME` | 应用名称 | `KBook` |
| `VITE_TOKEN_KEY` | Token 存储键名 | `kbook_token` |
| `VITE_REFRESH_TOKEN_KEY` | Refresh Token 键名 | `kbook_refresh_token` |
| `VITE_CODE_COUNTDOWN` | 验证码倒计时秒数 | `60` |

## 开发

```bash
npm install
npm run dev
```

## 构建

```bash
npm run build
```
