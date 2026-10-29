/**
 * 预设冒险向导（preset-rpg-entry）：星命推演 → 命定身份 → 建卡开局
 *
 * 本文件产出的全局符号：
 *   变量：presetWorldId, presetWorldName
 *   函数：enterPresetWizard
 *   依赖（运行时）：state.js（currentWorldId / currentGameStateId / currentSessionId / persistIdentity）；
 *         game.js（showView / startOpening）；messages.js（clearMessages）
 *   加载顺序：game.js 之后、init.js 之前（init.js 需调用 enterPresetWizard）
 *
 * 本向导是「星命推演」的唯一入口：工坊角色卡 tab 里的折叠面板已随 preset-rpg-entry 拆除。
 * 关键点是第三步 /rpg/workshop/start 必须携带 name/gender/birthDate/timeIndex/faction，
 * 否则后端 originResult 为 null、playerStates 落成 "{}"，开场白与 GM 读不到任何星命数据。
 */

// ===== 模块内状态 =====
/** 已物化的预设世界 ID（形如 preset-adventure:{tenantId}），入世建卡与开局都要用 */
let presetWorldId = null;
/** 已物化的预设世界名，仅用于日志与提示 */
let presetWorldName = null;
/** 最近一次推演的输入，开局时原样透传给 /start（后端据此重算并注入 playerStates） */
let presetOriginInput = null;
/** 最近一次推演结果，用于拼角色卡名「{姓名}·{星座}」 */
let presetOriginResult = null;
/**
 * 小宇宙重 roll 种子（rpg-cosmo-sense）：默认 0，每次点「↻ 重新生成」+1。
 * 并入 presetOriginInput 透传 /start，保证「步骤②所见值 = 存档 playerStates.cosmo」。
 * 掷骰确定性：同五要素同 seed 后端两遍计算必得同值，故展示与落档天然一致。
 */
let presetRollSeed = 0;

/**
 * 步骤③选中的资料来源 set id（null=世界默认），随 /start 提交 inheritSetId
 * （add-rpg-lorebook-sets D4）。
 */
let presetInheritSetId = null;
/** 步骤③的本世界世界书副本候选（含孤儿），来源步渲染用 */
let presetSources = [];
/**
 * 步骤①「命定身份」下拉数据源（preset-destined-figures）：四人物档案，
 * 由 loadDestinedFigures() 按物化 worldId 拉取；拉取失败留空（无名新人流程不受影响）。
 */
let presetFigures = [];
/** 步骤①当前选中的命定人物档案（null = 无名新人；姓名/阵营锁定联动见 applyFigureLock） */
let presetFigure = null;

// ===== 星阶/阵营中文标签 =====
// 真源：CharacterOriginService#rankToLabel 与三大阵营常量。后端把内部码（Gold / saint）
// 直接写进 playerStates 供 GM 与占位符使用，故此处前端复刻一份中文映射仅供展示。
// ⚠️ 后端新增星阶或改码时这里会静默漂移，届时兜底显示原始码（不会白屏）。
const STAR_RANK_LABELS = {
    'Gold': '黄金圣斗士',
    'Silver': '白银圣斗士',
    'Bronze': '青铜圣斗士',
    'Hidden': '隐藏星座守护者',
    'Specter-TianGang': '天罡冥斗士',
    'Specter-DiSha': '地煞冥斗士',
    'GodWarrior': '神斗士'
};
const FACTION_LABELS = {
    'saint': '圣斗士（88 星座）',
    'specter': '冥斗士（108 冥星）',
    'godwarrior': '神斗士（7 主星）'
};
// 属性名同样是后端内部码，且是封闭集（真源：StarBonusMap.ATTRIBUTES，共 7 项）。
// 结果卡里星阶/阵营已中文化，属性名跟着英文会很突兀，故一并映射。
const ATTR_LABELS = {
    'strength': '力量',
    'agility': '敏捷',
    'wisdom': '智慧',
    'endurance': '耐力',
    'healing': '治愈',
    'stamina': '体力',
    'righteousness': '义理'
};

/** 内部码 → 中文标签，未收录时原样返回 */
function labelOf(table, code) {
    return table[code] || code || '未知';
}

// ===== 小宇宙只读行渲染（rpg-cosmo-sense）=====
// 分档表与 cosmoRender / cosmoSenseLabel 定义在 game.js（早于本文件加载），
// 此处直接复用全局 cosmoRender，与状态面板共用同一份渲染，避免多处漂移。

/** 更新步骤②小宇宙只读行（值 + 显隐），推演与重 roll 共用 */
function renderCosmoRow(result) {
    const row = document.getElementById('presetCosmoRow');
    const val = document.getElementById('presetCosmoValue');
    if (!row || !val) return;
    if (result && result.cosmoIntensity !== null && result.cosmoIntensity !== undefined) {
        val.textContent = cosmoRender(result.cosmoIntensity);
        row.hidden = false;
    } else {
        row.hidden = true;
    }
}

// ===== 步骤切换 =====
/**
 * 切换向导三步，同步步骤指示条的高亮态。
 * @param {1|2|3} step 目标步骤
 */
function showPresetStep(step) {
    document.getElementById('presetStep1').hidden = step !== 1;
    document.getElementById('presetStep2').hidden = step !== 2;
    document.getElementById('presetStep3').hidden = step !== 3;
    document.getElementById('presetStepTag1').classList.toggle('active', step === 1);
    document.getElementById('presetStepTag2').classList.toggle('active', step === 2);
    document.getElementById('presetStepTag3').classList.toggle('active', step === 3);
}

/** 按钮忙碌态：禁用 + 换文案，返回还原函数 */
function busyState(btn, busyText) {
    const original = btn.textContent;
    btn.disabled = true;
    btn.textContent = busyText;
    return () => { btn.disabled = false; btn.textContent = original; };
}

/** 统一解析错误响应体，尽量取后端给的 error 文案 */
async function readError(res) {
    const text = await res.text().catch(() => '');
    try {
        const json = JSON.parse(text);
        if (json && json.error) return json.error;
    } catch (_) { /* 非 JSON 响应，退回截断原文 */ }
    return text.substring(0, 200) || ('HTTP ' + res.status);
}

// ===== 入口：物化预设世界并落地向导 =====
/**
 * 物化预设世界（幂等 upsert）并切到向导步骤①。
 * 由 init.js 在读到 URL 的 ?preset= 参数时调用。
 *
 * @param {string} presetKey 预设键（当前仅 adventure，后端有白名单）
 * @returns {Promise<boolean>} 成功落地向导返回 true；失败返回 false（调用方应落首页）
 */
async function enterPresetWizard(presetKey) {
    try {
        const res = await fetch('/rpg/preset/materialize', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({presetKey})
        });
        if (!res.ok) {
            alert('预设世界加载失败: ' + await readError(res));
            return false;
        }
        const world = await res.json();
        presetWorldId = world.worldId;
        presetWorldName = world.worldName;
    } catch (e) {
        alert('预设世界加载失败: ' + e.message);
        return false;
    }

    // 每次进向导都回到步骤①，但保留上次填过的生辰（用户可能只是想改个阵营重推）
    presetOriginInput = null;
    presetOriginResult = null;
    // 命定身份复位为无名新人并解除姓名/阵营锁定（下拉选项随后重拉渲染）
    presetFigure = null;
    document.getElementById('presetDestinedFigure').value = '';
    applyFigureLock();
    showPresetStep(1);
    showView('preset');
    loadDestinedFigures();
    return true;
}

// ===== 公历生日年/月/日联动 select（preset-birthdate-input）=====
// 替代原生 <input type="date">：其弹出日历年份轨道以当前年为中心只开小窗，
// 选 1991 这类出生年要滚约 35 年；三联动 select 下拉定位是秒级操作。
// 日选项按已选年/月实时重建（含闰年二月判定），从源头杜绝 2 月 31 日这类非法组合。
/** 年选项下限：星命计算只用年份做年柱天干，1900 已覆盖全部在世人群 */
const BIRTH_YEAR_MIN = 1900;

/** 闰年判定（公历）：能被 4 整除且不能被 100 整除，或能被 400 整除 */
function isLeapYear(year) {
    return (year % 4 === 0 && year % 100 !== 0) || (year % 400 === 0);
}

/** 某年某月的天数；二月由闰年判定 */
function daysInMonth(year, month) {
    if (month === 2) return isLeapYear(year) ? 29 : 28;
    return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

/**
 * 重建 select 选项：保留空占位项后追加数值项。
 * @param {HTMLSelectElement} select 目标 select
 * @param {string} placeholder 占位文案（如「日」）
 * @param {number[]} values 数值选项列表
 * @param {number} keepValue 重建后需保持选中的值；不在新选项范围内时回落占位项
 */
function rebuildSelectOptions(select, placeholder, values, keepValue) {
    select.innerHTML = '';
    const ph = document.createElement('option');
    ph.value = '';
    ph.textContent = placeholder;
    select.appendChild(ph);
    values.forEach(v => {
        const opt = document.createElement('option');
        opt.value = String(v);
        opt.textContent = String(v);
        select.appendChild(opt);
    });
    select.value = values.includes(keepValue) ? String(keepValue) : '';
}

/** 初始化年 select：页面运行时当前年至 1900 倒序 */
function initBirthYearOptions() {
    const years = [];
    for (let y = new Date().getFullYear(); y >= BIRTH_YEAR_MIN; y--) years.push(y);
    rebuildSelectOptions(document.getElementById('presetOriginBirthYear'), '年', years, NaN);
}

/** 按已选年/月重建日选项；年或月未选全时仅留占位项；已选日越界时 clamp 到该月最后一天 */
function refreshBirthDayOptions() {
    const year = parseInt(document.getElementById('presetOriginBirthYear').value, 10);
    const month = parseInt(document.getElementById('presetOriginBirthMonth').value, 10);
    const daySelect = document.getElementById('presetOriginBirthDay');
    const keepDay = parseInt(daySelect.value, 10);
    if (!year || !month) {
        rebuildSelectOptions(daySelect, '日', [], NaN);
        return;
    }
    const total = daysInMonth(year, month);
    const days = [];
    for (let d = 1; d <= total; d++) days.push(d);
    // clamp：已选日超出新月天数时落到该月最后一天（如 1 月 31 日 → 4 月 30 日），范围内保持原选
    const clamped = isNaN(keepDay) ? NaN : Math.min(keepDay, total);
    rebuildSelectOptions(daySelect, '日', days, clamped);
}

/**
 * 三 select 拼装为 yyyy-MM-dd 字符串（月/日补零两位）；
 * 任一 select 处于占位项时返回空串，由调用方按既有文案拦截。
 */
function assembleBirthDate() {
    const year = document.getElementById('presetOriginBirthYear').value;
    const month = document.getElementById('presetOriginBirthMonth').value;
    const day = document.getElementById('presetOriginBirthDay').value;
    if (!year || !month || !day) return '';
    return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
}

initBirthYearOptions();
document.getElementById('presetOriginBirthYear').addEventListener('change', refreshBirthDayOptions);
document.getElementById('presetOriginBirthMonth').addEventListener('change', refreshBirthDayOptions);

// ===== 命定身份下拉（preset-destined-figures）=====
/**
 * 人物选项一句话文案（spec「人物列表加载」）：绑定人物显示预绑星命
 * （如「长尾景虎 · 处女座黄金圣衣」）；信胜（档案 faction 空）显示「星命自推」。
 * 圣衣/冥衣词按阵营推导，星座名直接取档案 constellation（信长的星座位就是「天魁星」）。
 */
function figureOptionLabel(f) {
    if (!f.faction) return `${f.displayName} · 星命自推`;
    const robe = f.faction === 'saint' ? '黄金圣衣' : '冥衣';
    return `${f.displayName} · ${f.constellation}${robe}`;
}

/**
 * 拉取四人物档案渲染下拉（enterPresetWizard 落地后异步调用）。
 * 失败静默保留「无名新人」占位选项——人物入口缺位不应阻断现行流程。
 */
async function loadDestinedFigures() {
    try {
        const res = await fetch(`/rpg/workshop/destined-figures?worldId=${encodeURIComponent(presetWorldId)}`);
        if (!res.ok) return;
        presetFigures = await res.json();
    } catch (_) {
        return; // 网络失败：仅留无名新人选项
    }
    const select = document.getElementById('presetDestinedFigure');
    select.innerHTML = '';
    const ph = document.createElement('option');
    ph.value = '';
    ph.textContent = '无名新人（星命自推）';
    select.appendChild(ph);
    presetFigures.forEach(f => {
        const opt = document.createElement('option');
        opt.value = f.figureKey;
        opt.textContent = figureOptionLabel(f);
        select.appendChild(opt);
    });
}

/**
 * 表单联动（design D6）：选绑定人物 → 姓名预填锁定（readonly）、阵营锁定并视觉标注；
 * 选信胜（档案 faction 空）→ 仅锁姓名；留空 → 全表单恢复自由（姓名保留现值可编辑）。
 */
function applyFigureLock() {
    const nameInput = document.getElementById('presetOriginName');
    const factionSelect = document.getElementById('presetOriginFaction');
    const badge = document.getElementById('presetFactionLockBadge');
    if (presetFigure) {
        nameInput.value = presetFigure.displayName;
        nameInput.readOnly = true;
        if (presetFigure.faction) {
            factionSelect.value = presetFigure.faction;
            factionSelect.disabled = true;
            badge.hidden = false;
        } else {
            factionSelect.disabled = false;
            badge.hidden = true;
        }
    } else {
        nameInput.readOnly = false;
        factionSelect.disabled = false;
        badge.hidden = true;
    }
}

document.getElementById('presetDestinedFigure').addEventListener('change', () => {
    const key = document.getElementById('presetDestinedFigure').value;
    presetFigure = presetFigures.find(f => f.figureKey === key) || null;
    applyFigureLock();
});

// ===== 步骤①：星命推演 =====
document.getElementById('presetCalcBtn').addEventListener('click', async () => {
    const name = document.getElementById('presetOriginName').value.trim();
    const gender = document.getElementById('presetOriginGender').value;
    const birthDate = assembleBirthDate();
    const timeIndex = parseInt(document.getElementById('presetOriginTimeIndex').value, 10);
    const faction = document.getElementById('presetOriginFaction').value;

    // 必填校验前置：缺字段时不发请求，避免后端 400 再绕一圈
    if (!name) { alert('请输入姓名'); return; }
    if (!birthDate) { alert('请选择公历生日'); return; }

    const btn = document.getElementById('presetCalcBtn');
    const restore = busyState(btn, '⏳ 推演中...');
    presetRollSeed = 0; // 重新推演重置重 roll 种子
    try {
        // 命定人物模式（preset-destined-figures）：请求带 figureKey + worldId，
        // 后端按档案跳漏斗/取预绑星命；缺省不带（契约向后兼容）
        const figureKey = presetFigure ? presetFigure.figureKey : null;
        const body = {name, gender, birthDate, timeIndex, faction, rollSeed: presetRollSeed};
        if (figureKey) { body.figureKey = figureKey; body.worldId = presetWorldId; }
        const res = await fetch('/rpg/workshop/character-origin', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        });
        if (!res.ok) { alert('推演失败: ' + await readError(res)); return; }

        const result = await res.json();
        presetOriginResult = result;
        // figureKey/worldId 一并记入：cosmo 重 roll、/start 重算与 LLM 三件套人物
        // prompt 都从 presetOriginInput 读上下文（人物模式上下文不丢）
        presetOriginInput = {name, gender, birthDate, timeIndex, faction, rollSeed: presetRollSeed};
        if (figureKey) { presetOriginInput.figureKey = figureKey; presetOriginInput.worldId = presetWorldId; }
        renderOriginResult(result);
        prefillCharFields(result);
        showPresetStep(2);
    } catch (e) {
        alert('推演失败: ' + e.message);
    } finally { restore(); }
});

/** 渲染推演结果卡（复用工坊面板时代的 .origin-result / .origin-tech* 样式） */
function renderOriginResult(result) {
    const bonusText = result.starBonus && Object.keys(result.starBonus).length > 0
        ? Object.entries(result.starBonus)
            .map(([k, v]) => `${labelOf(ATTR_LABELS, k)}+${v}`).join('、')
        : '无';
    // 招式树展示（rpg-technique-tree-data 7.1）：按 tier 拓扑深度缩进，三态配色——
    // 已学高亮 + 熟练度数值/档位；可悟（前置全已学）琥珀色；封印灰显并标注前置。
    // 档位措辞复用 game.js 的 techProfRender（后端 TechniqueProficiency 镜像表），避免多处漂移。
    const techs = result.techniques || [];
    const learnedSet = new Set(techs.filter(t => t.learned).map(t => t.name));
    const techHtml = techs.length > 0
        ? techs.slice()
            .sort((a, b) => (a.tier || 1) - (b.tier || 1))
            .map(t => {
                const tier = t.tier || 1;
                const prereqs = t.prerequisites || [];
                const cls = t.learned ? 'tech-learned'
                    : (prereqs.every(p => learnedSet.has(p)) ? 'tech-unlockable' : 'tech-locked');
                const state = t.learned
                    ? `<span class="origin-tech-prof">${techProfRender(t.proficiency)}</span>`
                    : `<span class="origin-tech-prereq">${prereqs.length > 0
                        ? (cls === 'tech-unlockable' ? '可悟·需 ' : '需 ') + prereqs.join('、')
                        : '未学会'}</span>`;
                return `
            <div class="origin-tech ${cls}" style="margin-left:${tier}em">
                <span class="origin-tech-name">${t.name}</span>
                <span class="origin-tech-src">[${t.source}]</span>
                ${state}
                <div class="origin-tech-desc">${t.description}</div>
            </div>`;
            }).join('')
        : '<div class="origin-tech-empty">无招式数据</div>';

    document.getElementById('presetOriginResult').innerHTML = `
        <div><strong>星座/星辰：</strong>${result.constellation}</div>
        <div><strong>星阶：</strong>${labelOf(STAR_RANK_LABELS, result.starRank)}</div>
        <div><strong>阵营：</strong>${labelOf(FACTION_LABELS, result.faction)}</div>
        <div><strong>特质：</strong>${result.zodiacTraits || '无'}</div>
        <div><strong>属性加成：</strong>${bonusText}</div>
        <div class="origin-tech-title"><strong>招式：</strong></div>
        ${techHtml}`;
    renderCosmoRow(result);
}

/** 用后端建议预填三个可编辑字段（用户在步骤② 可自由改写） */
function prefillCharFields(result) {
    document.getElementById('presetCharIdentity').value = result.identitySuggestion || '';
    document.getElementById('presetCharPersonality').value = result.personalitySuggestion || '';
    document.getElementById('presetCharBackground').value = result.backgroundSuggestion || '';
}

// ===== 步骤② → 步骤①：重新推演 =====
document.getElementById('presetBackBtn').addEventListener('click', () => {
    // 刻意不清空表单与已填内容：用户通常只是想改个时辰或阵营
    showPresetStep(1);
});

// ===== 步骤②：LLM 星命改写三件套（preset-llm-identity-trio）=====
// 上下文 = 姓名/性别 + 星命推演结果（不喂用户当前三件套，语义是「按星命改写」）。
// 成功直接覆盖三输入框、无 confirm 可连点抽卡；失败保留原值并 alert 后端文案。
// 只碰三个输入框：小宇宙行/重roll/推演结果卡均不受影响。
document.getElementById('presetLlmTrioBtn').addEventListener('click', async () => {
    if (!presetOriginInput || !presetOriginResult) { alert('请先完成星命推演'); return; }

    const btn = document.getElementById('presetLlmTrioBtn');
    const restore = busyState(btn, '⏳ 改写中...');
    try {
        const res = await fetch('/rpg/workshop/generate-origin-trio', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                name: presetOriginInput.name,
                gender: presetOriginInput.gender,
                constellation: presetOriginResult.constellation,
                starRank: presetOriginResult.starRank,
                faction: presetOriginResult.faction,
                zodiacTraits: presetOriginResult.zodiacTraits,
                techniques: (presetOriginResult.techniques || []).map(t => t.name),
                // 命定人物模式（preset-destined-figures 6.2）：人物名与档案底稿入 LLM
                // prompt（「按星命 + 人物身份改写」）；无名新人不带 figureKey（零改动）
                ...(presetOriginInput.figureKey
                    ? {figureKey: presetOriginInput.figureKey, worldId: presetWorldId}
                    : {})
            })
        });
        if (!res.ok) { alert('LLM 改写失败: ' + await readError(res)); return; }
        const trio = await res.json();
        document.getElementById('presetCharIdentity').value = trio.identity || '';
        document.getElementById('presetCharPersonality').value = trio.personality || '';
        document.getElementById('presetCharBackground').value = trio.background || '';
    } catch (e) {
        alert('LLM 改写失败: ' + e.message);
    } finally { restore(); }
});

// ===== 步骤②：小宇宙重 roll（rpg-cosmo-sense）=====
// 小宇宙不可手改，只能重抽：seed+1 重调 /character-origin。
// 五要素不变、仅 seed 变，故星座/星阶/阵营/特质/招式全部不变，只有 cosmoIntensity 换值。
// 刻意只更新 cosmo 行与 presetOriginResult.cosmoIntensity，不调 prefillCharFields、不重渲染整张卡：
// 用户可能已改过三件套或点过 LLM 改写，重 roll 不该冲掉这些输入。
document.getElementById('presetCosmoRerollBtn').addEventListener('click', async () => {
    if (!presetOriginInput || !presetOriginResult) { alert('请先完成星命推演'); return; }

    const btn = document.getElementById('presetCosmoRerollBtn');
    const restore = busyState(btn, '⏳ 重抽中...');
    const nextSeed = presetRollSeed + 1;
    try {
        const res = await fetch('/rpg/workshop/character-origin', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({...presetOriginInput, rollSeed: nextSeed})
        });
        if (!res.ok) { alert('重抽失败: ' + await readError(res)); return; }
        const result = await res.json();
        // 成功后才提交 seed，失败保留原值（与 LLM 改写一致的保守语义）
        presetRollSeed = nextSeed;
        presetOriginInput = {...presetOriginInput, rollSeed: nextSeed};
        presetOriginResult.cosmoIntensity = result.cosmoIntensity;
        renderCosmoRow(presetOriginResult);
    } catch (e) {
        alert('重抽失败: ' + e.message);
    } finally { restore(); }
});

// ===== 步骤② → 步骤③：校验身份后进入资料来源步 =====
document.getElementById('presetEnterBtn').addEventListener('click', async () => {
    if (!presetWorldId) { alert('预设世界尚未就绪，请返回首页重新进入'); return; }
    if (!presetOriginInput || !presetOriginResult) { alert('请先完成星命推演'); return; }
    const identity = document.getElementById('presetCharIdentity').value.trim();
    if (!identity) { alert('请填写身份（可从星命建议改写）'); return; }
    // 进入步骤③：默认选「世界默认」，加载本世界可继承的世界书副本
    presetInheritSetId = null;
    showPresetStep(3);
    await loadPresetSources();
});

// ===== 步骤③ → 步骤②：返回修改身份 =====
document.getElementById('presetSourceBackBtn').addEventListener('click', () => {
    showPresetStep(2);
});

// ===== 步骤③：加载/渲染资料来源列表（复用 lorebook.js 的共用渲染函数）=====
async function loadPresetSources() {
    const container = document.getElementById('presetSourceList');
    container.replaceChildren(lorebookHintNode('加载中…'));
    presetSources = await fetchLorebookSources(presetWorldId);
    renderPresetSources();
}

function renderPresetSources() {
    renderLorebookSourceList(document.getElementById('presetSourceList'), presetSources,
        presetInheritSetId,
        setId => { presetInheritSetId = setId; renderPresetSources(); },
        () => loadPresetSources());
}

// ===== 步骤③：建卡 + 开局（携带 inheritSetId）=====
document.getElementById('presetStartBtn').addEventListener('click', async () => {
    if (!presetWorldId) { alert('预设世界尚未就绪，请返回首页重新进入'); return; }
    if (!presetOriginInput || !presetOriginResult) { alert('请先完成星命推演'); return; }

    const identity = document.getElementById('presetCharIdentity').value.trim();
    if (!identity) { alert('请填写身份（可从星命建议改写）'); return; }

    const btn = document.getElementById('presetStartBtn');
    const restore = busyState(btn, '⏳ 入世中...');
    try {
        // 1) 每局新建角色卡：名字带星座，避免与既有角色重名混淆（design D7）
        const cardName = `${presetOriginInput.name}·${presetOriginResult.constellation}`;
        const charRes = await fetch('/rpg/workshop/character', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                worldId: presetWorldId,
                name: cardName,
                type: 'player',
                identity,
                personality: document.getElementById('presetCharPersonality').value.trim(),
                background: document.getElementById('presetCharBackground').value.trim(),
                motivation: '',
                speechStyle: '',
                knowledge: '[]'
            })
        });
        if (!charRes.ok) { alert('创建角色卡失败: ' + await readError(charRes)); return; }
        const card = await charRes.json();

        // 2) 开局：带生辰五要素（后端重算星命注入 playerStates）+ 命定人物 figureKey
        //    （人物模式：...presetOriginInput 展开，后端查档案走 7 参 calculate 落 figure 三元组）
        //    + 资料来源 inheritSetId
        const sessionId = crypto.randomUUID();
        const startRes = await fetch('/rpg/workshop/start', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                worldId: presetWorldId,
                playerCharId: card.id,
                sessionId,
                ...presetOriginInput,
                inheritSetId: presetInheritSetId
            })
        });
        if (!startRes.ok) { alert('启动游戏失败: ' + await readError(startRes)); return; }
        const gs = await startRes.json();

        // 3) 写入全局身份并落地游戏视图
        currentSessionId = sessionId;
        currentGameStateId = gs.id;
        currentWorldId = presetWorldId;
        currentCharId = card.id;
        persistIdentity();
        showView('game');
        clearMessages();
        await startOpening(gs.id);
    } catch (e) {
        alert('启动游戏失败: ' + e.message);
    } finally { restore(); }
});
