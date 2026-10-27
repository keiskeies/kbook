/**
 * 图书问答离线评测的纯指标函数。
 * <p>不依赖网络/数据库，便于用 `node --test` 单测；runner 负责取数和调用。
 * <p>
 * 检索结果里的每一项可以是：
 * <ul>
 *   <li>数字 n —— 该片段覆盖 chunk n；</li>
 *   <li>数组 [start, end] —— 该片段是相邻 chunk 合并出来的，覆盖 start..end。</li>
 * </ul>
 * 后者是必需的：合并片段在依据里只占一行，不展开跨度会把被合并进去的前一块
 * 算成「没召回」。
 */

/** 该项检索结果是否覆盖指定的 chunk。 */
function covers(entry, chunkIndex) {
  if (Array.isArray(entry)) {
    if (entry.length === 0) return false;
    const start = entry[0];
    const end = entry.length > 1 ? entry[1] : entry[0];
    return Number.isInteger(start) && Number.isInteger(end)
      && chunkIndex >= start && chunkIndex <= end;
  }
  return entry === chunkIndex;
}

/** 该项检索结果是否命中了 gold 中的任意一块。 */
function hitsGold(entry, gold) {
  for (const g of gold) {
    if (covers(entry, g)) return true;
  }
  return false;
}

export function recallAtK(goldChunks, retrievedChunks, k = 10) {
  const gold = new Set((goldChunks ?? []).filter(n => Number.isInteger(n)));
  if (gold.size === 0) return null;
  const top = (retrievedChunks ?? []).slice(0, k);
  let hit = 0;
  for (const g of gold) {
    if (top.some(entry => covers(entry, g))) hit++;
  }
  return hit / gold.size;
}

/**
 * 上下文召回率：gold 被「整份检索结果」覆盖的比例（不看 top-k、不看排序）。
 * <p>
 * 与 {@link recallAtK} 分开看：检索预算足够大时（整本书进上下文），
 * top-10 召回低只说明「gold 排在第 10 名之后」，不代表答案拿不到依据。
 * 上下文召回衡量的是「gold 有没有进入这一次回答的可用材料」。
 */
export function contextRecall(goldChunks, retrievedChunks) {
  const gold = new Set((goldChunks ?? []).filter(n => Number.isInteger(n)));
  if (gold.size === 0) return null;
  const all = retrievedChunks ?? [];
  let hit = 0;
  for (const g of gold) {
    if (all.some(entry => covers(entry, g))) hit++;
  }
  return hit / gold.size;
}

export function mrr(goldChunks, retrievedChunks, k = 10) {
  const gold = new Set((goldChunks ?? []).filter(n => Number.isInteger(n)));
  if (gold.size === 0) return null;
  const top = (retrievedChunks ?? []).slice(0, k);
  for (let i = 0; i < top.length; i++) {
    if (hitsGold(top[i], gold)) return 1 / (i + 1);
  }
  return 0;
}

export function ndcgAtK(goldChunks, retrievedChunks, k = 10) {
  const gold = new Set((goldChunks ?? []).filter(n => Number.isInteger(n)));
  if (gold.size === 0) return null;
  const top = (retrievedChunks ?? []).slice(0, k);
  let dcg = 0;
  for (let i = 0; i < top.length; i++) {
    if (hitsGold(top[i], gold)) dcg += 1 / Math.log2(i + 2);
  }
  let idcg = 0;
  for (let i = 0; i < Math.min(gold.size, k); i++) {
    idcg += 1 / Math.log2(i + 2);
  }
  return idcg === 0 ? null : dcg / idcg;
}

/**
 * 按相似度降序排出依据顺序（与提示词里片段的实际顺序一致）。
 * <p>
 * `/evidence` 接口按 chunkIndex 升序返回，但上下文是按 score 降序拼的
 * （合并后按 bestScore 重排 → 预算填充）。排序指标必须用后者，
 * 否则 Recall@10 实际问的是「gold 是不是全书最靠前的十块」。
 * <p>
 * 列表型路径所有片段的 score 都被置为 1.0，稳定排序会保留 chunkIndex 顺序，
 * 与该路径的真实输出一致。
 */
export function rankByScore(evidence) {
  return [...(evidence ?? [])].sort((a, b) => {
    const sa = typeof a?.score === 'number' ? a.score : -1;
    const sb = typeof b?.score === 'number' ? b.score : -1;
    return sb - sa;
  });
}

/**
 * 免责/非书中来源的标记：出现这些词的上下文里，提到禁用断言不算「声称书里写了」。
 * <p>
 * 实测踩到的两种假阳性：
 * <ul>
 *   <li>「老查理，量子比特的事儿你管不管？」——反问句里出现术语，不是断言；</li>
 *   <li>「……不过这是我自己的引申，不是书里的内容」——模型自己声明了来源，不是伪造引用。</li>
 * </ul>
 */
const DISCLAIMER_MARKERS = [
  '不是书里', '非书中', '并非书中', '不是书中', '不在书里', '不在这本书', '书里没有', '书中没有',
  '我的引申', '我自己的', '我的推断', '纯属我', '只是个比方', '打个比方', '仅供参考',
  '我的看法', '我的理解', '我的观点', '我的思考', '个人理解', '不代表',
];

/** 命中断言前后的上下文窗口长度（字符）。 */
const CLAIM_WINDOW = 120;

/**
 * 否定词紧邻窗口：命中词前后的这几个字里出现否定词 → 这是在**否认**内容，不是断言。
 * <p>
 * 与 {@link DISCLAIMER_MARKERS} 分开：免责语是整句级别的声明（"不是书里的内容"），
 * 否定词是词级别的（"跟宝船队没半点关系"）。用 120 字窗口找否定词会把整段话都吞掉，
 * 反而漏掉真正的断言，所以这里只看紧邻的十几个字。
 */
const NEGATION_NEAR_MARKERS = [
  '没有', '没', '未', '不', '非', '别', '甭', '毫无', '从未', '从不', '不存在',
  '不涉及', '不讨论', '不谈', '不提', '无关', '一字没', '一个字都', '压根', '空谈',
];

/** 否定词窗口长度（字符）——只覆盖命中词紧邻的上下文。 */
const NEGATION_NEAR_WINDOW = 10;

/**
 * 否定词被「不只/不仅/不但/不是不」反转时，它不再是否定，而是加强断言。
 * 例："书里不只提到宝船"——这里的「不」不能用来免责。
 */
const NEGATION_OVERRIDES = ['不只', '不仅', '不但', '不是不', '无不', '毫不'];

/**
 * 找出回答里「把禁用内容说成书里内容」的断言。
 * <p>
 * 只有当出现位置既不在免责/来源声明的上下文里、也不是反问句、也不在否定语里时，才算违规。
 */
export function findUndisclosedClaims(answer, claims) {
  const text = String(answer ?? '');
  const hits = [];
  for (const claim of claims ?? []) {
    if (!claim) continue;
    let from = 0;
    for (;;) {
      const at = text.indexOf(claim, from);
      if (at < 0) break;
      from = at + claim.length;
      const before = text.slice(Math.max(0, at - CLAIM_WINDOW), at);
      const after = text.slice(at, at + CLAIM_WINDOW);
      if (DISCLAIMER_MARKERS.some(m => before.includes(m) || after.includes(m))) continue;
      // 反问句里的术语不是断言
      if (after.slice(0, 40).includes('？') || after.slice(0, 40).includes('?')) continue;
      // 否定语里的术语是"否认内容"，不是断言
      const nearBefore = text.slice(Math.max(0, at - NEGATION_NEAR_WINDOW), at);
      const nearAfter = text.slice(at, at + NEGATION_NEAR_WINDOW);
      const negated = NEGATION_NEAR_MARKERS.some(m => nearBefore.includes(m) || nearAfter.includes(m));
      if (negated && !NEGATION_OVERRIDES.some(m => nearBefore.includes(m))) continue;
      hits.push(claim);
      break;
    }
  }
  return hits;
}

/**
 * 流水线在「该条没检索到材料」时输出预写占位节（不调模型）。这两句是产品侧固定文案。
 * 占位节的标题里仍然带着条目名——不剔掉的话，「六节全空」的答案会拿满分覆盖率。
 */
const PLACEHOLDER_SENTENCES = [
  '这一节的原书材料没检索到，我先不展开，避免说错。你可以单独问我这一条。',
  '这一节暂时没法展开，材料不足，先记着，回头我单独补给你。',
];

/** 去掉正文只剩占位文案的分节（标题一并去掉）；没有分节标题的答案原样返回。 */
export function stripPlaceholderSections(answer) {
  const text = answer ?? '';
  if (!text.includes('### ')) return text;
  const blocks = text.split(/\n(?=### )/);
  return blocks
    .filter(block => {
      const body = PLACEHOLDER_SENTENCES.reduce((acc, s) => acc.split(s).join(''), block);
      if (body === block) return true; // 没有占位文案：正常节
      return body.replace(/^###[^\n]*\n?/, '').trim().length >= 20;
    })
    .join('\n');
}

export function itemCoverage(goldItems, answer) {
  const items = (goldItems ?? []).filter(Boolean).map(normalize);
  if (items.length === 0) return null;
  const text = normalize(stripPlaceholderSections(answer));
  // 「糟糕的婚姻」vs「糟糕婚姻」是同一个条目；只差结构助词时不该判没覆盖。
  // 去掉助词后短于 2 字的不参与（否则空串会命中任何答案）。
  const hit = items.filter(item => {
    if (text.includes(item)) return true;
    const loose = item.replace(/[的地得]/g, '');
    return loose.length >= 2 && loose !== item && text.includes(loose);
  }).length;
  return hit / items.length;
}

export function citationStats(answer, evidenceCount) {
  const text = answer ?? "";
  const cited = new Set();
  let total = 0;
  let valid = 0;
  let invalid = 0;
  for (const match of text.matchAll(/\[\[?\s*([0-9][0-9,\s]*)\s*\]\]?/g)) {
    for (const token of match[1].split(/[,\s]+/)) {
      if (!token) continue;
      total++;
      const n = Number(token);
      if (Number.isInteger(n) && n >= 1 && n <= evidenceCount) {
        valid++;
        cited.add(n);
      } else {
        invalid++;
      }
    }
  }
  return {
    total,
    valid,
    invalid,
    validity: total === 0 ? null : valid / total,
    citedChunks: [...cited].sort((a, b) => a - b),
  };
}

export const DEFAULT_REFUSAL_PATTERNS = [
  '没找到', '未涉及', '信息不够', '无法回答', '材料不足', '没有检索到',
];

export function isRefusal(answer, patterns = DEFAULT_REFUSAL_PATTERNS) {
  const text = answer ?? '';
  return patterns.some(p => text.includes(p));
}

/**
 * 把一次 run 的错误消息归类。
 * 认证类错误必须单独统计：token 中途过期会让整份报告的指标全变成 0，
 * 但那是「跑挂了」而不是「质量差」，混在一起会把基线读错。
 */
export function classifyError(message) {
  const text = String(message ?? '');
  if (/HTTP (401|403)/.test(text)) return 'auth';
  if (/abort|timeout|timed out/i.test(text)) return 'timeout';
  if (/ECONNREFUSED|ENOTFOUND|EAI_AGAIN|socket hang up|fetch failed/i.test(text)) return 'network';
  return 'other';
}

/**
 * 主题邻域否定词：出现这些词 + 主题词邻近 → 判定为「明说书中没有这个主题」。
 * <p>
 * 纯关键词表会漏掉「这本书里没有量子计算」「量子纠缠？不存在的」「一个字都没提」
 * 这类同义否定；只靠它们会得到假阴性（真拒答被判成没拒答）。
 * <p>
 * 否定词表刻意不含单字「不」「无」「未」——「不同」「未来」「无疑」会大面积误命中，
 * 宁可漏判也不要在切题用例上误判为拒答。
 */
const TOPIC_NEGATIONS = [
  '没有', '没提', '不存在', '不涉及', '不包含', '谈不上', '毫无',
  '无相关', '未提及', '未提到', '未涉及', '未包含', '未讨论', '未记载',
  '找不到', '一个字都', '一字都', '毫无', '谈不到',
];

/** 主题词与否定词的最大字符间距（同义否定常被标点/空格隔开）。 */
const NEGATION_WINDOW = 16;

/**
 * 判断回答是否构成「对指定主题的拒答」。
 * <p>
 * 命中条件（任一）：
 * <ol>
 *   <li>命中显式拒答关键词表；</li>
 *   <li>主题词附近出现否定词，即「明说书中没有这个主题」。</li>
 * </ol>
 *
 * @param {string} answer 回答正文
 * @param {string[]} topics 主题词（如 ['量子计算','量子纠缠']）；为空时只走关键词表
 * @param {string[]} patterns 显式拒答关键词表
 */
export function isRefusalAbout(answer, topics = [], patterns = DEFAULT_REFUSAL_PATTERNS) {
  const text = String(answer ?? '');
  if (isRefusal(text, patterns)) return true;
  return topicNegated(text, topics);
}

function topicNegated(text, topics = []) {
  for (const topic of topics ?? []) {
    if (!topic) continue;
    let from = 0;
    for (;;) {
      const at = text.indexOf(topic, from);
      if (at < 0) break;
      const window = text.slice(Math.max(0, at - NEGATION_WINDOW), at + topic.length + NEGATION_WINDOW);
      if (TOPIC_NEGATIONS.some(n => window.includes(n))) return true;
      from = at + topic.length;
    }
  }
  return false;
}

/** 拒答词与"读者所问的东西"的最大字符间距；超出即认为在说别的事，不是本次回答拒答。 */
const KEYWORD_REFUSAL_WINDOW = 40;

/**
 * 关键词分支的主题邻域版本，用于**切题用例**（没有 refusal_topics）。
 * <p>
 * 切题用例此前只能走关键词表全文搜索 → 正文里任何一处出现拒答词都算拒答。
 * 实测「25 条心理倾向」那条答案写全了 25 条，只在尾部说
 * 「第11、18、19、20条…我按"这部分材料不足"如实标注了」，就被判成 refusal=yes，
 * 整份报告的 falseRefusalRate 只剩这一个来源。
 * <p>
 * anchors 为空时退回"关键词命中即拒答"的原语义 —— 拒答用例（有 refusal_topics）的判定不变，
 * 不能为了消假阳性把真拒答也放宽。
 *
 * @param {string[]} anchors 读者所问的对象（gold_items / refusal_topics）
 */
export function keywordRefusalNearAnyTopic(answer, anchors = [], patterns = DEFAULT_REFUSAL_PATTERNS) {
  const text = String(answer ?? '');
  const words = (anchors ?? []).filter(a => a && String(a).length >= 2).map(String);
  if (words.length === 0) return isRefusal(text, patterns);
  for (const pattern of patterns ?? []) {
    let from = 0;
    for (;;) {
      const at = text.indexOf(pattern, from);
      if (at < 0) break;
      const near = words.some(word => {
        const wordAt = text.indexOf(word, Math.max(0, at - KEYWORD_REFUSAL_WINDOW));
        return wordAt >= 0 && wordAt <= at + pattern.length + KEYWORD_REFUSAL_WINDOW;
      });
      if (near) return true;
      from = at + pattern.length;
    }
  }
  return false;
}

export function passK(successes) {
  return successes.length > 0 && successes.every(Boolean);
}

export function percentile(values, p) {
  const sorted = (values ?? []).filter(Number.isFinite).sort((a, b) => a - b);
  if (sorted.length === 0) return null;
  const rank = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(sorted.length - 1, rank))];
}

export function mean(values) {
  const nums = (values ?? []).filter(Number.isFinite);
  if (nums.length === 0) return null;
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}

function normalize(text) {
  return String(text ?? '').replace(/\s+/g, '').toLowerCase();
}
