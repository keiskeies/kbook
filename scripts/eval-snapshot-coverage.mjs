#!/usr/bin/env node
/**
 * 快照回放分析：用已落库的检索快照给黄金集打分，不调用模型。
 *
 * 用途：
 *   1. 把「检索召回」与「排序召回」分开看——快照全量 = 上下文召回（gold 有没有进材料），
 *      快照按 score 降序的前 k 条 = 排序召回（gold 排第几）。检索预算大时两者会差很远。
 *   2. 回答里引用了哪个片段编号 → 该片段是不是 gold（引用是否落在正确依据上）。
 *
 * 快照的写入顺序（ai_retrieval_snapshots.id 升序）就是进入提示词的顺序，
 * 也是回答里 [N] 引用编号的来源；/evidence 接口按 chunkIndex 升序返回，不能直接当顺序用。
 *
 * 用法：
 *   node scripts/eval-snapshot-coverage.mjs \
 *     --dataset docs/eval/golden-set.real.jsonl --since 2026-10-26T17:30:00 \
 *     --out docs/eval/snapshot-coverage-24689.json
 *
 * 凭据从 backend/src/main/resources/application-dev.yml 读取（开发用脚本，不落盘密码）。
 */
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { contextRecall, recallAtK } from './eval-bookqa-metrics.mjs';

const DEV_YML = 'backend/src/main/resources/application-dev.yml';

function parseArgs(argv) {
  const args = {
    dataset: 'docs/eval/golden-set.real.jsonl',
    since: null,
    until: null,
    bookId: null,
    userId: 1,
    k: 10,
    out: '',
  };
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i + 1];
    switch (argv[i]) {
      case '--dataset': args.dataset = value; i++; break;
      case '--since': args.since = value; i++; break;
      case '--until': args.until = value; i++; break;
      case '--book-id': args.bookId = Number(value); i++; break;
      case '--user-id': args.userId = Number(value); i++; break;
      case '--k': args.k = Number(value); i++; break;
      case '--out': args.out = value; i++; break;
      default: break;
    }
  }
  return args;
}

/** 从 application-dev.yml 读数据库连接信息（datasource 段，避免误取 qdrant 的端口）。 */
function readDbConfig() {
  const lines = fs.readFileSync(DEV_YML, 'utf8').split(/\r?\n/);
  const start = lines.findIndex(l => /^  datasource:\s*$/.test(l));
  if (start < 0) throw new Error('datasource section not found in ' + DEV_YML);
  const block = [];
  for (let i = start + 1; i < lines.length; i++) {
    if (/^  \S/.test(lines[i])) break;
    block.push(lines[i]);
  }
  const pick = (key, fallback) => {
    const m = block.join('\n').match(new RegExp(`${key}:\\s*\\$\\{[^:}]+:([^}]+)\\}`))
      ?? block.join('\n').match(new RegExp(`${key}:\\s*(\\S+)`));
    return m ? m[1].trim() : fallback;
  };
  const url = pick('url', '');
  const urlMatch = url.match(/jdbc:mysql:\/\/([^:/?]+):(\d+)\/([^?}]+)/);
  return {
    host: urlMatch ? urlMatch[1] : 'localhost',
    port: urlMatch ? urlMatch[2] : '3306',
    user: pick('username', 'root'),
    password: pick('password', ''),
    db: urlMatch ? urlMatch[3] : 'kbook-dev',
  };
}

/** 跑一条 SQL，返回按 \t 分隔的行（值里的制表符/换行已剔除）。 */
function query(db, sql) {
  const out = execFileSync(
    mysqlBin(),
    [
      `-h${db.host}`, `-P${db.port}`, `-u${db.user}`,
      `--password=${db.password}`, '--batch', '--raw', '--skip-column-names', db.db, '-e', sql,
    ],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, env: { ...process.env, MYSQL_PWD: db.password } },
  );
  return out.split('\n').filter(l => l.trim().length > 0).map(l => l.split('\t'));
}

/**
 * 正文列的 SQL 表达式：把换行/制表符换成不会破坏「按行切分」的单行形式。
 * <p>
 * 不做这一步的话，mysql --batch 的输出里一段多行正文会被拆成多行，
 * 解析器只取到第一行——回答正文里的引用编号全部丢失，指标被系统性低估。
 * 换行统一编码成两字符的 {@code \n}（反斜杠 + n），读回时还原。
 */
function contentExpr(column) {
  return `REPLACE(REPLACE(REPLACE(${column}, CHAR(9), ' '), CHAR(10), CONCAT(CHAR(92), 'n')), CHAR(13), '')`;
}

/** 还原 contentExpr 编码的换行。 */
function decodeContent(text) {
  return String(text ?? '').replace(/\\n/g, '\n');
}

/** 归一化空白，用于问题文本匹配（DB 里可能带换行/多余空格）。 */
function normalizeText(text) {
  return String(text ?? '').replace(/\s+/g, ' ').trim();
}

/** mysql 客户端路径：PATH 里没有时退回 MySQL 8.4 的默认安装位置。 */
function mysqlBin() {
  if (process.env.MYSQL_BIN) return process.env.MYSQL_BIN;
  const candidates = [
    'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe',
    'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe',
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return 'mysql';
}

function loadCases(path) {
  return fs.readFileSync(path, 'utf8').split(/\r?\n/)
    .filter(l => l.trim() && !l.trim().startsWith('#'))
    .map(l => JSON.parse(l));
}

/** 回答里出现的 [N] 引用编号集合（兜底路径；优先用 citation_json）。 */
function citationNumbers(answer) {
  const out = new Set();
  for (const m of String(answer ?? '').matchAll(/\[\[?\s*([0-9][0-9,\s]*)\s*\]\]?/g)) {
    for (const token of m[1].split(/[,\s]+/)) {
      if (token) out.add(Number(token));
    }
  }
  return out;
}

/** citation_json 里 CitationValidator 校验通过的编号集合。 */
function citationNumbersFromJson(citationJson) {
  const out = new Set();
  if (!citationJson) return out;
  try {
    const parsed = JSON.parse(citationJson);
    for (const n of parsed.valid ?? []) out.add(Number(n));
  } catch {
    // citation_json 不是合法 JSON 时退化为空集合
  }
  return out;
}

/** citation_json 记录的被剔除的非法编号个数。 */
function invalidCitationCount(citationJson) {
  if (!citationJson) return null;
  try {
    return Number(JSON.parse(citationJson).invalid ?? 0);
  } catch {
    return null;
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const cases = loadCases(args.dataset);
  const byQuestion = new Map(cases.map(c => [normalizeText(c.question), c]));
  const db = readDbConfig();

  const bookId = args.bookId ?? cases[0]?.book_id;
  if (!bookId) throw new Error('book id unknown: pass --book-id');

  const conditions = [
    'book_id = ' + Number(bookId),
    'user_id = ' + Number(args.userId),
  ];
  if (args.since) conditions.push(`created_at >= '${args.since}'`);
  if (args.until) conditions.push(`created_at <= '${args.until}'`);

  // 每个会话的用户提问（按 id 升序）→ 用来匹配黄金集用例
  const userRows = query(db, `SELECT session_id, id, ${contentExpr('content')} FROM ai_conversations
      WHERE ${conditions.join(' AND ')} AND role = 'user' ORDER BY id ASC`);
  const userBySession = new Map();
  for (const [sessionId, id, content] of userRows) {
    if (!userBySession.has(sessionId)) userBySession.set(sessionId, []);
    userBySession.get(sessionId).push({ id: Number(id), content: decodeContent(content) });
  }

  const convoRows = query(db, `SELECT id, session_id, citation_json, ${contentExpr('content')}, created_at
      FROM ai_conversations WHERE ${conditions.join(' AND ')} AND role = 'assistant' ORDER BY id ASC`);

  const snapshotCache = new Map();
  function snapshotsFor(conversationId) {
    if (!snapshotCache.has(conversationId)) {
      const rows = query(db, `SELECT chunk_index, COALESCE(chunk_end_index, chunk_index), COALESCE(score, 0)
          FROM ai_retrieval_snapshots WHERE conversation_id = ${Number(conversationId)} ORDER BY id ASC`);
      snapshotCache.set(conversationId, rows.map(r => [Number(r[0]), Number(r[1]), Number(r[2])]));
    }
    return snapshotCache.get(conversationId);
  }

  /** 取「该回答之前最近的一条用户提问」——多轮会话里不能用会话的最后一条提问。 */
  function precedingQuestion(sessionId, assistantId) {
    const list = userBySession.get(sessionId) ?? [];
    let chosen = null;
    for (const u of list) {
      if (u.id < assistantId) chosen = u;
    }
    return chosen?.content ?? '';
  }

  const results = [];
  for (const [conversationId, sessionId, citationJson, rawContent, createdAt] of convoRows) {
    const content = decodeContent(rawContent);
    const question = precedingQuestion(sessionId, Number(conversationId));
    const testCase = byQuestion.get(normalizeText(question));
    if (!testCase) continue;

    const snaps = snapshotsFor(conversationId);
    if (snaps.length === 0) continue;

    const spans = snaps.map(s => [s[0], s[1]]);
    const ranked = [...snaps]
      .sort((a, b) => b[2] - a[2])
      .map(s => [s[0], s[1]]);

    // 引用编号：片段 N 对应提示词里的第 N 段 → 快照写入顺序
    // 优先用 citation_json（CitationValidator 校验后的合法编号），正文正则只作兜底。
    const cited = citationNumbersFromJson(citationJson);
    const citationSource = cited.size > 0 ? 'citation_json' : 'answer_text';
    for (const n of citationNumbers(content)) cited.add(n);
    const goldCited = [...cited].some(n => {
      const row = snaps[n - 1];
      if (!row) return false;
      return (testCase.gold_chunks ?? []).some(g => g >= row[0] && g <= row[1]);
    });

    results.push({
      conversationId: Number(conversationId),
      createdAt,
      caseId: testCase.id,
      type: testCase.type,
      evidenceCount: snaps.length,
      ctxRecall: contextRecall(testCase.gold_chunks, spans),
      rankRecall: recallAtK(testCase.gold_chunks, ranked, args.k),
      goldCited: testCase.gold_chunks?.length ? goldCited : null,
      citations: [...cited].sort((a, b) => a - b),
      citationSource,
      citationInvalid: invalidCitationCount(citationJson),
    });
  }

  const byCase = new Map();
  for (const r of results) {
    if (!byCase.has(r.caseId)) byCase.set(r.caseId, []);
    byCase.get(r.caseId).push(r);
  }

  const rows = [...byCase.entries()].map(([caseId, list]) => {
    const type = list[0].type;
    const avg = key => list.reduce((s, r) => s + (r[key] ?? 0), 0) / list.length;
    const eligible = list.filter(r => r.goldCited !== null).length;
    return {
      caseId,
      type,
      runs: list.length,
      evidenceCountMin: Math.min(...list.map(r => r.evidenceCount)),
      evidenceCountMax: Math.max(...list.map(r => r.evidenceCount)),
      ctxRecallMean: avg('ctxRecall'),
      rankRecallMean: avg('rankRecall'),
      goldCitedRuns: list.filter(r => r.goldCited === true).length,
      goldCitedEligible: eligible,
    };
  });

  const summary = {
    generatedAt: new Date().toISOString(),
    dataset: args.dataset,
    bookId,
    window: { since: args.since, until: args.until },
    runs: results.length,
    ctxRecallMean: results.length
      ? results.reduce((s, r) => s + (r.ctxRecall ?? 0), 0) / results.length : null,
    rankRecallMean: results.length
      ? results.reduce((s, r) => s + (r.rankRecall ?? 0), 0) / results.length : null,
    goldCitedRuns: results.filter(r => r.goldCited === true).length,
    goldCitedEligible: results.filter(r => r.goldCited !== null).length,
    cases: rows,
  };

  console.log(`快照回放：${summary.runs} 次回答，命中用例 ${rows.length} 个`);
  console.log(`上下文召回均值 ${summary.ctxRecallMean?.toFixed(3) ?? '-'} / 排序召回均值 ${summary.rankRecallMean?.toFixed(3) ?? '-'}`);
  console.log(`引用命中 gold：${summary.goldCitedRuns}/${summary.goldCitedEligible}`);
  console.log('');
  console.log('| case | type | runs | 依据行数 | ctxRecall | rankRecall | 引用命中gold |');
  console.log('|---|---|---|---|---|---|---|');
  for (const r of rows) {
    const cited = r.goldCitedEligible > 0 ? `${r.goldCitedRuns}/${r.goldCitedEligible}` : '-';
    console.log(`| ${r.caseId} | ${r.type} | ${r.runs} | ${r.evidenceCountMin}~${r.evidenceCountMax} | ${r.ctxRecallMean.toFixed(3)} | ${r.rankRecallMean.toFixed(3)} | ${cited} |`);
  }

  if (args.out) {
    fs.writeFileSync(args.out, JSON.stringify({ summary, results }, null, 2));
    console.log(`\nwritten to ${args.out}`);
  }
}

main();
