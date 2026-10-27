#!/usr/bin/env node
/**
 * 本地评测用的 access token 生成器（仅限开发环境）。
 *
 * 背景：access token 默认 2 小时过期，一次 3 轮全量评测跑下来容易中途 401，
 * 把整份报告作废；而登录接口带验证码、被封账号还进不去，评测脚本没法自助拿 token。
 * 这里用本地 dev 配置里的 jwt.secret 直接签一个长时效 token，签完立刻调
 * GET /api/user/me 自检，验证通过才打印。
 *
 * 用法：
 *   node scripts/eval-dev-token.mjs --base-url http://localhost:18181 --ttl-hours 24
 *
 * 注意：签出的 token 等同于该账号的管理员权限，只在本地开发库使用，
 * 不要对生产密钥执行本脚本。
 */
import fs from 'node:fs';
import crypto from 'node:crypto';

function parseArgs(argv) {
  const args = {
    baseUrl: process.env.KBOOK_EVAL_BASE_URL ?? 'http://localhost:8181',
    userId: 1,
    email: 'right_way@foxmail.com',
    role: 'ADMIN',
    ttlHours: 24,
    secretFile: 'backend/src/main/resources/application-dev.yml',
  };
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i];
    const value = argv[i + 1];
    switch (key) {
      case '--base-url': args.baseUrl = value; i++; break;
      case '--user-id': args.userId = Number(value); i++; break;
      case '--email': args.email = value; i++; break;
      case '--role': args.role = value; i++; break;
      case '--ttl-hours': args.ttlHours = Number(value); i++; break;
      case '--secret-file': args.secretFile = value; i++; break;
      default: break;
    }
  }
  return args;
}

/** 从 dev 配置里取 jwt.secret（形如 `secret: ${JWT_SECRET:xxx}`） */
function readDevSecret(file) {
  if (!fs.existsSync(file)) {
    throw new Error(`找不到配置文件: ${file}`);
  }
  const text = fs.readFileSync(file, 'utf8');
  const m = text.match(/^\s{2}secret:\s*\$\{JWT_SECRET:([^}]+)\}/m);
  if (!m) {
    throw new Error(`未能从 ${file} 解析 jwt.secret`);
  }
  return m[1];
}

function b64url(buf) {
  return Buffer.from(buf).toString('base64url');
}

function mintToken(secret, { userId, email, role }, ttlMs) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({
    userId, email, role, type: 'access', iat: now, exp: now + Math.floor(ttlMs / 1000),
  }));
  const sig = b64url(crypto.createHmac('sha256', secret).update(`${header}.${payload}`).digest());
  return `${header}.${payload}.${sig}`;
}

async function verify(baseUrl, token) {
  const res = await fetch(`${baseUrl}/api/user/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`自检失败 HTTP ${res.status}: ${text.slice(0, 300)}`);
  }
  return text;
}

const args = parseArgs(process.argv.slice(2));
const secret = process.env.JWT_SECRET ?? readDevSecret(args.secretFile);
const token = mintToken(secret, args, args.ttlHours * 3600 * 1000);

try {
  const me = await verify(args.baseUrl, token);
  console.log(`token ok (userId=${args.userId}, ttl=${args.ttlHours}h)`);
  console.log(`me: ${me.slice(0, 300)}`);
  console.log(token);
} catch (e) {
  console.error(e.message);
  process.exit(1);
}
