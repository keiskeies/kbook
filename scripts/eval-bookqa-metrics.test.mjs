import test from 'node:test';
import assert from 'node:assert/strict';
import {
  recallAtK, mrr, ndcgAtK, itemCoverage, citationStats,
  isRefusal, isRefusalAbout, keywordRefusalNearAnyTopic, passK, percentile, mean, classifyError, rankByScore,
  findUndisclosedClaims, contextRecall, stripPlaceholderSections,
} from './eval-bookqa-metrics.mjs';

test('recallAtK counts gold hits in top-k', () => {
  assert.equal(recallAtK([1, 2, 3], [2, 4, 1]), 2 / 3);
  assert.equal(recallAtK([1], [9, 8], 2), 0);
  assert.equal(recallAtK([], [1]), null);
});

test('mrr uses the first gold rank', () => {
  assert.equal(mrr([2], [4, 2, 1]), 0.5);
  assert.equal(mrr([7], [1, 2]), 0);
  assert.equal(mrr([], [1]), null);
});

test('nDCG@k matches hand calculation', () => {
  const value = ndcgAtK([1, 2], [1, 3, 2]);
  const expected = (1 + 1 / Math.log2(4)) / (1 + 1 / Math.log2(3));
  assert.ok(Math.abs(value - expected) < 1e-9);
});

test('itemCoverage is whitespace-insensitive', () => {
  assert.equal(itemCoverage(['甲', '乙'], '甲 和 乙'), 1);
  assert.equal(itemCoverage(['甲', '乙'], '只讲了甲'), 0.5);
  assert.equal(itemCoverage([], '任意'), null);
});

test('占位节不算覆盖率：六节全空只留标题时 coverage 必须是 0', () => {
  const empty = [
    '### 第 1 节 · 第一辑 自然\n\n这一节的原书材料没检索到，我先不展开，避免说错。你可以单独问我这一条。',
    '### 第 2 节 · 第二辑 人类\n\n这一节的原书材料没检索到，我先不展开，避免说错。你可以单独问我这一条。',
  ].join('\n\n');
  assert.equal(itemCoverage(['第一辑 自然', '第二辑 人类'], empty), 0);
  assert.equal(stripPlaceholderSections(empty).includes('第一辑'), false);
});

test('有正文的分节照常计入覆盖率（占位判断不能误伤正常节）', () => {
  const body = [
    '### 第 1 节 · 第一辑 自然\n\n芒格在这一辑里收了《物种起源》等博物学经典，用来讲竞争与适应。',
    '### 第 2 节 · 第二辑 人类\n\n这一节的原书材料没检索到，我先不展开，避免说错。你可以单独问我这一条。',
  ].join('\n\n');
  assert.equal(itemCoverage(['第一辑 自然', '第二辑 人类'], body), 0.5);
  const kept = stripPlaceholderSections(body);
  assert.ok(kept.includes('物种起源'));
  assert.equal(kept.includes('第二辑'), false);
});

test('无分节标题的普通回答原样参与覆盖率', () => {
  assert.equal(itemCoverage(['甲', '乙'], '甲和乙都在正文里'), 1);
});

test('只差结构助词的条目算同一项，真空缺仍算没覆盖', () => {
  assert.equal(itemCoverage(['糟糕的婚姻'], '第 2 节 · 避免糟糕婚姻'), 1);
  assert.equal(itemCoverage(['糟糕的婚姻', '吸毒'], '只讲了糟糕婚姻'), 0.5);
  assert.equal(itemCoverage(['的'], '任何答案'), 0);
});

test('citationStats separates valid and invalid citations', () => {
  const stats = citationStats('事实A[1]，事实B[2,9]', 2);
  assert.equal(stats.total, 3);
  assert.equal(stats.valid, 2);
  assert.equal(stats.invalid, 1);
  assert.equal(stats.validity, 2 / 3);
  assert.deepEqual(stats.citedChunks, [1, 2]);

  const empty = citationStats('没有引用', 3);
  assert.equal(empty.validity, null);
});

test('isRefusal detects prefixed refusal wording', () => {
  assert.equal(isRefusal('这本书里我没找到关于「X」的内容。'), true);
  assert.equal(isRefusal('正常回答'), false);
});

test('passK requires all runs to succeed', () => {
  assert.equal(passK([true, true, true]), true);
  assert.equal(passK([true, false, true]), false);
  assert.equal(passK([]), false);
});

test('percentile and mean', () => {
  assert.equal(percentile([4, 1, 3, 2], 50), 2);
  assert.equal(percentile([], 50), null);
  assert.equal(mean([1, 2, 3]), 2);
  assert.equal(mean([]), null);
});

test('isRefusalAbout catches topic-adjacent denial without keyword table', () => {
  // 实测真拒答的三种同义否定：都不在关键词表里，但语义明确
  assert.equal(
    isRefusalAbout('这本书里没有量子计算，也没有量子纠缠，一个字都没有。', ['量子计算', '量子纠缠'], []),
    true,
  );
  assert.equal(
    isRefusalAbout('量子纠缠？不存在的。芒格的书里装不下这个主题。', ['量子计算', '量子纠缠'], []),
    true,
  );
  assert.equal(
    isRefusalAbout('我翻遍了全书，一个字都没提量子计算。', ['量子计算'], []),
    true,
  );
});

test('isRefusalAbout does not fire on on-topic answers', () => {
  const answer = '芒格在《穷查理宝典》里讲过多元思维模型，还推荐了牛顿的《自然哲学的数学原理》。';
  assert.equal(isRefusalAbout(answer, ['量子计算'], []), false);
  // 「未来」「不同」「无疑」不得被当成否定词
  assert.equal(isRefusalAbout('量子计算在未来会改变投资格局，这与现在不同，无疑值得关注。', ['量子计算'], []), false);
});

test('isRefusalAbout falls back to keyword patterns when no topics given', () => {
  assert.equal(isRefusalAbout('材料不足，无法回答该问题', [], []), false);
  assert.equal(isRefusalAbout('材料不足，无法回答该问题', [], ['材料不足']), true);
});

test('keywordRefusalNearAnyTopic ignores refusal wording far from the asked items', () => {
  // 实测假阳性：25 条全写出来了，尾部一句「这部分材料不足」把整条判成拒答
  const answer =
    '1. 不用就忘倾向\n2. 化学物质错误影响倾向\n\n' +
    '以上就是全部条目。其中第 11、18、19、20 条在参考内容中只出现在清单里，' +
    '我按"这部分材料不足"如实标注了。';
  assert.equal(
    keywordRefusalNearAnyTopic(answer, ['不用就忘倾向', '化学物质错误影响倾向'], ['材料不足']),
    false,
  );
  // 同一句紧贴读者所问的条目时仍要判成拒答（不能为了消假阳性把真拒答放宽）
  assert.equal(
    keywordRefusalNearAnyTopic('不用就忘倾向这条材料不足，无法展开。', ['不用就忘倾向'], ['材料不足']),
    true,
  );
  // 没有锚点可定位时退回原语义
  assert.equal(keywordRefusalNearAnyTopic('材料不足，无法回答该问题', [], ['材料不足']), true);
});

test('classifyError separates auth failures from other failures', () => {
  // token 中途过期是这个分类存在的唯一理由：它会把所有指标压成 0，
  // 但报告本身是「跑挂了」而不是「质量差」。
  assert.equal(classifyError('HTTP 401'), 'auth');
  assert.equal(classifyError('HTTP 403'), 'auth');
  assert.equal(classifyError('The operation was aborted'), 'timeout');
  assert.equal(classifyError('request timeout'), 'timeout');
  assert.equal(classifyError('fetch failed'), 'network');
  assert.equal(classifyError('ECONNREFUSED 127.0.0.1:18181'), 'network');
  assert.equal(classifyError('pre_turn: boom'), 'other');
  assert.equal(classifyError(undefined), 'other');
});

test('merged spans count as retrieved chunks', () => {
  // 12+13 合并成一行依据时，两块都算召回
  assert.equal(recallAtK([12], [[12, 13]]), 1);
  assert.equal(recallAtK([12, 13], [[12, 13]]), 1);
  assert.equal(recallAtK([11], [[12, 13]]), 0);
  assert.equal(mrr([12], [[12, 13]]), 1);
  assert.equal(mrr([11], [[9, 10], [12, 13]]), 0);
  assert.equal(ndcgAtK([12], [[12, 13]]) > 0, true);
});

test('recall counts distinct gold chunks, not duplicate rows', () => {
  // 同一块被召回两次不应把召回率顶到 1 以上
  assert.equal(recallAtK([1], [1, 1]), 1);
  assert.equal(recallAtK([1, 2], [1, 1]), 0.5);
});

test('contextRecall covers gold anywhere in the retrieved set', () => {
  // gold 排在 top-10 之外但进入了上下文：召回口径算命中，排序口径不算
  const retrieved = Array.from({ length: 20 }, (_, i) => i + 1);
  assert.equal(recallAtK([15], retrieved, 10), 0);
  assert.equal(contextRecall([15], retrieved), 1);
  assert.equal(contextRecall([15, 21], retrieved), 0.5);
  assert.equal(contextRecall([], retrieved), null);
  // 合并跨度同样算覆盖
  assert.equal(contextRecall([13], [[12, 13]]), 1);
  assert.equal(contextRecall([14], [[12, 13]]), 0);
});

test('rankByScore orders evidence like the prompt does', () => {
  // /evidence 按 chunkIndex 升序返回；上下文按 score 降序拼，排序指标要用后者
  const evidence = [
    { chunkIndex: 1, score: 0.6 },
    { chunkIndex: 9, score: 0.9 },
    { chunkIndex: 5, score: 0.75 },
  ];
  assert.deepEqual(rankByScore(evidence).map(e => e.chunkIndex), [9, 5, 1]);
  // 同分时保留原顺序（列表型路径所有片段都是 1.0）
  const flat = [{ chunkIndex: 3, score: 1 }, { chunkIndex: 1, score: 1 }];
  assert.deepEqual(rankByScore(flat).map(e => e.chunkIndex), [3, 1]);
  assert.deepEqual(rankByScore([]), []);
});

test('findUndisclosedClaims ignores rhetoric and self-disclosed extrapolations', () => {
  // 真实踩到的两种假阳性
  assert.deepEqual(
    findUndisclosedClaims('哈哈，这个问题问得让我有点想给芒格发个短信："老查理，量子比特的事儿你管不管？"答案是：这本书没有涉及量子计算。',
      ['量子比特']),
    [],
  );
  assert.deepEqual(
    findUndisclosedClaims('书中确实没提郑和下西洋。不过这是我自己的引申，不是书里的内容：动机（政治声望+朝贡体系）。',
      ['朝贡']),
    [],
  );
  // 真的把禁用内容说成书里的 → 违规
  assert.deepEqual(
    findUndisclosedClaims('书中明确讲到郑和的朝贡体系与宝船规模。', ['朝贡', '宝船']),
    ['朝贡', '宝船'],
  );
  assert.deepEqual(findUndisclosedClaims('没有任何相关内容', ['朝贡']), []);
});

test('findUndisclosedClaims treats negated mentions as denial, not assertion', () => {
  // 真实踩到的假阳性：回答在"否认"内容，却因为命中关键词被判成"把禁用内容说成书里的"
  assert.deepEqual(
    findUndisclosedClaims('他引用孔子，引的是修身逻辑，跟宝船队没半点关系。', ['宝船']),
    [],
  );
  assert.deepEqual(
    findUndisclosedClaims('这本书里没有关于宝船的任何记载。', ['宝船']),
    [],
  );
  // 否定词被"不只/不仅"反转时，它加强断言而不是免责 → 仍然算违规
  assert.deepEqual(
    findUndisclosedClaims('书里不只提到宝船，还详细写了宝船队的规模。', ['宝船']),
    ['宝船'],
  );
  // 纯断言不受影响
  assert.deepEqual(
    findUndisclosedClaims('书中讲到芒格评价了宝船的火力。', ['宝船']),
    ['宝船'],
  );
});
