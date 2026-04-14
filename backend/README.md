# KBook 后端

KBook 智能阅读平台后端服务，基于 Spring Boot 3 + MySQL + Redis。

## 技术栈

- **框架**: Spring Boot 3.3.5 + Java 17
- **数据库**: MySQL 8 + HikariCP 连接池
- **缓存**: Redis + Lettuce 连接池
- **认证**: Spring Security + JWT (jjwt 0.12)
- **ORM**: Spring Data JPA + Hibernate
- **邮件**: Spring Boot Mail

## 目录结构

```
src/main/java/com/kbook/
├── KBookApplication.java          # 启动类
├── common/                        # 公共模块
│   ├── api/                       # 统一响应、分页
│   └── exception/                 # 全局异常、业务异常
├── config/                        # 配置类
│   ├── SecurityConfig.java        # Security + JWT 配置
│   ├── CorsConfig.java            # 跨域策略
│   ├── RedisConfig.java           # Redis 序列化
│   ├── JwtUtil.java               # JWT 工具类
│   ├── JwtAuthenticationFilter.java # JWT 过滤器
│   └── DataInitializer.java       # 初始管理员注入
├── controller/                    # 控制器层
│   ├── AuthController.java        # 认证（登录/注册/验证码）
│   ├── UserController.java        # 用户信息
│   ├── AdminController.java       # 管理员审核
│   ├── ProgressController.java    # 阅读进度
│   └── HealthController.java      # 健康检查
├── entity/                        # 实体类
│   ├── User.java                  # 用户（含审核状态）
│   ├── Book.java                  # 图书
│   └── ReadingProgress.java       # 阅读进度
├── repository/                    # 数据访问层
│   ├── UserRepository.java
│   ├── BookRepository.java
│   └── ReadingProgressRepository.java
└── service/                       # 业务逻辑层
    ├── AuthService.java           # 认证服务
    ├── UserService.java           # 用户服务
    └── ReadingProgressService.java # 进度服务
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MYSQL_HOST` | MySQL 主机 | `localhost` |
| `MYSQL_PORT` | MySQL 端口 | `3306` |
| `MYSQL_DB` | 数据库名 | `kbook` |
| `MYSQL_USER` | MySQL 用户 | `root` |
| `MYSQL_PASSWORD` | MySQL 密码 | `root` |
| `REDIS_HOST` | Redis 主机 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `JWT_SECRET` | JWT 密钥 | 内置默认值 |
| `KBOOK_ADMIN_EMAIL` | 管理员邮箱 | `admin@kbook.com` |
| `KBOOK_ADMIN_PASSWORD` | 管理员密码 | `admin123456` |
| `MAIL_HOST` | 邮件 SMTP | `smtp.qq.com` |
| `MAIL_USERNAME` | 邮件账号 | 空 |
| `MAIL_PASSWORD` | 邮件密码 | 空 |

## 运行

```bash
# 1. 创建数据库
mysql -u root -p -e "CREATE DATABASE kbook DEFAULT CHARACTER SET utf8mb4;"

# 2. 启动 Redis
redis-server

# 3. 启动后端
mvn spring-boot:run
```

## API 接口

### 公开接口
- `POST /api/auth/send-code` - 发送验证码
- `POST /api/auth/login/code` - 验证码登录
- `POST /api/auth/login/password` - 密码登录
- `POST /api/auth/register` - 注册
- `POST /api/auth/refresh` - 刷新 Token
- `POST /api/auth/reset-password` - 重置密码
- `GET /api/health` - 健康检查

### 认证接口（需 Token）
- `GET /api/user/me` - 获取当前用户
- `PUT /api/user/profile` - 更新资料
- `POST /api/progress` - 上报阅读进度
- `GET /api/progress/{bookId}` - 获取进度
- `GET /api/progress/list` - 进度列表

### 管理员接口（需 ADMIN 角色）
- `GET /api/admin/users/pending` - 待审核列表
- `POST /api/admin/users/{id}/approve` - 通过审核
- `POST /api/admin/users/batch-approve` - 批量通过
- `POST /api/admin/users/{id}/reject` - 拒绝审核
- `POST /api/admin/users/batch-reject` - 批量拒绝
