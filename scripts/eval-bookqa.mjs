#!/usr/bin/env node
/**
 * 图书问答离线评测 runner。
 *
 * 用法：
 *   KBOOK_EVAL_TOKEN=<jwt> node scripts/eval-bookqa.mjs \
 *     --dataset docs/eval/golden-set.sample.jsonl --runs 3
 *
 * 依赖：后端已启动（默认 http://localhost:8181）、Qdrant 与模型可用。
 * 取数：SSE 问答 → history 找到 assistant 行 → evidence 接口取检索依据。
 * 指标：Recall@k / MRR / nDCG@k / 条目覆盖率 / 拒答正确率 / 引用合法率 / pass^k / 延迟。
 *
 * 数据集字段：除 sample 里列出的字段外，支持 pre_turns（多轮用例的前置问题数组）——
 * 前置问题与正式问题在同一个会话内依次发送，sessionId 由首轮响应自动带出。
 */
import fs from 'node:fs';
import {
  recallAtK, mrr, ndcgAtK, itemCoverage, citationStats,
  isRefusalAbout, keywordRefusalNearAnyTopic, passK, percentile, mean, classifyError, rankByScore,
  findUndisclosedClaims, contextRecall,
} from './eval-bookqa-metrics.mjs';

function parseArgs(argv) {
  const args = {
    baseUrl: 'http://localhost:8181',
    token: process.env.KBOOK_EVAL_TOKEN ?? '',
    dataset: 'docs/eval/golden-set.sample.jsonl',
    runs: 1,
    k: 10,
    out: 'eval-report.json',
    timeoutMs: 180000,
  };
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i];
    const value = argv[i + 1];
    switch (key) {
      case '--base-url': args.baseUrl = value; i++; break;
      case '--token': args.token = value; i++; break;
      case '--dataset': args.dataset = value; i++; break;
      case '--runs': args.runs = Number(value); i++; break;
      case '--k': args.k = Number(value); i++; break;
      case '--out': args.out = value; i++; break;
      case '--timeout-ms': args.timeoutMs = Number(value); i++; break;
      default: break;
    }
  }
  return args;
}

function loadCases(path) {
  const raw = fs.readFileSync(path, 'utf8');
  return raw.split(/\r?\n/)
    .map((line, i) => ({ line, i }))
    .filter(({ line }) => line.trim() && !line.trim().startsWith('#'))
    .map(({ line, i }) => {
      try {
        return JSON.parse(line);
      } catch (e) {
        throw new Error(`dataset line ${i + 1} is not valid JSON: ${e.message}`);
      }
    });
}

function parseEvent(raw) {
  let event = 'message';
  const data = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if (data.length === 0 && event === 'message') return null;
  return { event, data: data.join('\n') };
}

async function postSse(url, body, token, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const started = performance.now();
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let content = '';
    let sessionId = '';
    let error = null;
    let done = false;
    let firstTokenMs = null;

    while (true) {
      const { value, done: streamDone } = await reader.read();
      if (value) buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, '\n');
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const event = parseEvent(raw);
        if (!event) continue;
        if (event.event === 'session_id') {
          sessionId = event.data;
        } else if (event.event === 'message') {
          if (firstTokenMs === null) firstTokenMs = performance.now() - started;
          content += event.data;
        } else if (event.event === 'replace') {
          content = event.data;
        } else if (event.event === 'error') {
          error = event.data;
        } else if (event.event === 'done') {
          done = true;
        }
      }
      if (streamDone) break;
    }
    return {
      content, sessionId, error, done, firstTokenMs,
      totalMs: Math.round(performance.now() - started),
    };
  } finally {
    clearTimeout(timer);
  }
}

async function getJson(url, token) {
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== 0) throw new Error(json.message ?? `code=${json.code}`);
  return json.data;
}

async function runOnce(baseUrl, token, testCase, k, timeoutMs) {
  const url = `${baseUrl}/api/books/${testCase.book_id}/chat/stream`;
  let sessionId = testCase.session_id ?? null;

  // 多轮用例：先在同一会话里跑前置问题，再问正式问题。
  // 不依赖外部预置的 sessionId，因此数据集本身可复现。
  for (const pre of testCase.pre_turns ?? []) {
    const preSse = await postSse(
      url,
      { message: pre, sessionId, manual: true },
      token,
      timeoutMs,
    );
    sessionId = preSse.sessionId || sessionId;
    if (preSse.error) {
      return {
        success: false, recall: null, mrr: null, ndcg: null, coverage: null,
        refusal: false, citationValidity: null, mustNotClaimHits: [],
        evidenceCount: 0, latencyMs: preSse.totalMs, firstTokenMs: preSse.firstTokenMs,
        error: `pre_turn: ${preSse.error}`, answer: '',
        errorKind: classifyError(preSse.error),
      };
    }
  }

  const sse = await postSse(
    url,
    { message: testCase.question, sessionId, manual: true },
    token,
    timeoutMs,
  );

  let evidence = [];
  let conversationId = null;
  if (sse.sessionId) {
    try {
      const history = await getJson(
        `${baseUrl}/api/books/${testCase.book_id}/chat/history?sessionId=${encodeURIComponent(sse.sessionId)}`,
        token,
      );
      const assistant = [...(history ?? [])].reverse().find(m => m.role === 'assistant');
      if (assistant?.id) {
        conversationId = assistant.id;
        evidence = await getJson(
          `${baseUrl}/api/books/${testCase.book_id}/chat/${assistant.id}/evidence`,
          token,
        ) ?? [];
      }
    } catch (e) {
      sse.error = sse.error ?? `evidence: ${e.message}`;
    }
  }

  // 依据接口按 chunkIndex 升序返回，但进入提示词的顺序是按 score 降序（合并后重排 + 预算填充）。
  // 排序指标必须按提示词里的实际顺序算，否则 Recall@10 变成「gold 是不是全书最靠前的十块」。
  const ranked = rankByScore(evidence);
  // 依据行可能是相邻 chunk 合并出来的（chunk_index..chunk_end_index），
  // 保留跨度再算召回，否则被合并进去的前一块会被算成没召回。
  const retrieved = ranked.flatMap(e => {
    if (!Number.isInteger(e.chunkIndex)) return [];
    const end = Number.isInteger(e.chunkEndIndex) && e.chunkEndIndex >= e.chunkIndex
      ? e.chunkEndIndex
      : e.chunkIndex;
    return [[e.chunkIndex, end]];
  });
  const recall = recallAtK(testCase.gold_chunks, retrieved, k);
  const ctxRecall = contextRecall(testCase.gold_chunks, retrieved);
  const reciprocalRank = mrr(testCase.gold_chunks, retrieved, k);
  const ndcg = ndcgAtK(testCase.gold_chunks, retrieved, k);
  const coverage = itemCoverage(testCase.gold_items, sse.content);
  const citations = citationStats(sse.content, evidence.length);
  // 拒答判定分两条口径：
  //  - 拒答用例：主题邻域否定 + 关键词表，口径不变（放宽会把真拒答也漏掉）；
  //  - 切题用例：关键词表要贴着读者所问的东西（gold_items）才算拒答，
  //    否则答案里一句"这部分材料不足"就会把整条写全的答案判成拒答。
  const refusal = testCase.must_refuse
    ? isRefusalAbout(sse.content, testCase.refusal_topics ?? [], testCase.refusal_patterns)
    : keywordRefusalNearAnyTopic(
        sse.content,
        [...(testCase.gold_items ?? []), ...(testCase.refusal_topics ?? [])],
        testCase.refusal_patterns,
      );
  const mustNotClaimHits = findUndisclosedClaims(sse.content, testCase.must_not_claim);
  // 最高相似度：用于标定 qdrant.absolute-min-score（列表型用 rank 序数分，不可比）
  const scores = evidence.map(e => e.score).filter(v => typeof v === 'number');
  const topScore = scores.length ? Math.max(...scores) : null;

  let success;
  let successContext;
  if (testCase.must_refuse) {
    // 拒答用例同样校验 must_not_claim：否则「先说没找到、再补一段编造」会被判成通过
    success = refusal && mustNotClaimHits.length === 0;
    successContext = success;
  } else {
    success = Boolean(sse.content) && !sse.error && mustNotClaimHits.length === 0;
    if (recall !== null) success = success && recall >= (testCase.min_recall ?? 0.5);
    if (coverage !== null) success = success && coverage >= (testCase.min_coverage ?? 1.0);
    if (citations.validity !== null) {
      success = success && citations.validity >= (testCase.min_citation_validity ?? 0.99);
    }
    // 上下文口径：gold 只要进了这一次回答的材料就算召回，不看它排在第几。
    // 检索预算大（整本书进上下文）时，top-k 召回低不代表答案拿不到依据。
    successContext = Boolean(sse.content) && !sse.error && mustNotClaimHits.length === 0;
    if (ctxRecall !== null) successContext = successContext && ctxRecall >= (testCase.min_recall ?? 0.5);
    if (coverage !== null) successContext = successContext && coverage >= (testCase.min_coverage ?? 1.0);
    if (citations.validity !== null) {
      successContext = successContext && citations.validity >= (testCase.min_citation_validity ?? 0.99);
    }
  }

  return {
    success,
    successContext,
    recall, ctxRecall, mrr: reciprocalRank, ndcg, coverage, refusal,
    citationValidity: citations.validity,
    mustNotClaimHits,
    evidenceCount: evidence.length,
    retrievedChunks: retrieved,
    conversationId,
    topScore,
    latencyMs: sse.totalMs,
    firstTokenMs: sse.firstTokenMs,
    error: sse.error,
    errorKind: sse.error ? classifyError(sse.error) : null,
    answer: sse.content,
  };
}

function aggregate(results) {
  const allRuns = results.flatMap(r => r.runs);
  const mustRefuse = results.filter(r => r.case.must_refuse).flatMap(r => r.runs);
  const answerable = results.filter(r => !r.case.must_refuse).flatMap(r => r.runs);
  // topScore 只在「向量相似度分」的用例上可比；列表型的分是 rank 序数分，排除
  const scoredRuns = results
    .filter(r => r.case.type !== 'list' && r.case.type !== 'multiturn')
    .flatMap(r => r.runs)
    .map(r => r.topScore)
    .filter(v => Number.isFinite(v));
  const refuseRuns = results
    .filter(r => r.case.must_refuse)
    .flatMap(r => r.runs)
    .map(r => r.topScore)
    .filter(v => Number.isFinite(v));
  const onTopicRuns = results
    .filter(r => !r.case.must_refuse && r.case.type !== 'list' && r.case.type !== 'multiturn')
    .flatMap(r => r.runs)
    .map(r => r.topScore)
    .filter(v => Number.isFinite(v));

  // 失败归因：全部 run 都报错的用例说明这次运行没跑成，不能当成「质量不达标」
  const errorRuns = allRuns.filter(r => r.error);
  const invalidCases = results.filter(r => r.runs.every(x => x.error));
  const errorKinds = {};
  for (const r of errorRuns) {
    const kind = r.errorKind ?? classifyError(r.error);
    errorKinds[kind] = (errorKinds[kind] ?? 0) + 1;
  }

  return {
    valid: invalidCases.length === 0,
    errorRuns: errorRuns.length,
    errorKinds,
    invalidCaseIds: invalidCases.map(r => r.case.id),
    cases: results.length,
    passedCases: results.filter(r => r.passK).length,
    passKRate: results.length ? results.filter(r => r.passK).length / results.length : null,
    passedCasesContext: results.filter(r => r.passKContext).length,
    passKContextRate: results.length ? results.filter(r => r.passKContext).length / results.length : null,
    recallMean: mean(allRuns.map(r => r.recall)),
    ctxRecallMean: mean(allRuns.map(r => r.ctxRecall)),
    ndcgMean: mean(allRuns.map(r => r.ndcg)),
    coverageMean: mean(allRuns.map(r => r.coverage)),
    refusalAccuracy: mustRefuse.length
      ? mustRefuse.filter(r => r.refusal).length / mustRefuse.length : null,
    falseRefusalRate: answerable.length
      ? answerable.filter(r => r.refusal).length / answerable.length : null,
    citationValidityMean: mean(allRuns.map(r => r.citationValidity)),
    latencyP50: percentile(allRuns.map(r => r.latencyMs), 50),
    latencyP95: percentile(allRuns.map(r => r.latencyMs), 95),
    topScoreMean: mean(scoredRuns),
    topScoreMin: scoredRuns.length ? Math.min(...scoredRuns) : null,
    topScoreMax: scoredRuns.length ? Math.max(...scoredRuns) : null,
    topScoreOffTopicMean: mean(refuseRuns),
    topScoreOnTopicMean: mean(onTopicRuns),
  };
}

function printMarkdown(report) {
  const a = report.aggregate;
  const fmt = v => v === null || v === undefined ? '-' : Number(v).toFixed(3);
  if (a.valid === false) {
    console.log('## Book QA Eval Report');
    console.log('');
    console.log('> ⚠️ **报告无效**：以下用例的所有 run 都执行失败，指标不能作为质量基线。');
    console.log(`> 失败用例: ${a.invalidCaseIds.join(', ')}`);
    console.log(`> 错误分布: ${JSON.stringify(a.errorKinds)}`);
    console.log('> 常见原因: access token 过期（用 scripts/eval-dev-token.mjs 重签长时效 token）、后端未启动、模型不可用。');
    console.log('');
  }
  console.log('## Book QA Eval Report');
  console.log('');
  console.log(`- cases: ${a.cases}, passed: ${a.passedCases}, pass^k: ${a.passKRate === null ? 'n/a' : a.passKRate.toFixed(3)}`);
  console.log(`- pass^k (上下文口径): ${a.passKContextRate === null ? 'n/a' : a.passKContextRate.toFixed(3)}`);
  console.log(`- Recall@k: ${a.recallMean === null ? 'n/a' : a.recallMean.toFixed(3)}`);
  console.log(`- 上下文召回: ${a.ctxRecallMean === null ? 'n/a' : a.ctxRecallMean.toFixed(3)}`);
  console.log(`- nDCG@k: ${a.ndcgMean === null ? 'n/a' : a.ndcgMean.toFixed(3)}`);
  console.log(`- item coverage: ${a.coverageMean === null ? 'n/a' : a.coverageMean.toFixed(3)}`);
  console.log(`- refusal accuracy: ${a.refusalAccuracy === null ? 'n/a' : a.refusalAccuracy.toFixed(3)}`);
  console.log(`- false refusal: ${a.falseRefusalRate === null ? 'n/a' : a.falseRefusalRate.toFixed(3)}`);
  console.log(`- citation validity: ${a.citationValidityMean === null ? 'n/a' : a.citationValidityMean.toFixed(3)}`);
  console.log(`- latency P50/P95: ${a.latencyP50 ?? 'n/a'}ms / ${a.latencyP95 ?? 'n/a'}ms`);
  console.log(`- topScore (向量分用例) mean/min/max: ${fmt(a.topScoreMean)} / ${fmt(a.topScoreMin)} / ${fmt(a.topScoreMax)}`);
  console.log(`- topScore 切题均值 / 离题均值: ${fmt(a.topScoreOnTopicMean)} / ${fmt(a.topScoreOffTopicMean)}`);
  console.log('');
  console.log('| case | pass^k | pass^k(上下文) | recall | ctxRecall | coverage | refusal | cite | topScore | latency |');
  console.log('|---|---|---|---|---|---|---|---|---|---|');
  for (const r of report.cases) {
    const mark = r.invalid ? 'ERR' : (r.passK ? '✅' : '❌');
    const markCtx = r.invalid ? 'ERR' : (r.passKContext ? '✅' : '❌');
    console.log(`| ${r.case.id} | ${mark} | ${markCtx} | ${fmt(r.recall)} | ${fmt(r.ctxRecall)} | ${fmt(r.coverage)} | ${r.refusal ? 'yes' : 'no'} | ${fmt(r.citationValidity)} | ${fmt(r.topScore)} | ${r.latencyMs}ms |`);
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.token) {
    console.error('missing token: set KBOOK_EVAL_TOKEN or pass --token');
    process.exit(2);
  }
  if (!Number.isInteger(args.runs) || args.runs < 1) {
    console.error('--runs must be a positive integer');
    process.exit(2);
  }

  const allCases = loadCases(args.dataset);
  const runnable = allCases.filter(c => Number.isInteger(c.book_id) && c.book_id > 0);
  const skipped = allCases.length - runnable.length;
  if (skipped > 0) {
    console.error(`[eval] skipped ${skipped} template case(s) with book_id<=0`);
  }

  const results = [];
  for (const testCase of runnable) {
    const runs = [];
    for (let i = 0; i < args.runs; i++) {
      try {
        runs.push(await runOnce(args.baseUrl, args.token, testCase, args.k, args.timeoutMs));
      } catch (e) {
        runs.push({
          success: false, recall: null, mrr: null, ndcg: null, coverage: null,
          refusal: false, citationValidity: null, mustNotClaimHits: [],
          evidenceCount: 0, topScore: null, latencyMs: null, firstTokenMs: null,
          error: e.message, answer: '',
        });
      }
    }
    const last = runs[runs.length - 1];
    const invalid = runs.every(r => r.error);
    results.push({
      case: testCase,
      runs,
      invalid,
      passK: passK(runs.map(r => r.success)),
      passKContext: passK(runs.map(r => r.successContext)),
      recall: last.recall,
      ctxRecall: last.ctxRecall,
      coverage: last.coverage,
      refusal: last.refusal,
      citationValidity: last.citationValidity,
      topScore: last.topScore,
      latencyMs: last.latencyMs,
    });
    console.log(`[eval] ${testCase.id}: ${last ? (runs.every(r => r.success) ? 'pass' : 'fail') : 'no-run'}`);
  }

  const report = {
    generatedAt: new Date().toISOString(),
    baseUrl: args.baseUrl,
    dataset: args.dataset,
    runs: args.runs,
    k: args.k,
    skipped,
    aggregate: aggregate(results),
    cases: results,
  };
  fs.writeFileSync(args.out, JSON.stringify(report, null, 2));
  printMarkdown(report);
  console.log(`\nreport written to ${args.out}`);
  if (report.aggregate.valid === false) {
    console.error('[eval] 报告无效：有用例全部执行失败，先修环境再重跑。');
    process.exit(3);
  }
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
