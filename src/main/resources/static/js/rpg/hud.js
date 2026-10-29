/**
 * GM 气泡富组件渲染器（rpg-hud-bubble-components）
 *
 * 数据轨复用（design D1）：模型经 <state_delta> 输出结构化数据（choices/mind 等），
 * 本模块把 state_delta JSON 组装为气泡富组件（HUD 头 / 主角·NPC 卡 / 心理气泡 /
 * 选项按钮），模型 HTML 输出量为零。
 *
 * 三段式挂载（design D5）：HUD 头插叙述气泡之前（视觉顶部），卡片与选项组插气泡
 * 之后（actions 行之前恒居尾）；叙述流式 innerHTML 整体重写逻辑零改动。
 *
 * XSS 防线（design D9）：全部节点 createElement/textContent 数据绑定，模型数据
 * 永不成为可执行标记。
 *
 * 降级矩阵（design D8）：JSON 坏 → 整包丢弃 + warn；任一字段缺失 → 对应子组件
 * 缺省不渲染，不存在半渲染态。
 *
 * 作者自定义内容块（rpg-hud-custom-blocks）：世界卡 customBlocks 声明「标题 +
 * state_delta 字段名」映射，applyWorldCustomBlocks 校验后存全局配置，
 * buildCustomBlocks 按固定模板（值形态→行形态映射写死）渲染；field 安全校验
 * 双层（标识符正则 + hasOwnProperty），textContent 绑定与降级语义同上。
 *
 * 本文件产出的全局符号：
 *   变量：worldHudCustomBlocks
 *   函数：attachHudComponents, reviveLatestHudChoices, invalidateHudChoices,
 *         applyWorldTheme, applyWorldThemeById, applyWorldCustomBlocks
 *   依赖（运行时）：game.js 的 displayNpcKey / displayNpcStatus / sendTurn /
 *         cosmoRender / worldCharCards、state.js 的 currentCharId（加载序在
 *         hud.js 之后，运行时调用无碍，同 backtrack.js 引用 sendTurn 模式）
 */

// ===== state 解析（D8：坏 JSON 整包丢弃降级纯叙述） =====

/** 解析 state_delta JSON；null/空/坏 JSON/非对象均返回 null（纯叙述降级） */
function parseHudState(stateJson) {
    if (!stateJson || typeof stateJson !== 'string') return null;
    try {
        const obj = JSON.parse(stateJson);
        return (obj && typeof obj === 'object' && !Array.isArray(obj)) ? obj : null;
    } catch (e) {
        console.warn('HUD state JSON 解析失败，该气泡降级纯叙述', e);
        return null;
    }
}

/** 依候选键取非空字符串（GM 下划线/驼峰两种命名兼容，与后端 firstPresent 对齐） */
function pickHudString(obj, keys) {
    for (const k of keys) {
        const v = obj[k];
        if (typeof v === 'string' && v.trim() !== '') return v.trim();
    }
    return null;
}

/** 依候选键取字符串数组（过滤非字符串/空白项） */
function pickHudStringArray(obj, keys) {
    for (const k of keys) {
        const v = obj[k];
        if (Array.isArray(v)) return v.filter(x => typeof x === 'string' && x.trim() !== '');
    }
    return [];
}

/** 依候选键取对象值（过滤 null/数组） */
function pickHudObject(obj, keys) {
    for (const k of keys) {
        const v = obj[k];
        if (v && typeof v === 'object' && !Array.isArray(v)) return v;
    }
    return null;
}

// ===== 挂载入口 =====

/**
 * 把 state JSON 组装的富组件挂入 GM 气泡 wrap（live 尾包与历史回放共用）。
 * @param {HTMLElement} gmWrap GM 气泡 wrap（.rpg-message.gm）
 * @param {string|null} stateJson state_delta JSON 文本（null/坏 JSON → 不渲染）
 * @param {{live?:boolean, onChoice?:(quote:string, edgeId?:string)=>void,
 *          graphEdges?:Array<{id:string,label?:string,quote:string,hint?:string}>,
 *          improviseAllowed?:boolean}} [ctx]
 *        live=true（尾包路径）：组件淡入、选项可点（onChoice 缺省直接 sendTurn）；
 *        graphEdges（story-graph 4.5）：当前节点图出边 → 图边按钮组（复用 choices
 *        组件结构，渲染在 GM choices 之前，off/锚点时是唯一选项源）；
 *        improviseAllowed=false → GM 即兴 choices 不渲染。历史回放（默认）：
 *        选项置灰、不传 graphEdges/improviseAllowed（不渲染图边、GM choices
 *        照旧），由 reviveLatestHudChoices 激活最新一条
 * @returns {boolean} 是否实际渲染了至少一个组件
 */
function attachHudComponents(gmWrap, stateJson, ctx = {}) {
    const state = parseHudState(stateJson);
    if (!state || !gmWrap) return false;
    const bubble = gmWrap.querySelector('.rpg-bubble');
    if (!bubble) return false;

    let rendered = false;
    const head = buildHudHead(state);
    if (head) {
        gmWrap.insertBefore(head, bubble); // 视觉顶部：叙述气泡之前
        rendered = true;
    }
    const cards = buildHudCards(state);
    if (cards) {
        appendHudBlock(gmWrap, cards);
        rendered = true;
    }
    const customBlocks = buildCustomBlocks(state);   // 状态卡之后、选项组之前（rpg-hud-custom-blocks D3）
    if (customBlocks) {
        appendHudBlock(gmWrap, customBlocks);
        rendered = true;
    }
    const graphChoices = buildGraphChoices(ctx);   // 图边优先：混排时在 GM 即兴 choices 之前（story-graph 4.5）
    if (graphChoices) {
        appendHudBlock(gmWrap, graphChoices);
        rendered = true;
    }
    const choices = buildHudChoices(state, ctx);
    if (choices) {
        appendHudBlock(gmWrap, choices);
        rendered = true;
    }
    // 尾包路径淡入（D5）：组件后到，动画恰好还原 demo「打字机剧情 → 选项淡入」节奏
    if (rendered && ctx.live === true) {
        [head, cards, customBlocks, graphChoices, choices].forEach(el => {
            if (el) el.classList.add('hud-fade-in');
        });
    }
    return rendered;
}

/** 组件块插入 wrap 尾部（actions 行之前，保持回溯/重新生成行恒在最尾） */
function appendHudBlock(gmWrap, el) {
    const actions = gmWrap.querySelector('.rpg-msg-actions');
    if (actions) gmWrap.insertBefore(el, actions);
    else gmWrap.appendChild(el);
}

// ===== HUD 头（location / scene_npcs） =====

/** HUD 头：地点 + 在场名单（demo 顶部条的轻量版；两项全缺 → 整头不渲染） */
function buildHudHead(state) {
    const loc = pickHudString(state, ['location_change', 'currentLocation', 'location']);
    const cast = pickHudStringArray(state, ['scene_npcs', 'sceneNpcs']);
    if (loc == null && cast.length === 0) return null;

    const head = document.createElement('div');
    head.className = 'hud-head';
    if (loc != null) {
        const locEl = document.createElement('span');
        locEl.className = 'hud-loc';
        locEl.textContent = '✦ ' + loc;
        head.appendChild(locEl);
    }
    if (cast.length > 0) {
        const names = cast.map(k => displayNpcKey(k));
        const castEl = document.createElement('span');
        castEl.className = 'hud-cast';
        castEl.textContent = '在场 · ' + names.join('、');
        castEl.title = names.join('、'); // 名单长时悬停看全量
        head.appendChild(castEl);
    }
    return head;
}

// ===== 卡片行（主角卡 + NPC 卡） =====

/**
 * 卡片行：主角卡（state.player 基础字段）+ NPC 卡（本轮 touched npc_states 条目，
 * 含 status 汉化与 mind 心理气泡）。两者全缺 → 整行不渲染（D8）。
 */
function buildHudCards(state) {
    const playerNode = pickHudObject(state, ['player', 'playerStates']);
    const npcNode = pickHudObject(state, ['npc_states', 'npcStates']);
    const heroCard = playerNode ? buildHeroCard(playerNode) : null;

    const npcCards = [];
    if (npcNode) {
        for (const [key, v] of Object.entries(npcNode)) {
            if (v && typeof v === 'object') {
                npcCards.push(buildNpcCard(key, v));
            }
        }
    }
    if (!heroCard && npcCards.length === 0) return null;

    const row = document.createElement('div');
    row.className = 'hud-cards';
    if (heroCard) row.appendChild(heroCard);
    npcCards.forEach(c => row.appendChild(c));
    return row;
}

/**
 * 主角卡：已知基础字段友好展示（money/titles/abilities/cosmo），未知键忽略
 * （Non-Goals：档案密度扩展留后续提案）。无可展示字段 → 返回 null（D8）。
 */
function buildHeroCard(playerNode) {
    const rows = [];
    if (typeof playerNode.money === 'number') {
        rows.push(['经济', String(playerNode.money)]);
    } else if (typeof playerNode.money === 'string' && playerNode.money.trim() !== '') {
        rows.push(['经济', playerNode.money.trim()]);
    }
    if (Array.isArray(playerNode.titles) && playerNode.titles.length > 0) {
        const titles = playerNode.titles.filter(t => typeof t === 'string' && t.trim() !== '');
        if (titles.length > 0) rows.push(['称号', titles.join('、')]);
    }
    if (playerNode.abilities && typeof playerNode.abilities === 'object'
            && !Array.isArray(playerNode.abilities)) {
        const keys = Object.keys(playerNode.abilities);
        if (keys.length > 0) rows.push(['能力', keys.join('、')]);
    }
    if (typeof playerNode.cosmo === 'number') {
        rows.push(['小宇宙', cosmoRender(playerNode.cosmo)]);
    }
    if (rows.length === 0) return null;

    // 名字解析：玩家角色卡 ID 命中缓存显示卡名，否则「主角」
    const heroName = (typeof currentCharId === 'string' && currentCharId)
            ? displayNpcKey(currentCharId) : '主角';

    const card = document.createElement('div');
    card.className = 'hud-card hero';
    card.appendChild(buildCardHead(heroName, null));
    rows.forEach(([k, v]) => card.appendChild(buildCardRow(k, v)));
    return card;
}

/** NPC 卡：名字 + status 徽章（汉化）+ mind 心理气泡；status/mind 全缺仍渲染名字卡 */
function buildNpcCard(key, v) {
    const card = document.createElement('div');
    card.className = 'hud-card npc';
    const status = typeof v.status === 'string' ? v.status.trim() : '';
    card.appendChild(buildCardHead(displayNpcKey(key), status || null));
    if (typeof v.mind === 'string' && v.mind.trim() !== '') {
        const mind = document.createElement('div');
        mind.className = 'hud-mind';
        const tag = document.createElement('span');
        tag.className = 'hud-mind-tag';
        tag.textContent = '心理活动';
        const text = document.createElement('span');
        text.className = 'hud-mind-text';
        text.textContent = v.mind.trim();
        mind.appendChild(tag);
        mind.appendChild(text);
        card.appendChild(mind);
    }
    return card;
}

/** 卡片头：名字 + 可选 status 徽章（status 经汉化映射，表外值原样透传） */
function buildCardHead(name, status) {
    const headEl = document.createElement('div');
    headEl.className = 'hud-card-head';
    const nameEl = document.createElement('span');
    nameEl.className = 'hud-card-name';
    nameEl.textContent = name;
    headEl.appendChild(nameEl);
    if (status) {
        const badge = document.createElement('span');
        badge.className = 'hud-status-badge';
        badge.textContent = displayNpcStatus(status);
        headEl.appendChild(badge);
    }
    return headEl;
}

/** 卡片字段行：k（窄标签）+ v（值） */
function buildCardRow(k, v) {
    const row = document.createElement('div');
    row.className = 'hud-row';
    const kEl = document.createElement('span');
    kEl.className = 'hud-row-k';
    kEl.textContent = k;
    const vEl = document.createElement('span');
    vEl.className = 'hud-row-v';
    vEl.textContent = v;
    row.appendChild(kEl);
    row.appendChild(vEl);
    return row;
}

// ===== 作者自定义内容块（rpg-hud-custom-blocks D3：字段映射 + 固定模板） =====

/**
 * 自定义内容块组：按 worldHudCustomBlocks 配置渲染（挂载于状态卡之后、选项组之前）。
 * 值形态三映射（写死，无配置余地）：string/number/boolean → 单行；object → 键值行集
 * （嵌套值 JSON.stringify 压缩保底）；array → 列表行（对象项优先 text 键，done:true
 * 完成态样式）。取数 hasOwnProperty 双层防御（D4：原型链键不可达）；字段缺失/值为
 * null → 整块缺省（D8）。全部 createElement/textContent（D9）。
 */
function buildCustomBlocks(state) {
    if (!worldHudCustomBlocks.length) return null;
    const blocks = [];
    for (const cfg of worldHudCustomBlocks) {
        // D4 第二层：JSON.parse 产物取自有键，原型链键（__proto__/constructor）不可达
        if (!Object.prototype.hasOwnProperty.call(state, cfg.field)) continue;
        const value = state[cfg.field];
        if (value === null || value === undefined) continue;   // 字段缺失/null → 整块缺省（D8）
        blocks.push(buildCustomBlock(cfg, value));
    }
    if (blocks.length === 0) return null;
    const group = document.createElement('div');
    group.className = 'hud-custom-blocks';
    blocks.forEach(b => group.appendChild(b));
    return group;
}

/** 单块：标题行（accent 边框与标题色，缺省 --hud-gold）+ 值形态映射的行集 */
function buildCustomBlock(cfg, value) {
    const block = document.createElement('div');
    block.className = 'hud-custom-block';
    if (cfg.accent) block.style.setProperty('--hud-block-accent', 'var(' + cfg.accent + ')');
    const title = document.createElement('div');
    title.className = 'hud-custom-block-title';
    title.textContent = cfg.title;
    block.appendChild(title);
    appendCustomBlockRows(block, value);
    return block;
}

/** 值形态 → 行形态映射（D3）：基本类型单行 / 对象键值行集（复用 .hud-row）/ 数组列表行 */
function appendCustomBlockRows(block, value) {
    if (typeof value === 'object' && value !== null) {
        if (Array.isArray(value)) {
            value.forEach(item => block.appendChild(buildCustomListItem(item)));
        } else {
            for (const [k, v] of Object.entries(value)) {
                block.appendChild(buildCardRow(k, stringifyBlockValue(v)));
            }
        }
    } else {
        block.appendChild(buildCustomSingleRow(String(value)));
    }
}

/** 数组列表行：对象项优先 text 键（{text, done?} 常见形态），done:true 加完成态；无 text 键 stringify 压缩 */
function buildCustomListItem(item) {
    const row = document.createElement('div');
    row.className = 'hud-custom-row';
    if (item !== null && typeof item === 'object') {
        if (item.done === true) row.classList.add('done');
        row.textContent = (typeof item.text === 'string' && item.text.trim() !== '')
                ? item.text.trim()
                : JSON.stringify(item);
    } else {
        row.textContent = stringifyBlockValue(item);
    }
    return row;
}

/** 单值行（string/number/boolean 的单行形态） */
function buildCustomSingleRow(text) {
    const row = document.createElement('div');
    row.className = 'hud-custom-row single';
    row.textContent = text;
    return row;
}

/** 值文本化（D3 保底）：基本类型 String() 化，其余 JSON.stringify 压缩（不截断，保真优先） */
function stringifyBlockValue(v) {
    if (v === null || v === undefined) return '';
    if (typeof v === 'string') return v;
    if (typeof v === 'number' || typeof v === 'boolean') return String(v);
    return JSON.stringify(v);
}

// ===== 选项组（choices） =====

/**
 * 图边选项组（story-graph 4.5）：story-options 视图的 edges → 按钮组，与 GM
 * choices 同构（序号圆点 + label 主文本 + hint 副文本 + 箭头），class 加 graph
 * 区分（圆点用主角色色）；渲染在 GM choices 之前。id/quote 任一缺失的条目跳过；
 * 未传 graphEdges（历史回放）/空数组（未挂图、free 退化）→ 整组不渲染。
 * 点击以边 quote 发起新回合并携带 edgeId（后端确定性结算）。
 */
function buildGraphChoices(ctx = {}) {
    const edges = Array.isArray(ctx.graphEdges) ? ctx.graphEdges.filter(e => e && typeof e === 'object'
            && typeof e.id === 'string' && e.id.trim() !== ''
            && typeof e.quote === 'string' && e.quote.trim() !== '') : [];
    if (edges.length === 0) return null;

    const row = document.createElement('div');
    row.className = 'hud-choices graph';
    edges.forEach((e, i) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'hud-choice';
        btn.disabled = ctx.live !== true; // 仅 live 尾包渲染（历史回放不传 graphEdges）

        const no = document.createElement('span');
        no.className = 'hud-choice-no';
        no.textContent = String(i + 1);

        const main = document.createElement('span');
        main.className = 'hud-choice-main';
        const label = document.createElement('span');
        label.className = 'hud-choice-label';
        // label 缺失时退用 quote（后端已兑空串，防御小模型漏字段）
        label.textContent = (typeof e.label === 'string' && e.label.trim() !== '')
                ? e.label.trim() : e.quote.trim();
        main.appendChild(label);
        if (typeof e.hint === 'string' && e.hint.trim() !== '') {
            const hint = document.createElement('small');
            hint.className = 'hud-choice-hint';
            hint.textContent = e.hint.trim();
            main.appendChild(hint);
        }

        const arrow = document.createElement('span');
        arrow.className = 'hud-choice-arrow';
        arrow.textContent = '→';

        btn.appendChild(no);
        btn.appendChild(main);
        btn.appendChild(arrow);

        const quote = e.quote.trim();
        const edgeId = e.id.trim();
        btn.addEventListener('click', () => {
            if (btn.disabled || btn.classList.contains('stale')) return;
            if (typeof ctx.onChoice === 'function') ctx.onChoice(quote, edgeId);
            else sendTurn(quote, edgeId); // 历史激活后的兑底：等效图边重发
        });
        row.appendChild(btn);
    });
    return row;
}

/**
 * 图上回溯后刷新最后 GM 气泡的选项区（story-graph 4.10）：旧图边按钮组移除、
 * 其余选项（GM 即兴 choices）置 stale 失效，再按回溯后当前节点的 story-options
 * 视图重建图边按钮组（off 模式玩家推进的唯一入口）；无 edges（未挂图/当前
 * 节点无出边）仅做清理。GM 即兴 choices 不重建——旧语境已失效，新一轮尾包
 * 才有新 choices（期间玩家仍可自由输入）。
 * opts.keepChoices=true（读档恢复路径）：GM choices 已由 reviveLatestHudChoices
 * 激活（存档末轮语境依然有效），保留不置灰，仅补挂图边按钮。
 */
function refreshGraphChoicesAfterBacktrack(storyOpts, onChoice, opts = {}) {
    const msgs = [...getMessagesContainer().querySelectorAll('.rpg-message.gm')];
    const lastGm = msgs[msgs.length - 1];
    if (!lastGm) return false;
    lastGm.querySelectorAll('.hud-choices.graph').forEach(el => el.remove());
    if (!opts.keepChoices) {
        lastGm.querySelectorAll('.hud-choice').forEach(btn => {
            btn.disabled = true;
            btn.classList.add('stale');
        });
    }
    const edges = storyOpts ? storyOpts.edges : null;
    if (!Array.isArray(edges) || edges.length === 0) return true;
    const row = buildGraphChoices({graphEdges: edges, live: true, onChoice});
    if (row) {
        row.classList.add('hud-fade-in');
        appendHudBlock(lastGm, row);
    }
    return true;
}

/**
 * 选项组：choices 数组 → 按钮组（序号圆点 + label 主文本 + hint 副文本 + 箭头）。
 * label/quote 任一缺失的条目跳过（小模型漏字段容忍）；数组缺失/清空 → 整组不渲染。
 * 点击发送 quote（台词进上下文，label 不进——D11）。
 */
function buildHudChoices(state, ctx = {}) {
    // 即兴过滤（story-graph 4.5）：improviseAllowed=false（off 模式/锚点/节点级
    // 覆盖）时 GM 即兴 choices 不渲染——图边按钮是唯一选项源。历史回放不传
    // 该标志，GM choices 照旧渲染
    if (ctx.improviseAllowed === false) return null;
    if (!Array.isArray(state.choices)) return null;
    const items = state.choices.filter(c => c && typeof c === 'object'
            && typeof c.label === 'string' && c.label.trim() !== ''
            && typeof c.quote === 'string' && c.quote.trim() !== '');
    if (items.length === 0) return null;

    const live = ctx.live === true;
    const row = document.createElement('div');
    row.className = 'hud-choices';
    items.forEach((c, i) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'hud-choice';
        btn.disabled = !live; // 历史回放默认置灰，reviveLatestHudChoices 激活最新一条

        const no = document.createElement('span');
        no.className = 'hud-choice-no';
        no.textContent = String(i + 1);

        const main = document.createElement('span');
        main.className = 'hud-choice-main';
        const label = document.createElement('span');
        label.className = 'hud-choice-label';
        label.textContent = c.label.trim();
        main.appendChild(label);
        if (typeof c.hint === 'string' && c.hint.trim() !== '') {
            const hint = document.createElement('small');
            hint.className = 'hud-choice-hint';
            hint.textContent = c.hint.trim();
            main.appendChild(hint);
        }

        const arrow = document.createElement('span');
        arrow.className = 'hud-choice-arrow';
        arrow.textContent = '→';

        btn.appendChild(no);
        btn.appendChild(main);
        btn.appendChild(arrow);

        const quote = c.quote.trim();
        btn.addEventListener('click', () => {
            if (btn.disabled || btn.classList.contains('stale')) return;
            if (typeof ctx.onChoice === 'function') ctx.onChoice(quote);
            else sendTurn(quote); // 历史激活后的兜底：等效输入框发送同一台词
        });
        row.appendChild(btn);
    });
    return row;
}

// ===== 选项生命周期（D11：点击即发送，新回合开始全量失效） =====

/**
 * 历史回放收尾（loadHistory 末尾调用）：仅最新一条带选项的 GM 气泡激活选项，
 * 其余保持置灰——刷新后可直接点最新选项继续，历史轮次选项不可点。
 */
function reviveLatestHudChoices() {
    const gmWraps = document.querySelectorAll('#messagesContainer .rpg-message.gm');
    for (let i = gmWraps.length - 1; i >= 0; i--) {
        const btns = gmWraps[i].querySelectorAll('.hud-choice');
        if (btns.length === 0) continue;
        btns.forEach(b => { b.disabled = false; });
        return;
    }
}

/** 新回合开始：全部选项置灰失效（与 regenerate-btn 清理同位置调用，D11） */
function invalidateHudChoices() {
    document.querySelectorAll('#messagesContainer .hud-choice').forEach(btn => {
        btn.disabled = true;
        btn.classList.add('stale');
    });
}

// ===== 作者换肤：themeTokens 校验与注入（design D10 / tasks 4.2）=====
// 结构化 token（CSS 变量名→颜色 + hudStyle 枚举），否决自由 CSS：url() 数据渗出、
// position:fixed 骗点击面归零，注入天然 scope 到 GM 组件（var() 引用仅在组件规则）。
// 白名单 = 组件 CSS 实际消费的变量集，与 rpg.css :root 变量块同源维护（spec 钉住枚举）。
const HUD_TOKEN_VARS = ['--hud-gold', '--hero-accent', '--npc-accent', '--panel-bg', '--hud-mind-accent'];
const HUD_TOKEN_STYLES = ['classic', 'compact', 'minimal'];
const HUD_COLOR_RE = /^(#[0-9a-fA-F]{3,8}|rgba?\([^()]*\))$/;   // hex 3/4/6/8 位 或 rgb[a](...)
/** 自定义块 field 安全标识符正则（rpg-hud-custom-blocks D4，与 workshop.js 表单侧同规则） */
const HUD_BLOCK_FIELD_RE = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/;

/**
 * 应用世界自定义内容块配置（rpg-hud-custom-blocks D2/D7）：解析 + 单块校验
 * （field 安全正则 / accent ∈ HUD_TOKEN_VARS / title 限长 / ≤5 块），非法块确定性
 * 丢弃 + warn 不报错；NULL/坏 JSON/非数组/超限 → 整包丢弃走无块状态。
 * 由 applyWorldThemeById 的世界卡拉取同步喂入（同缓存同替换语义）。
 */
let worldHudCustomBlocks = [];
function applyWorldCustomBlocks(blocksJson) {
    worldHudCustomBlocks = [];
    if (!blocksJson) return;
    let parsed;
    try {
        parsed = JSON.parse(blocksJson);
    } catch (e) {
        console.warn('customBlocks 解析失败（按无自定义块处理）:', e);
        return;
    }
    if (!Array.isArray(parsed)) {
        console.warn('customBlocks 非数组（整包丢弃）');
        return;
    }
    if (parsed.length > 5) {
        console.warn('customBlocks 超过 5 块上限（整包丢弃）:', parsed.length);
        return;
    }
    for (let i = 0; i < parsed.length; i++) {
        const b = parsed[i];
        const accentOk = b.accent == null
                || (typeof b.accent === 'string' && HUD_TOKEN_VARS.includes(b.accent));
        const blockOk = b && typeof b === 'object' && !Array.isArray(b)
                && typeof b.title === 'string' && b.title.trim() !== '' && b.title.length <= 20
                && typeof b.field === 'string' && HUD_BLOCK_FIELD_RE.test(b.field)
                && accentOk;
        if (!blockOk) {
            console.warn('customBlocks 第 ' + (i + 1) + ' 块配置非法（丢弃该块）:', b);
            continue;
        }
        const block = {title: b.title.trim(), field: b.field};
        if (typeof b.accent === 'string') block.accent = b.accent;
        worldHudCustomBlocks.push(block);
    }
}

/**
 * 应用世界主题 token 到 GM 组件作用域（body 级 setProperty + hudStyle class）。
 * 校验：变量名 ∈ 白名单、颜色 hex/rgb[a]、hudStyle ∈ 枚举；非法项确定性丢弃 +
 * warn 不报错（spec「非法 token 丢弃」）。themeTokens NULL/空 → 清除注入走默认主题。
 */
function applyWorldTheme(themeTokens) {
    // 先全量清除既有注入（切换世界/存档语义），再按 token 注入
    HUD_TOKEN_VARS.forEach(v => document.body.style.removeProperty(v));
    document.body.classList.remove('hud-compact', 'hud-minimal');
    if (!themeTokens) return;
    const parsed = parseHudState(themeTokens);
    if (!parsed) return;   // 坏 JSON/非对象：整包丢弃，走默认主题
    for (const [key, value] of Object.entries(parsed)) {
        if (key === 'hudStyle') {
            if (HUD_TOKEN_STYLES.includes(value)) {
                if (value === 'compact') document.body.classList.add('hud-compact');
                else if (value === 'minimal') document.body.classList.add('hud-minimal');
            } else {
                console.warn('themeTokens hudStyle 非枚举值（丢弃）:', value);
            }
            continue;
        }
        if (!HUD_TOKEN_VARS.includes(key)) {
            console.warn('themeTokens 未知变量名（丢弃）:', key);
            continue;
        }
        if (typeof value !== 'string' || !HUD_COLOR_RE.test(value.trim())) {
            console.warn('themeTokens 颜色值非 hex/rgb[a]（丢弃）:', key, value);
            continue;
        }
        document.body.style.setProperty(key, value.trim());
    }
}

/**
 * 按 worldId 拉世界卡应用主题（开局 startGameWith / 读档 continueSave 统一入口）。
 * 同世界幂等（重复开局/读档不重拉）；拉取/解析失败 warn 走默认主题，不阻断开局链。
 */
let appliedThemeWorldId = null;
async function applyWorldThemeById(worldId) {
    if (!worldId || appliedThemeWorldId === worldId) return;
    try {
        const res = await fetch('/rpg/workshop/world/' + encodeURIComponent(worldId));
        if (!res.ok) {
            console.warn('世界卡拉取失败（主题走默认）:', worldId, res.status);
            return;
        }
        const world = await res.json();
        appliedThemeWorldId = worldId;
        applyWorldTheme(world.themeTokens || null);
        applyWorldCustomBlocks(world.customBlocks || null);   // 同缓存同替换（rpg-hud-custom-blocks D2）
    } catch (e) {
        console.warn('主题加载失败（走默认主题）:', e);
    }
}
