/**
 * 创建作品模式（works-view-and-world-publish D7：原工坊）：Tab 切换、世界观/角色卡/
 * 地点/触发器/关系 CRUD、LLM 生成、批量中文化改名（rpg-js-split 拆分自 rpg.js；
 * rpg-home-entry：开始冒险选择器已迁至 game.js，编辑器回归纯创作工具）
 *
 * preset-rpg-entry：星命推演（originCalcBtn 监听与 lastOriginResult/lastOriginInput 缓存）
 * 已从本文件移除——它在这里是条断头路（缓存无消费方，/start 从不携带生辰），
 * 现在唯一入口是 preset.js 的三步向导。
 *
 * 本文件产出的全局符号：
 *   变量：savedChars
 *   函数：resetWorldForm, loadWorldsIntoSelector, loadCharsIntoSelector,
 *         loadCharacterIntoForm, resetCharForm, formatLocalizeSummary,
 *         saveWorldForm/saveCharForm/saveLocForm/saveTriggerForm/saveRelForm/
 *         saveWorldbookForm/saveStarForm（works-playtest-entry D1：七个保存
 *         listener 体抽取为返回 boolean 的独立函数，试玩编排复用）、
 *         activateWorkshopTab/playtestFromEdit（works-playtest-entry D2/D3：
 *         tab 切换单源函数与编辑视图「▶ 试玩」编排入口）,
 *         refreshWorkshopWorldbook（add-rpg-worldbook：随世界刷新可编辑条目）,
 *         refreshWorkshopLocations/refreshWorkshopTriggers/
 *         refreshWorkshopRelationships/refreshWorkshopStars（workshop-soft-delete-cascade：
 *         三件套列表 + 世界书 tab 双轨的冥星轨，均随选中世界联动刷新）,
 *         deleteWorldById/renderWorks（「你的作品」卡：点卡进编辑视图并载入、
 *         卡上悬浮钮族「▶」就地开局 /「🚀」发布 /「⬇」撤销 /「🗑️」直接删，
 *         墙尾「＋」卡进新建态）,
 *         enterWorksSelect/enterWorksEdit（作品两级视图切换：showView('works')
 *         落作品选择首屏，点卡/新建卡进编辑视图，返回按钮回作品首屏）,
 *         fetchPublishedPackMap/publishWorld/revokeWorldPublish（发布经济 5.1/5.4：
 *         橱窗回查已发布映射、一键发布/覆盖与撤销，完成后刷墙联动）
 *   依赖（运行时）：state.js（currentWorldId 等）；gallery.js（fetchWorldCards/
 *         buildWorldCard：作品卡复用画廊渲染器）；game.js（worldCharCards 缓存失效：
 *         expandWorldCardChips：「▶」钮试玩链路；renderCharChips/startGameWith：
 *         编辑视图试玩选角与开局（works-playtest-entry D2/D3）；
 *         showCompactToast：发布/撤销 toast）；
 *         story-graph.js（refreshWorkshopStoryGraphs：故事图清单随世界联动刷新，
 *         story-graph-authoring 3.2）
 */

// ===== 工坊模式 Tab 切换 =====
/** 切到指定 tab（data-tab 名）。单源函数：tab 按钮点击与试玩编排定位出错 tab 共用（works-playtest-entry D3） */
function activateWorkshopTab(tabName) {
    const btn = document.querySelector(`.tab-btn[data-tab="${tabName}"]`);
    if (!btn) return;
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById('tab-' + tabName).classList.add('active');
}
document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => activateWorkshopTab(btn.dataset.tab));
});

// ===== 作品两级视图（作品选择首屏 ↔ 编辑）=====
/**
 * 进作品选择首屏：works 落地视图（showView('works') 后由 game.js 回调），只有作品卡。
 * 每次进入都重刷卡片墙，保存/删除后的最新状态即时可见。
 */
function enterWorksSelect() {
    const selView = document.getElementById('worksSelectView');
    const editView = document.getElementById('worksEditView');
    if (!selView || !editView) return;
    selView.hidden = false;
    editView.hidden = true;
    renderWorks();
}
/** 进编辑视图：点作品卡（随 change 载入该世界）或「＋ 新建」卡（新建态）触发 */
function enterWorksEdit() {
    const selView = document.getElementById('worksSelectView');
    const editView = document.getElementById('worksEditView');
    if (!selView || !editView) return;
    selView.hidden = true;
    editView.hidden = false;
    // 收起上次试玩展开的选角 chips（works-playtest-entry D2：避免陈旧角色列表）
    collapsePlaytestChips();
}
document.getElementById('worksBackBtn').addEventListener('click', () => enterWorksSelect());

// ===== 编辑视图一键试玩（works-playtest-entry D2/D3）=====
/** 收起试玩选角 chips：进编辑视图/切换世界时清除陈旧列表 */
function collapsePlaytestChips() {
    const box = document.getElementById('worksPlaytestChips');
    if (box) { box.hidden = true; box.innerHTML = ''; }
}

/**
 * 试玩编排单入口：「▶ 试玩（保存并开局）」点击触发。依序保存各 tab 编辑中的表单
 * （世界观必执行——新建态靠它取得 worldId；其余空态静默跳过不建空记录），任一步
 * 失败即 alert + 切到出错 tab 中止；全部成功后就地展开角色 chips 选角，点选即
 * startGameWith 真实开局（存档自然进「📜 存档」，与普通开局完全一致，无隔离标记）。
 */
async function playtestFromEdit() {
    // 保存顺序同 tab 排列；设定集（冥星轨）与世界书同 tab，失败一并定位到「世界书」
    const steps = [
        {label: '世界观', tab: 'world',        run: () => saveWorldForm(true)},
        {label: '角色卡', tab: 'character',    run: () => saveCharForm(true)},
        {label: '地点',   tab: 'location',     run: () => saveLocForm(true)},
        {label: '触发器', tab: 'trigger',      run: () => saveTriggerForm(true)},
        {label: '世界书', tab: 'worldbook',    run: () => saveWorldbookForm(true)},
        {label: '关系',   tab: 'relationship', run: () => saveRelForm(true)},
        {label: '设定集', tab: 'worldbook',    run: () => saveStarForm(true)},
    ];
    for (const step of steps) {
        let ok = false;
        try { ok = await step.run(); } catch (e) { console.error('试玩保存异常:', e); }
        if (!ok) {
            // 具体原因已由 saveXxxForm 内 alert 提示，此处补 tab 定位并中止
            activateWorkshopTab(step.tab);
            alert('试玩已中止：「' + step.label + '」未通过校验，已切换到该 tab，请修正后重试');
            return;
        }
    }
    // 二道防线（D3）：新建态保存失败未取得 worldId 时到此拦截
    // （一道防线是 saveWorldForm 必执行，正常不会走到这里）
    if (!currentWorldId) {
        activateWorkshopTab('world');
        alert('请先在「世界观」tab 保存世界观，再试玩');
        return;
    }
    // 保存全部成功：就地展开角色 chips 选角（renderCharChips 由后加载的 game.js
    // 定义，此处运行时调用无碍），点选即 startGameWith 真实开局
    const chipsBox = document.getElementById('worksPlaytestChips');
    chipsBox.hidden = false;
    renderCharChips(chipsBox, currentWorldId, (worldId, charId) => startGameWith(worldId, charId),
        {emptyText: '暂无角色，先去「角色卡」tab 建角色', onEmpty: () => activateWorkshopTab('character')});
}
document.getElementById('worksPlaytestBtn').addEventListener('click', playtestFromEdit);

// ===== 世界观保存与生成 =====
/**
 * 保存世界观表单（works-playtest-entry D1：saveWorldBtn listener 体抽取）。
 * @param skipEmpty 空态短路：试玩编排传 true——已落库世界（有 currentWorldId）且
 *        名称空视为「未在编辑」，静默返回 true 不调接口；手动保存（缺省）保持原
 *        alert 提示。新建态名称必填（试玩依赖保存取得 worldId），空名仍 alert 返回 false。
 * @returns {Promise<boolean>} 是否保存成功（含空态跳过）
 */
async function saveWorldForm(skipEmpty) {
    const themeTokens = buildThemeTokensPayload();
    if (themeTokens === undefined) return false;   // 坏 JSON：已提示作者修复，阻断本次保存
    const customBlocks = buildCustomBlocksPayload();
    if (customBlocks === undefined) return false;  // 坏配置：已提示作者修复，阻断本次保存（rpg-hud-custom-blocks D7）
    const data = {
        name: document.getElementById('worldNameInput').value,
        era: document.getElementById('worldEraInput').value,
        settingDesc: document.getElementById('worldDescInput').value,
        rules: document.getElementById('worldRulesInput').value,
        atmosphere: document.getElementById('worldAtmosphereInput').value,
        themeTokens: themeTokens,
        customBlocks: customBlocks
    };
    if (!data.name) {
        // 空态短路仅对已落库世界生效：worldId 已在手，无新内容可存
        if (skipEmpty && currentWorldId) return true;
        alert('请输入世界名称');
        return false;
    }
    // 编辑已有世界观时带上 id
    if (currentWorldId) {
        data.id = currentWorldId;
    }
    try {
        const res = await fetch('/rpg/workshop/world', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        const world = await res.json();
        currentWorldId = world.id;
        document.getElementById('deleteWorldBtn').hidden = false;
        alert('世界观已保存: ' + world.name);
        renderWorks();
        // 同步更新工坊世界观选择器
        await loadWorldsIntoSelector();
        // 选中当前保存的世界
        const sel = document.getElementById('worldSelect');
        if (sel) sel.value = world.id;
        return true;
    } catch (e) { alert('保存失败: ' + e.message); return false; }
}
document.getElementById('saveWorldBtn').addEventListener('click', () => saveWorldForm());

// ===== 世界观新建与删除 =====
/** 清空世界观表单并进入新建态（保存时走 INSERT） */
function resetWorldForm() {
    currentWorldId = null;
    document.getElementById('worldNameInput').value = '';
    document.getElementById('worldEraInput').value = '';
    document.getElementById('worldDescInput').value = '';
    document.getElementById('worldRulesInput').value = '';
    document.getElementById('worldAtmosphereInput').value = '';
    applyThemeTokensToForm(null);
    applyCustomBlocksToForm(null);
    const sel = document.getElementById('worldSelect');
    if (sel) sel.value = '';
    document.getElementById('deleteWorldBtn').hidden = true;
    refreshCoverPreview(null);
    loadCharsIntoSelector();
    // 设定集只读块（star-lorebook）：新建态整块隐藏
    refreshWorkshopLorebook(null);
    // 通用世界书（add-rpg-worldbook）：新建态清空条目选择器与编辑区
    refreshWorkshopWorldbook(null);
    // 三件套列表（workshop-soft-delete-cascade 6.2）：新建态清空
    refreshWorkshopLocations(null);
    refreshWorkshopTriggers(null);
    refreshWorkshopRelationships(null);
    // 冥星轨（workshop-soft-delete-cascade 7.2）：新建态清空
    refreshWorkshopStars(null);
}

document.getElementById('newWorldBtn').addEventListener('click', resetWorldForm);

// ===== HUD 主题 token（rpg-hud-bubble-components 4.1）：表单 ↔ themeTokens JSON 互转 =====
/** 组合表单为 themeTokens JSON 字符串：CSS 变量 textarea + hudStyle select。
 *  两者均默认（空 JSON + classic）时返回 null（列存 NULL = 组件默认主题）；
 *  坏 JSON 返回 undefined 阻断保存（表单侧即时反馈，与开局注入侧的静默丢弃分工）。 */
function buildThemeTokensPayload() {
    let vars = null;
    const raw = document.getElementById('worldThemeTokensInput').value.trim();
    if (raw) {
        try {
            const parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                vars = parsed;
            } else {
                alert('主题 token 必须是 JSON 对象（如 {"--hud-gold":"#e8c15a"}）');
                return undefined;
            }
        } catch (e) {
            alert('主题 token JSON 解析失败：' + e.message);
            return undefined;
        }
    }
    const style = document.getElementById('worldHudStyleInput').value;
    if (!vars && style === 'classic') return null;
    return JSON.stringify({...vars, hudStyle: style});
}

/** 回显 themeTokens 到表单：hudStyle 弹到 select，其余键留 textarea；NULL/坏 JSON 全默认 */
function applyThemeTokensToForm(themeTokens) {
    document.getElementById('worldHudStyleInput').value = 'classic';
    document.getElementById('worldThemeTokensInput').value = '';
    if (!themeTokens) return;
    try {
        const parsed = JSON.parse(themeTokens);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return;
        const rest = {...parsed};
        if (rest.hudStyle === 'classic' || rest.hudStyle === 'compact' || rest.hudStyle === 'minimal') {
            document.getElementById('worldHudStyleInput').value = rest.hudStyle;
            delete rest.hudStyle;
        }
        if (Object.keys(rest).length) {
            document.getElementById('worldThemeTokensInput').value = JSON.stringify(rest, null, 2);
        }
    } catch (e) { console.warn('themeTokens 回显解析失败（按默认主题）:', e); }
}

// ===== 作者自定义 HUD 内容块（rpg-hud-custom-blocks 2.2）：表单 ↔ customBlocks JSON 数组互转 =====
/** field 安全标识符正则（rpg-hud-custom-blocks D4：字母开头、仅字母数字下划线、≤64，
 *  直接拒绝 __proto__/constructor/prototype 与点路径 a.b） */
const CUSTOM_BLOCK_FIELD_RE = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/;
/** accent ∈ 既有主题变量白名单（与 hud.js HUD_TOKEN_VARS 同源维护，不新增变量） */
const CUSTOM_BLOCK_ACCENTS = ['--hud-gold', '--hero-accent', '--npc-accent', '--panel-bg', '--hud-mind-accent'];

/** 组合表单为 customBlocks JSON 字符串：逐块校验（title 限长 / field 安全正则 / accent 白名单）。
 *  空输入 → null（列存 NULL = 无自定义块）；非法输入 alert 阻断保存（表单侧即时反馈，
 *  与渲染侧单块丢弃的分工同 themeTokens 双层校验模式，design D7）。 */
function buildCustomBlocksPayload() {
    const raw = document.getElementById('worldCustomBlocksInput').value.trim();
    if (!raw) return null;
    let parsed;
    try {
        parsed = JSON.parse(raw);
    } catch (e) {
        alert('自定义内容块 JSON 解析失败：' + e.message);
        return undefined;
    }
    if (!Array.isArray(parsed)) {
        alert('自定义内容块必须是 JSON 数组（如 [{"title":"本幕目标","field":"objectives"}]）');
        return undefined;
    }
    if (parsed.length > 5) {
        alert('自定义内容块最多 5 块（提示词与视觉噪音双控）');
        return undefined;
    }
    for (let i = 0; i < parsed.length; i++) {
        const block = parsed[i];
        const at = '自定义内容块第 ' + (i + 1) + ' 项';
        if (!block || typeof block !== 'object' || Array.isArray(block)) {
            alert(at + '必须是对象 {title, field, accent?}');
            return undefined;
        }
        if (typeof block.title !== 'string' || !block.title.trim() || block.title.length > 20) {
            alert(at + '的 title 必须为 1~20 字符的文本');
            return undefined;
        }
        if (typeof block.field !== 'string' || !CUSTOM_BLOCK_FIELD_RE.test(block.field)) {
            alert(at + '的 field 必须以字母开头、仅含字母数字下划线且 ≤64（如 objectives；防 __proto__ 注入）');
            return undefined;
        }
        if (block.accent !== undefined && block.accent !== null
                && !CUSTOM_BLOCK_ACCENTS.includes(block.accent)) {
            alert(at + '的 accent 必须是主题变量之一：' + CUSTOM_BLOCK_ACCENTS.join(' / '));
            return undefined;
        }
    }
    return JSON.stringify(parsed);
}

/** 回显 customBlocks 到表单：合法数组 pretty print 原样（非法项留待保存时逐块阻断提醒）；
 *  NULL/坏 JSON/非数组 → 清空按无块处理（与渲染侧整包丢弃一致）。 */
function applyCustomBlocksToForm(customBlocks) {
    const input = document.getElementById('worldCustomBlocksInput');
    input.value = '';
    if (!customBlocks) return;
    try {
        const parsed = JSON.parse(customBlocks);
        if (!Array.isArray(parsed) || parsed.length === 0) return;
        input.value = JSON.stringify(parsed, null, 2);
    } catch (e) { console.warn('customBlocks 回显解析失败（按无块处理）:', e); }
}

document.getElementById('deleteWorldBtn').addEventListener('click', () => {
    if (!currentWorldId) return;
    deleteWorldById(currentWorldId);
});

/**
 * 删除世界（表单删除按钮与「你的作品」卡删除共用，workshop-soft-delete-cascade D3）：
 * 有存档 409 拒绝；否则级联软删角色/地点/关系/触发器/冥星模板行（通用世界书与封面豁免）。
 * 删的是当前编辑中的世界则回新建态；删的是其他卡则保持当前编辑态。
 */
async function deleteWorldById(worldId, worldName) {
    const label = worldName || '当前世界观';
    if (!confirm(`确认删除「${label}」？此操作不可撤销。\n（旗下有存档时会被拒绝，需先删除存档；无存档时连同角色/地点/关系/触发器/冥星条目一并软删除）`)) return;
    try {
        const res = await fetch(`/rpg/workshop/world/${encodeURIComponent(worldId)}`, {method: 'DELETE'});
        if (res.status === 409) {
            const body = await res.json().catch(() => null);
            alert('删除被拒绝: ' + ((body && body.error) || '存在子数据'));
            return;
        }
        if (!res.ok) { alert('删除失败 (HTTP ' + res.status + ')'); return; }
        // 角色卡缓存作废（状态面板按卡 ID 解析卡名）
        worldCharCards = {};
        cachedCharWorldId = null;
        alert('世界观已删除');
        if (worldId === currentWorldId) {
            resetWorldForm();
        }
        await loadWorldsIntoSelector();
        await renderWorks();
    } catch (e) { alert('删除失败: ' + e.message); }
}

document.getElementById('generateWorldBtn').addEventListener('click', () => {
    document.getElementById('worldLlmInput').hidden = false;
});

document.getElementById('confirmGenerateWorldBtn').addEventListener('click', async () => {
    const keywords = document.getElementById('worldKeywords').value;
    if (!keywords) return;
    const btn = document.getElementById('confirmGenerateWorldBtn');
    const originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = '⏳ 生成中...';
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 60000); // 60s 超时
    try {
        const res = await fetch('/rpg/workshop/generate-world', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({keywords}),
            signal: ctrl.signal
        });
        if (!res.ok) {
            const errText = await res.text().catch(() => '');
            // 内容审查拒答（input-content-moderation）：422 + {moderation:true,error}，
            // 直接呈现拒答话术；其余错误沿用原有 HTTP 状态 + 文本提示。
            let msg = errText.substring(0, 200) || '服务器错误';
            try {
                const errJson = JSON.parse(errText);
                if (errJson && errJson.moderation && errJson.error) {
                    alert(errJson.error);
                    return;
                }
                if (errJson && errJson.error) msg = errJson.error;
            } catch (_) { /* 非 JSON 响应，沿用原文本 */ }
            alert('生成失败 (HTTP ' + res.status + '): ' + msg);
            return;
        }
        const world = await res.json();
        document.getElementById('worldNameInput').value = world.name || '';
        document.getElementById('worldEraInput').value = world.era || '';
        document.getElementById('worldDescInput').value = world.settingDesc || '';
        document.getElementById('worldRulesInput').value = world.rules || '[]';
        document.getElementById('worldAtmosphereInput').value = world.atmosphere || '';
        document.getElementById('worldLlmInput').hidden = true;
    } catch (e) {
        if (e.name === 'AbortError') {
            alert('生成超时，请稍后重试');
        } else {
            alert('生成失败: ' + e.message);
        }
    } finally {
        clearTimeout(timer);
        btn.disabled = false;
        btn.textContent = originalText;
    }
});

// ===== 角色卡保存与生成 =====
/**
 * 保存角色卡表单（works-playtest-entry D1：saveCharBtn listener 体抽取）。
 * @param skipEmpty 空态短路：试玩编排传 true——名称空视为「未在编辑」静默返回 true
 *        不调接口（不建空角色）；手动保存（缺省）保持原 alert 提示。
 * @returns {Promise<boolean>} 是否保存成功（含空态跳过）
 */
async function saveCharForm(skipEmpty) {
    if (!currentWorldId) { alert('请先选择或新建世界观'); return false; }
    const data = {
        worldId: currentWorldId,
        name: document.getElementById('charNameInput').value,
        identity: document.getElementById('charIdentityInput').value,
        personality: document.getElementById('charPersonalityInput').value,
        background: document.getElementById('charBackgroundInput').value,
        motivation: document.getElementById('charMotivationInput').value,
        speechStyle: document.getElementById('charSpeechStyleInput').value,
        knowledge: '[]'
    };
    if (currentCharId) data.id = currentCharId;   // 编辑已有角色时走 UPDATE
    if (!data.name) {
        if (skipEmpty) return true;   // 表单未在编辑：试玩静默跳过
        alert('请输入角色名称');
        return false;
    }
    try {
        const res = await fetch('/rpg/workshop/character', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        const char = await res.json();
        currentCharId = char.id;
        document.getElementById('deleteCharBtn').hidden = false;
        alert('角色卡已保存: ' + char.name);
        await loadCharsIntoSelector();
        // 保存后选中刚保存的角色
        const charSel = document.getElementById('charSelect');
        if (charSel) charSel.value = currentCharId;
        return true;
    } catch (e) { alert('保存失败: ' + e.message); return false; }
}
document.getElementById('saveCharBtn').addEventListener('click', () => saveCharForm());

document.getElementById('generateCharBtn').addEventListener('click', () => {
    document.getElementById('charLlmInput').hidden = false;
});

document.getElementById('confirmGenerateCharBtn').addEventListener('click', async () => {
    const desc = document.getElementById('charDesc').value;
    if (!desc) return;
    const btn = document.getElementById('confirmGenerateCharBtn');
    const originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = '⏳ 生成中...';
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 60000); // 60s 超时
    try {
        const res = await fetch('/rpg/workshop/generate-character', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({description: desc}),
            signal: ctrl.signal
        });
        if (!res.ok) {
            const errText = await res.text().catch(() => '');
            // 内容审查拒答（input-content-moderation）：422 + {moderation:true,error}，
            // 直接呈现拒答话术；其余错误沿用原有 HTTP 状态 + 文本提示。
            let msg = errText.substring(0, 200) || '服务器错误';
            try {
                const errJson = JSON.parse(errText);
                if (errJson && errJson.moderation && errJson.error) {
                    alert(errJson.error);
                    return;
                }
                if (errJson && errJson.error) msg = errJson.error;
            } catch (_) { /* 非 JSON 响应，沿用原文本 */ }
            alert('生成失败 (HTTP ' + res.status + '): ' + msg);
            return;
        }
        const char = await res.json();
        document.getElementById('charNameInput').value = char.name || '';
        document.getElementById('charIdentityInput').value = char.identity || '';
        document.getElementById('charPersonalityInput').value = char.personality || '';
        document.getElementById('charBackgroundInput').value = char.background || '';
        document.getElementById('charMotivationInput').value = char.motivation || '';
        document.getElementById('charSpeechStyleInput').value = char.speechStyle || '';
        document.getElementById('charLlmInput').hidden = true;
    } catch (e) {
        if (e.name === 'AbortError') {
            alert('生成超时，请稍后重试');
        } else {
            alert('生成失败: ' + e.message);
        }
    } finally {
        clearTimeout(timer);
        btn.disabled = false;
        btn.textContent = originalText;
    }
});

// ===== 一键中文化改名（存量罗马音存档的修复入口）=====
document.getElementById('localizeNamesBtn').addEventListener('click', async () => {
    if (!currentWorldId) { alert('请先保存世界观'); return; }
    if (!confirm('把本世界含罗马音/英文的角色名批量改为汉字名？\n'
            + '含：角色卡名字，以及存档里无角色卡的临时 NPC 拼音名（GM 临时创造的人物）。\n'
            + '同时会把存档 npcStates key、触发器与关系引用迁移到角色卡 ID（无卡临时 NPC 迁移到汉字名）。\n'
            + '建议避开回合生成中执行。')) return;

    const btn = document.getElementById('localizeNamesBtn');
    const label = btn.textContent.trim();
    btn.disabled = true;
    btn.textContent = '改名中...';
    try {
        const res = await fetch(`/rpg/workshop/localize-names?worldId=${encodeURIComponent(currentWorldId)}`,
            {method: 'POST'});
        const body = await res.json().catch(() => null);
        if (!res.ok) {
            alert('改名失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return;
        }
        const renames = (body && body.renames) || [];
        if (renames.length === 0) {
            alert('该世界没有需要中文化的名字。');
            return;
        }
        // 卡名与 npcStates key 都变了，缓存必须作废
        worldCharCards = {};
        cachedCharWorldId = null;
        alert(formatLocalizeSummary(renames, body));
        await loadCharsIntoSelector();
    } catch (e) {
        alert('改名失败: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = label;
    }
});

function formatLocalizeSummary(renames, body) {
    const lines = renames.map(r => `・${r.oldName} → ${r.newName}`);
    return `已改名 ${renames.length} 项：\n${lines.join('\n')}\n\n引用迁移：`
        + `存档 ${body.gameStateCount} 个 / npcStates key ${body.npcStateKeyCount} 个 / `
        + `触发器 ${body.triggerCount} 条 / 关系 ${body.relationshipCount} 条 / `
        + `笔记本文件 ${body.notebookFileCount} 个`;
}

// ===== 地点列表与增删改（workshop-soft-delete-cascade 6.2：补列表选择器/回填/新建/删除，对齐角色 tab 模式）=====

/** 当前编辑中的地点 id（null = 新建态，保存走 INSERT） */
let currentLocId = null;
/** 当前世界的已保存地点（选择器数据源，按 id 取完整对象） */
let savedLocations = [];

/** 随选中世界刷新地点选择器（对齐 loadCharsIntoSelector 时机） */
async function refreshWorkshopLocations(worldId) {
    const sel = document.getElementById('locSelect');
    if (!sel) return;
    resetLocForm();
    sel.innerHTML = '<option value="">（新建）</option>';
    savedLocations = [];
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/locations?worldId=${encodeURIComponent(worldId)}`);
        if (!res.ok) return;
        savedLocations = await res.json();
        savedLocations.forEach(l => {
            const opt = document.createElement('option');
            opt.value = l.id;
            opt.textContent = l.name || '(无名称)';
            sel.appendChild(opt);
        });
    } catch (e) { console.error('加载地点列表失败', e); }
}

document.getElementById('locSelect').addEventListener('change', (e) => {
    const locId = e.target.value;
    if (!locId) { resetLocForm(); return; }
    const loc = savedLocations.find(l => l.id === locId);
    if (loc) loadLocIntoForm(loc);
});

/** 把选中地点加载到表单（进入编辑态） */
function loadLocIntoForm(l) {
    currentLocId = l.id;
    document.getElementById('locNameInput').value = l.name || '';
    document.getElementById('locDescInput').value = l.description || '';
    document.getElementById('deleteLocBtn').hidden = false;
}

/** 清空地点表单并进入新建态（保存时走 INSERT） */
function resetLocForm() {
    currentLocId = null;
    document.getElementById('locNameInput').value = '';
    document.getElementById('locDescInput').value = '';
    document.getElementById('deleteLocBtn').hidden = true;
    const sel = document.getElementById('locSelect');
    if (sel) sel.value = '';
}

document.getElementById('newLocBtn').addEventListener('click', resetLocForm);

document.getElementById('saveLocBtn').addEventListener('click', () => saveLocForm());

/**
 * 保存地点表单（works-playtest-entry D1：saveLocBtn listener 体抽取）。
 * @param skipEmpty 空态短路：试玩编排传 true——名称空视为「未在编辑」静默返回 true
 *        不调接口（不建空地点）；手动保存（缺省）保持原 alert 提示。
 * @returns {Promise<boolean>} 是否保存成功（含空态跳过）
 */
async function saveLocForm(skipEmpty) {
    if (!currentWorldId) { alert('请先保存世界观'); return false; }
    const data = {
        worldId: currentWorldId,
        name: document.getElementById('locNameInput').value,
        description: document.getElementById('locDescInput').value,
        npcIds: '[]', triggerIds: '[]'
    };
    if (!data.name) {
        if (skipEmpty) return true;   // 表单未在编辑：试玩静默跳过
        alert('请输入地点名称');
        return false;
    }
    if (currentLocId) data.id = currentLocId;   // 编辑已有地点时走 UPDATE
    try {
        const res = await fetch('/rpg/workshop/location', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('保存失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return false;
        }
        const loc = await res.json();
        currentLocId = loc.id;
        document.getElementById('deleteLocBtn').hidden = false;
        alert('地点已保存: ' + loc.name);
        await refreshWorkshopLocations(currentWorldId);
        const sel = document.getElementById('locSelect');
        if (sel) sel.value = loc.id;
        return true;
    } catch (e) { alert('保存失败: ' + e.message); return false; }
}

document.getElementById('deleteLocBtn').addEventListener('click', async () => {
    if (!currentLocId) return;
    if (!confirm('确认删除当前地点？此操作不可撤销。')) return;
    try {
        const res = await fetch(`/rpg/workshop/location/${encodeURIComponent(currentLocId)}`, {method: 'DELETE'});
        if (res.status === 404) { alert('地点不存在或已删除'); return; }
        if (!res.ok) { alert('删除失败 (HTTP ' + res.status + ')'); return; }
        alert('地点已删除');
        await refreshWorkshopLocations(currentWorldId);
    } catch (e) { alert('删除失败: ' + e.message); }
});

// ===== 触发器列表与增删改（workshop-soft-delete-cascade 6.2：补列表选择器/回填/新建/删除，对齐角色 tab 模式）=====

/** 当前编辑中的触发器 id（null = 新建态，保存走 INSERT） */
let currentTriggerId = null;
/** 当前世界的已保存触发器（选择器数据源，按 id 取完整对象） */
let savedTriggers = [];

/** 随选中世界刷新触发器选择器（对齐 loadCharsIntoSelector 时机）；npcId 用角色名富化显示 */
async function refreshWorkshopTriggers(worldId) {
    const sel = document.getElementById('triggerSelect');
    if (!sel) return;
    resetTriggerForm();
    sel.innerHTML = '<option value="">（新建）</option>';
    savedTriggers = [];
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/triggers?worldId=${encodeURIComponent(worldId)}`);
        if (!res.ok) return;
        savedTriggers = await res.json();
        savedTriggers.forEach(t => {
            const opt = document.createElement('option');
            opt.value = t.id;
            opt.textContent = (t.type === 'event' ? '事件' : '动机') + '·' + (t.action || '?')
                + (t.npcId ? ' @' + resolveRelCharName(t.npcId) : '');
            sel.appendChild(opt);
        });
    } catch (e) { console.error('加载触发器列表失败', e); }
}

document.getElementById('triggerSelect').addEventListener('change', (e) => {
    const triggerId = e.target.value;
    if (!triggerId) { resetTriggerForm(); return; }
    const trigger = savedTriggers.find(t => t.id === triggerId);
    if (trigger) loadTriggerIntoForm(trigger);
});

/** 把选中触发器加载到表单（进入编辑态） */
function loadTriggerIntoForm(t) {
    currentTriggerId = t.id;
    document.getElementById('triggerType').value = t.type || 'event';
    document.getElementById('triggerNpcId').value = t.npcId || '';
    document.getElementById('triggerHardConditions').value = t.hardConditions || '[]';
    document.getElementById('triggerSoftCondition').value = t.softCondition || '';
    document.getElementById('triggerAction').value = t.action || '';
    document.getElementById('triggerCooldown').value = t.cooldown != null ? t.cooldown : 3;
    document.getElementById('triggerProbability').value = t.probability != null ? t.probability : 0.6;
    document.getElementById('deleteTriggerBtn').hidden = false;
}

/** 清空触发器表单并进入新建态（保存时走 INSERT） */
function resetTriggerForm() {
    currentTriggerId = null;
    document.getElementById('triggerType').value = 'event';
    document.getElementById('triggerNpcId').value = '';
    document.getElementById('triggerHardConditions').value = '';
    document.getElementById('triggerSoftCondition').value = '';
    document.getElementById('triggerAction').value = '';
    document.getElementById('triggerCooldown').value = 3;
    document.getElementById('triggerProbability').value = 0.6;
    document.getElementById('deleteTriggerBtn').hidden = true;
    const sel = document.getElementById('triggerSelect');
    if (sel) sel.value = '';
}

document.getElementById('newTriggerBtn').addEventListener('click', resetTriggerForm);

document.getElementById('saveTriggerBtn').addEventListener('click', () => saveTriggerForm());

/**
 * 保存触发器表单（works-playtest-entry D1：saveTriggerBtn listener 体抽取）。
 * @param skipEmpty 空态短路：试玩编排传 true——行动空视为「未在编辑」静默返回 true
 *        不调接口（不建空触发器）；手动保存（缺省）保持原 alert 提示。
 * @returns {Promise<boolean>} 是否保存成功（含空态跳过）
 */
async function saveTriggerForm(skipEmpty) {
    if (!currentWorldId) { alert('请先保存世界观'); return false; }
    const data = {
        worldId: currentWorldId,
        type: document.getElementById('triggerType').value,
        npcId: document.getElementById('triggerNpcId').value,
        hardConditions: document.getElementById('triggerHardConditions').value,
        softCondition: document.getElementById('triggerSoftCondition').value,
        action: document.getElementById('triggerAction').value,
        cooldown: parseInt(document.getElementById('triggerCooldown').value),
        probability: parseFloat(document.getElementById('triggerProbability').value)
    };
    if (!data.action) {
        if (skipEmpty) return true;   // 表单未在编辑：试玩静默跳过
        alert('请输入触发行动');
        return false;
    }
    if (currentTriggerId) data.id = currentTriggerId;   // 编辑已有触发器时走 UPDATE
    try {
        const res = await fetch('/rpg/workshop/trigger', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('保存失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return false;
        }
        const trigger = await res.json();
        currentTriggerId = trigger.id;
        document.getElementById('deleteTriggerBtn').hidden = false;
        alert('触发器已保存: ' + (trigger.type === 'event' ? '事件' : '动机') + '·' + trigger.action);
        await refreshWorkshopTriggers(currentWorldId);
        const sel = document.getElementById('triggerSelect');
        if (sel) sel.value = trigger.id;
        return true;
    } catch (e) { alert('保存失败: ' + e.message); return false; }
}

document.getElementById('deleteTriggerBtn').addEventListener('click', async () => {
    if (!currentTriggerId) return;
    if (!confirm('确认删除当前触发器？此操作不可撤销。\n（对进行中的对局立即停止触发，触发器软删后引擎扫描即时排除）')) return;
    try {
        const res = await fetch(`/rpg/workshop/trigger/${encodeURIComponent(currentTriggerId)}`, {method: 'DELETE'});
        if (res.status === 404) { alert('触发器不存在或已删除'); return; }
        if (!res.ok) { alert('删除失败 (HTTP ' + res.status + ')'); return; }
        alert('触发器已删除（进行中的对局即时停止触发）');
        await refreshWorkshopTriggers(currentWorldId);
    } catch (e) { alert('删除失败: ' + e.message); }
});

// ===== 关系列表与增删改（workshop-soft-delete-cascade 6.2/6.3：补列表选择器/回填/新建/删除，两端角色名富化）=====

/** 当前编辑中的关系 id（null = 新建态，保存走 INSERT；软删对后端自动复活） */
let currentRelId = null;
/** 当前世界的已保存关系（选择器数据源，按 id 取完整对象；经世界角色双向收集去重） */
let savedRelationships = [];

/**
 * 解析角色名（6.3）：工坊 savedChars（当前世界）优先 → 游戏模式 worldCharCards
 * 缓存（game.js，跨世界另一端可能命中）→ 悬空 ID 显示占位。
 */
function resolveRelCharName(charId) {
    if (!charId) return '（空）';
    const card = savedChars.find(c => c.id === charId);
    if (card && card.name) return card.name;
    const cached = typeof worldCharCards !== 'undefined' && worldCharCards[charId];
    if (cached) return cached;
    return `（ID ${charId} 失效）`;
}

/** 关系选项/提示文本：两端角色名（非裸 ID；悬空端显示占位） */
function relOptionLabel(rel) {
    return resolveRelCharName(rel.charAId) + ' ↔ ' + resolveRelCharName(rel.charBId);
}

/** 随选中世界刷新关系选择器（对齐 loadCharsIntoSelector 时机） */
async function refreshWorkshopRelationships(worldId) {
    const sel = document.getElementById('relSelect');
    if (!sel) return;
    resetRelForm();
    sel.innerHTML = '<option value="">（新建）</option>';
    savedRelationships = [];
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/relationships?worldId=${encodeURIComponent(worldId)}`);
        if (!res.ok) return;
        savedRelationships = await res.json();
        savedRelationships.forEach(rel => {
            const opt = document.createElement('option');
            opt.value = rel.id;
            opt.textContent = relOptionLabel(rel);
            sel.appendChild(opt);
        });
    } catch (e) { console.error('加载关系列表失败', e); }
}

document.getElementById('relSelect').addEventListener('change', (e) => {
    const relId = e.target.value;
    if (!relId) { resetRelForm(); return; }
    const rel = savedRelationships.find(r => r.id === relId);
    if (rel) loadRelIntoForm(rel);
});

/** 把选中关系加载到表单（进入编辑态；保存带 id 走 UPDATE） */
function loadRelIntoForm(rel) {
    currentRelId = rel.id;
    document.getElementById('relCharA').value = rel.charAId || '';
    document.getElementById('relCharB').value = rel.charBId || '';
    document.getElementById('relAttitude').value = rel.attitude != null ? rel.attitude : 0;
    document.getElementById('relTrust').value = rel.trust != null ? rel.trust : 50;
    document.getElementById('deleteRelBtn').hidden = false;
}

/** 清空关系表单并进入新建态（保存走 INSERT；若与软删对同对，后端复活原行） */
function resetRelForm() {
    currentRelId = null;
    document.getElementById('relCharA').value = '';
    document.getElementById('relCharB').value = '';
    document.getElementById('relAttitude').value = 0;
    document.getElementById('relTrust').value = 50;
    document.getElementById('deleteRelBtn').hidden = true;
    const sel = document.getElementById('relSelect');
    if (sel) sel.value = '';
}

document.getElementById('newRelBtn').addEventListener('click', resetRelForm);

document.getElementById('saveRelBtn').addEventListener('click', () => saveRelForm());

/**
 * 保存关系表单（works-playtest-entry D1：saveRelBtn listener 体抽取）。
 * @param skipEmpty 空态短路：试玩编排传 true——任一端角色未选视为「未在编辑」
 *        静默返回 true 不调接口（不建空关系）；手动保存（缺省）保持原 alert 提示。
 * @returns {Promise<boolean>} 是否保存成功（含空态跳过）
 */
async function saveRelForm(skipEmpty) {
    const data = {
        charAId: document.getElementById('relCharA').value.trim(),
        charBId: document.getElementById('relCharB').value.trim(),
        attitude: parseInt(document.getElementById('relAttitude').value),
        trust: parseInt(document.getElementById('relTrust').value),
        notes: ''
    };
    if (!data.charAId || !data.charBId) {
        if (skipEmpty) return true;   // 表单未在编辑：试玩静默跳过
        alert('请输入两端角色 ID');
        return false;
    }
    if (data.charAId === data.charBId) { alert('两端不能是同一角色'); return false; }
    if (currentRelId) data.id = currentRelId;   // 编辑态走 UPDATE；新建遇软删对后端复活原行
    try {
        const res = await fetch('/rpg/workshop/relationship', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('保存失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return false;
        }
        const rel = await res.json();
        currentRelId = rel.id;
        document.getElementById('deleteRelBtn').hidden = false;
        alert('关系已保存：' + relOptionLabel(rel));
        await refreshWorkshopRelationships(currentWorldId);
        const sel = document.getElementById('relSelect');
        if (sel) sel.value = rel.id;
        return true;
    } catch (e) { alert('保存失败: ' + e.message); return false; }
}

document.getElementById('deleteRelBtn').addEventListener('click', async () => {
    if (!currentRelId) return;
    if (!confirm('确认删除当前关系？此操作不可撤销。\n（重建同对关系时会复活并覆写原行，不产生新行）')) return;
    try {
        const res = await fetch(`/rpg/workshop/relationship/${encodeURIComponent(currentRelId)}`, {method: 'DELETE'});
        if (res.status === 404) { alert('关系不存在或已删除'); return; }
        if (!res.ok) { alert('删除失败 (HTTP ' + res.status + ')'); return; }
        alert('关系已删除');
        await refreshWorkshopRelationships(currentWorldId);
    } catch (e) { alert('删除失败: ' + e.message); }
});

// ===== 辅助方法 =====

/**
 * 加载已保存的世界观到选择器（只填 worldSelect，不刷作品墙：
 * 墙刷新由各调用方/enterWorksSelect 显式负责——曾在此尾随 fire renderWorks，
 * init.js 落地后的第二次调用会在 ?world= 深链展开之后重刷墙，抹掉展开态，
 * 视图合并后保存/删除路径又都自带刷墙，该副作用已无人依赖，移除）
 */
async function loadWorldsIntoSelector() {
    const sel = document.getElementById('worldSelect');
    if (!sel) return;
    try {
        const res = await fetch('/rpg/workshop/worlds');
        const worlds = await res.json();
        sel.innerHTML = '<option value="">（新建）</option>';
        worlds.forEach(w => {
            const opt = document.createElement('option');
            opt.value = w.id;
            opt.textContent = w.name;
            sel.appendChild(opt);
        });
        // 重建 options 会丢失选中态：按当前编辑世界恢复（删别的作品卡后保持表单与下拉一致）
        if (currentWorldId && worlds.some(w => w.id === currentWorldId)) {
            sel.value = currentWorldId;
        }
    } catch (e) { console.error('加载世界观列表失败', e); }
}

/**
 * 渲染「你的作品」卡片墙（works 首屏）：复用画廊卡片渲染器（gallery.js）与
 * world-wall.css 卡片体系。数据源 gallery?scope=works 纯沙盒行（后端口径，
 * works-view-and-world-publish D9：预设世界由 md 物化维护、重新物化即恢复，不属作品，
 * 前端不再重复过滤）。
 * 点卡 = 进编辑视图并载入该世界（下拉选中并触发 change，与全部子 tab 联动）；
 * 卡上悬浮钮族（D7）：「▶」就地展开角色 chips 开局（复用 expandWorldCardChips
 * 链路）、「🚀」发布/「⬇」撤销（5.1 发布经济）、「🗑️」直接删；
 * 墙尾常驻「＋ 新建」卡 = 进编辑视图并切新建态（空态时是墙里唯一的卡）。
 * <p>
 * 发布态双源：后端 WorldCard.published（权威）+ 橱窗回查映射 originWorldId→packId
 * （撤销钮需 packId；回查失败不阻塞墙渲染，仅本次不显撤销钮）。
 */
async function renderWorksOnce() {
    const box = document.getElementById('worksWorldCards');
    if (!box) return;
    let cards;
    try {
        cards = await fetchWorldCards('works');
    } catch (e) {
        console.error('作品列表加载失败', e);
        box.innerHTML = '<div class="wall-error">作品列表加载失败，请刷新重试</div>';
        return;
    }
    let packMap = {};
    try {
        packMap = await fetchPublishedPackMap();
    } catch (e) {
        console.warn('已发布包映射回查失败（撤销钮本次不可用）:', e);
    }
    box.innerHTML = '';
    (cards || []).forEach(w => {
        const el = buildWorldCard(w, {});
        el.title = '点击载入编辑';
        // 当前编辑中的世界卡描边高亮（复用 expanded 态）
        if (w.id === currentWorldId) el.classList.add('expanded');
        el.addEventListener('click', () => {
            enterWorksEdit();
            const sel = document.getElementById('worldSelect');
            if (!sel || sel.value === w.id) return;
            sel.value = w.id;
            sel.dispatchEvent(new Event('change'));
        });
        // 悬浮「▶」试玩钮（卡右上角，works-playtest-entry D4：语义升级交互不变）：
        // 展开角色 chips 选角开局，存档自然进「📜 存档」；
        // stopPropagation 避免触发点卡载入编辑
        const start = document.createElement('button');
        start.type = 'button';
        start.className = 'works-start-btn';
        start.textContent = '▶';
        start.title = '试玩：选角开局，存档进「📜 存档」';
        start.addEventListener('click', ev => {
            ev.stopPropagation();
            expandWorldCardChips(w.id);
        });
        el.appendChild(start);
        // 发布/撤销钮（5.1）：已发布（packMap 命中）显「⬇ 撤销」，否则「🚀 发布」
        const packId = packMap[w.id];
        if (packId) {
            const revoke = document.createElement('button');
            revoke.type = 'button';
            revoke.className = 'works-revoke-btn';
            revoke.textContent = '⬇';
            revoke.title = '撤销发布：橱窗不再展示、他人不可再导入；已导入副本不受影响；可重新发布覆盖复活';
            revoke.addEventListener('click', ev => {
                ev.stopPropagation();
                revokeWorldPublish(w, packId);
            });
            el.appendChild(revoke);
            // 已发布徽章（5.1：卡身绿色小字行）
            const flag = document.createElement('p');
            flag.className = 'works-published-flag';
            flag.textContent = '🚀 已发布上橱窗';
            el.querySelector('.world-card-body').appendChild(flag);
        } else {
            const pub = document.createElement('button');
            pub.type = 'button';
            pub.className = 'works-publish-btn';
            pub.textContent = '🚀';
            pub.title = '发布到世界橱窗：全家桶时点快照（世界观/角色/地点/关系/触发器/世界书/冥星录/封面），全平台可见可导入';
            pub.addEventListener('click', ev => {
                ev.stopPropagation();
                publishWorld(w);
            });
            el.appendChild(pub);
        }
        // 悬浮删除钮（卡右上角小图标，rpg.css absolute 定位）：不占卡高；
        // stopPropagation 避免触发点卡载入
        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'works-del-btn';
        del.textContent = '🗑️';
        del.title = '删除此世界（旗下有存档时会被拒绝；否则级联软删全部子数据）';
        del.addEventListener('click', ev => {
            ev.stopPropagation();
            deleteWorldById(w.id, w.name);
        });
        el.appendChild(del);
        box.appendChild(el);
    });
    // 墙尾常驻「＋ 新建」卡：空态时是墙里唯一的卡，点击进新建表单（复用 resetWorldForm 全量新建态切换）
    const addCard = document.createElement('div');
    addCard.className = 'works-new-card';
    addCard.title = '新建世界观';
    addCard.innerHTML = '<span class="works-new-plus">＋</span><span class="works-new-label">新建世界观</span>';
    addCard.addEventListener('click', () => {
        enterWorksEdit();
        resetWorldForm();
        // 滚动到编辑表单并聚焦名称框，衔接「点卡 → 填表单」动作链
        const name = document.getElementById('worldNameInput');
        if (name) {
            name.scrollIntoView({behavior: 'smooth', block: 'center'});
            name.focus({preventScroll: true});
        }
    });
    box.appendChild(addCard);
}

/**
 * renderWorks 单飞包装（in-flight 去重）：showView('works') 内 enterWorksSelect 会
 * fire 一刷且不等待，goWorks 紧随 await 再起一刷——双刷竞速时后完成者的 innerHTML
 * 重渲染会抹掉 ?world= 深链刚展开的角色 chips（导入落地展开丢失，冒烟实测复现）。
 * 并发调用共享同一次渲染，等待方拿到的墙必然是“渲染完成且不再被重刷”的稳定态。
 */
let renderWorksInFlight = null;

async function renderWorks() {
    if (renderWorksInFlight) return renderWorksInFlight;
    renderWorksInFlight = renderWorksOnce().finally(() => { renderWorksInFlight = null; });
    return renderWorksInFlight;
}

// ===== 发布经济（works-view-and-world-publish 5.1/5.4）=====
/**
 * 本人已发布包映射 originWorldId → packId：作品墙发布态与撤销钮数据源。
 * 橱窗 showcase 回查（publisherSelf 卡）；失败抛错由调用方降级。
 */
async function fetchPublishedPackMap() {
    const cards = await fetchShowcase();
    const map = {};
    (cards || []).filter(c => c.source === 'published' && c.publisherSelf && c.originWorldId)
        .forEach(c => { map[c.originWorldId] = c.id; });
    return map;
}

/**
 * 发布/覆盖发布世界快照（POST /rpg/shared-worlds {worldId}）。
 * 确认文案明示快照语义与覆盖规则；成功 toast + 刷墙（5.4 联动）。
 */
async function publishWorld(w) {
    const overwriteHint = w.published ? '\n你之前发布过该世界，本次将覆盖旧包。' : '';
    if (!confirm(`确认发布「${w.name}」到世界橱窗？\n\n发布的是当前世界全家桶的时点快照（世界观 / 角色 / 地点 / 关系 / 触发器 / 世界书 / 冥星录 / 封面）：\n· 全平台登录用户可见可导入\n· 此后源世界的修改不影响已发布版本，直到你再次发布覆盖${overwriteHint}`)) return;
    try {
        const res = await fetch('/rpg/shared-worlds', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({worldId: w.id})
        });
        const body = await res.json().catch(() => null);
        if (!res.ok) {
            alert('发布失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return;
        }
        showCompactToast(body && body.overwrite
            ? `✅ 已覆盖更新「${w.name}」（共 ${body.entryCount} 条子数据）`
            : `🚀 「${w.name}」已发布上橱窗（共 ${body.entryCount} 条子数据）`);
        renderWorks();
    } catch (e) { alert('发布失败: ' + e.message); }
}

/**
 * 撤销发布（DELETE /rpg/shared-worlds/{packId}，软下架）。
 * 确认文案明示已导入副本不受影响；成功 toast + 刷墙（5.4 联动）。
 */
async function revokeWorldPublish(w, packId) {
    if (!confirm(`确认撤销发布「${w.name}」？\n\n· 橱窗不再展示、他人不可再导入\n· 已导入的副本不受影响（快照哲学，无回链）\n· 你随时可以重新发布（覆盖旧包即复活）`)) return;
    try {
        const res = await fetch(`/rpg/shared-worlds/${encodeURIComponent(packId)}`, {method: 'DELETE'});
        if (!res.ok) { alert('撤销失败 (HTTP ' + res.status + ')'); return; }
        showCompactToast(`⬇ 已撤销「${w.name}」的发布`);
        renderWorks();
    } catch (e) { alert('撤销失败: ' + e.message); }
}

/** 选择已保存的世界观后，加载其详情到表单 */
document.getElementById('worldSelect').addEventListener('change', async (e) => {
    const worldId = e.target.value;
    // 切世界时收起试玩选角 chips，避免陈旧角色列表（works-playtest-entry D2）
    collapsePlaytestChips();
    // 作品卡高亮随选中切换（只切类，不重渲染，避免封面闪烁）
    document.querySelectorAll('#worksWorldCards .world-card').forEach(c => {
        c.classList.toggle('expanded', !!worldId && c.dataset.id === worldId);
    });
    if (!worldId) {
        // 清空表单，准备新建
        resetWorldForm();
        return;
    }
    try {
        const res = await fetch(`/rpg/workshop/world/${encodeURIComponent(worldId)}`);
        if (!res.ok) return;
        const world = await res.json();
        currentWorldId = world.id;
        document.getElementById('deleteWorldBtn').hidden = false;
        refreshCoverPreview(world.id);
        document.getElementById('worldNameInput').value = world.name || '';
        document.getElementById('worldEraInput').value = world.era || '';
        document.getElementById('worldDescInput').value = world.settingDesc || '';
        document.getElementById('worldRulesInput').value = world.rules || '[]';
        document.getElementById('worldAtmosphereInput').value = world.atmosphere || '';
        applyThemeTokensToForm(world.themeTokens || null);
        applyCustomBlocksToForm(world.customBlocks || null);
        // 切换世界观后角色表单回到新建态，避免旧世界角色误挂到新世界
        resetCharForm();
        loadCharsIntoSelector();
        // 设定集只读块（star-lorebook）：随选中世界刷新（自定义世界/空名录时整块隐藏）
        refreshWorkshopLorebook(worldId);
        // 通用世界书（add-rpg-worldbook）：随选中世界加载可编辑条目
        refreshWorkshopWorldbook(worldId);
        // 三件套列表（workshop-soft-delete-cascade 6.2）：随选中世界联动刷新
        refreshWorkshopLocations(worldId);
        refreshWorkshopTriggers(worldId);
        refreshWorkshopRelationships(worldId);
        // 冥星轨（workshop-soft-delete-cascade 7.2）：随世界刷新（预设只读态在此切换）
        refreshWorkshopStars(worldId);
        // 故事图（story-graph-authoring 3.2）：随世界刷新图清单（story-graph.js 供全函数）
        refreshWorkshopStoryGraphs(worldId);
    } catch (e) { console.error('加载世界观详情失败', e); }
});

// ===== 封面回显与上传（world-card-gallery 9.2）=====
/** 回显封面：img 指向伺服端点，404/无封面显占位；仅已保存世界露出上传按钮 */
function refreshCoverPreview(worldId) {
    const img = document.getElementById('coverPreviewImg');
    const empty = document.getElementById('coverPreviewEmpty');
    const btn = document.getElementById('uploadCoverBtn');
    if (!img) return;
    if (!worldId) {
        img.hidden = true;
        img.removeAttribute('src');
        empty.hidden = false;
        empty.textContent = '无封面';
        if (btn) btn.hidden = true;
        return;
    }
    if (btn) btn.hidden = false;
    // 时间戳绕开长缓存取最新封面（替换上传后 cover 列已指向新文件）
    img.src = `/rpg/worlds/${encodeURIComponent(worldId)}/cover?v=${Date.now()}`;
    img.hidden = false;
    empty.hidden = true;
    img.onerror = () => {
        img.hidden = true;
        empty.hidden = false;
        empty.textContent = '无封面';
    };
}

document.getElementById('uploadCoverBtn').addEventListener('click', async () => {
    if (!currentWorldId) { alert('请先保存世界再上传封面'); return; }
    const input = document.getElementById('coverFileInput');
    const file = input.files && input.files[0];
    if (!file) { alert('请先选择图片（png/jpeg/webp，≤ 4MB）'); return; }
    const form = new FormData();
    form.append('file', file);
    try {
        const res = await fetch(`/rpg/workshop/worlds/${encodeURIComponent(currentWorldId)}/cover`, {
            method: 'POST', body: form
        });
        const body = await res.json().catch(() => null);
        if (!res.ok) {
            alert('封面上传失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return;
        }
        input.value = '';
        refreshCoverPreview(currentWorldId);
        // 同步刷新作品墙，新封面即时上卡
        renderWorks();
        alert('封面已更新');
    } catch (e) { alert('封面上传失败: ' + e.message); }
});

/** 已保存角色选择器的当前列表（选择变更时按 id 取完整卡对象） */
let savedChars = [];

/** 加载当前世界的已保存角色到选择器（参照已保存世界观选择器） */
async function loadCharsIntoSelector() {
    const sel = document.getElementById('charSelect');
    if (!sel) return;
    sel.innerHTML = '<option value="">（新建）</option>';
    savedChars = [];
    if (!currentWorldId) return;
    try {
        const res = await fetch(`/rpg/workshop/characters?worldId=${currentWorldId}`);
        if (!res.ok) return;
        savedChars = await res.json();
        savedChars.forEach(c => {
            const opt = document.createElement('option');
            opt.value = c.id;
            opt.textContent = c.name;
            sel.appendChild(opt);
        });
    } catch (e) { console.error('加载角色列表失败', e); }
}

/** 选择已保存角色后加载到表单（进入编辑态）；选（新建）则清空表单 */
document.getElementById('charSelect').addEventListener('change', (e) => {
    const charId = e.target.value;
    if (!charId) { resetCharForm(); return; }
    const card = savedChars.find(c => c.id === charId);
    if (card) loadCharacterIntoForm(card);
});

/** 把选中的已保存角色加载到表单（进入编辑态） */
function loadCharacterIntoForm(c) {
    currentCharId = c.id;
    document.getElementById('charNameInput').value = c.name || '';
    document.getElementById('charIdentityInput').value = c.identity || '';
    document.getElementById('charPersonalityInput').value = c.personality || '';
    document.getElementById('charBackgroundInput').value = c.background || '';
    document.getElementById('charMotivationInput').value = c.motivation || '';
    document.getElementById('charSpeechStyleInput').value = c.speechStyle || '';
    document.getElementById('deleteCharBtn').hidden = false;
}

/** 清空角色表单并进入新建态（保存时走 INSERT） */
function resetCharForm() {
    currentCharId = null;
    document.getElementById('charNameInput').value = '';
    document.getElementById('charIdentityInput').value = '';
    document.getElementById('charPersonalityInput').value = '';
    document.getElementById('charBackgroundInput').value = '';
    document.getElementById('charMotivationInput').value = '';
    document.getElementById('charSpeechStyleInput').value = '';
    document.getElementById('deleteCharBtn').hidden = true;
    // 浏览器刷新会自动恢复表单值，这里显式把选择器拨回（新建）
    const charSel = document.getElementById('charSelect');
    if (charSel) charSel.value = '';
}

document.getElementById('newCharBtn').addEventListener('click', resetCharForm);

document.getElementById('deleteCharBtn').addEventListener('click', async () => {
    if (!currentCharId) return;
    if (!confirm('确认删除当前角色？此操作不可撤销。\n（被存档引用为玩家时会被拒绝；否则两端关系与关联触发器一并软删除）')) return;
    try {
        const res = await fetch(`/rpg/workshop/character/${encodeURIComponent(currentCharId)}`, {method: 'DELETE'});
        if (res.status === 409) {
            const body = await res.json().catch(() => null);
            alert('删除被拒绝: ' + ((body && body.error) || '存在引用'));
            return;
        }
        if (!res.ok) { alert('删除失败 (HTTP ' + res.status + ')'); return; }
        // 角色卡缓存作废（状态面板按卡 ID 解析卡名）
        worldCharCards = {};
        cachedCharWorldId = null;
        alert('角色已删除');
        resetCharForm();
        loadCharsIntoSelector();
    } catch (e) { alert('删除失败: ' + e.message); }
});

// ===== 世界书 CRUD 与 LLM 生成（add-rpg-worldbook D7）=====

/** 当前编辑中的世界书条目 id（null = 新建态） */
let currentWorldbookId = null;
/** 当前世界的已保存条目（选择器数据源，按 id 取完整对象） */
let savedWorldbookEntries = [];

/**
 * 随选中世界刷新世界书编辑区（对齐 refreshWorkshopLorebook 时机）。
 * worldId 为空（新建态）时清空选择器与表单。
 */
async function refreshWorkshopWorldbook(worldId) {
    const sel = document.getElementById('worldbookSelect');
    if (!sel) return;
    resetWorldbookForm();
    sel.innerHTML = '<option value="">（新建）</option>';
    savedWorldbookEntries = [];
    hideWorldbookDrafts();
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/worldbook?worldId=${encodeURIComponent(worldId)}`);
        if (!res.ok) return;
        savedWorldbookEntries = await res.json();
        savedWorldbookEntries.forEach(en => {
            const opt = document.createElement('option');
            opt.value = en.id;
            opt.textContent = (en.enabled === false ? '〔停用〕' : '') + (en.title || '(无标题)');
            sel.appendChild(opt);
        });
    } catch (e) { console.error('加载世界书条目失败', e); }
}

/** 选择已保存条目后加载到表单（编辑态）；选（新建）则清空 */
document.getElementById('worldbookSelect').addEventListener('change', (e) => {
    const id = e.target.value;
    if (!id) { resetWorldbookForm(); return; }
    const entry = savedWorldbookEntries.find(en => en.id === id);
    if (entry) loadWorldbookIntoForm(entry);
});

/** 把选中条目加载到编辑区（编辑态） */
function loadWorldbookIntoForm(en) {
    currentWorldbookId = en.id;
    document.getElementById('worldbookTitleInput').value = en.title || '';
    document.getElementById('worldbookKeywordsInput').value = en.keywords || '';
    document.getElementById('worldbookContentInput').value = en.content || '';
    document.getElementById('worldbookEnabledInput').checked = en.enabled !== false;
    document.getElementById('deleteWorldbookBtn').hidden = false;
}

/** 清空世界书表单并进入新建态（保存时走 INSERT） */
function resetWorldbookForm() {
    currentWorldbookId = null;
    document.getElementById('worldbookTitleInput').value = '';
    document.getElementById('worldbookKeywordsInput').value = '';
    document.getElementById('worldbookContentInput').value = '';
    document.getElementById('worldbookEnabledInput').checked = true;
    document.getElementById('deleteWorldbookBtn').hidden = true;
    const sel = document.getElementById('worldbookSelect');
    if (sel) sel.value = '';
}

document.getElementById('newWorldbookBtn').addEventListener('click', resetWorldbookForm);

document.getElementById('saveWorldbookBtn').addEventListener('click', () => saveWorldbookForm());

/**
 * 保存世界书条目表单（works-playtest-entry D1：saveWorldbookBtn listener 体抽取；
 * LLM 草稿区不纳入——尊重其「草稿预填 → 逐条确认落库」契约，design Non-Goals）。
 * @param skipEmpty 空态短路：试玩编排传 true——标题空视为「未在编辑」静默返回 true
 *        不调接口（不建空条目）；手动保存（缺省）保持原 alert 提示。
 * @returns {Promise<boolean>} 是否保存成功（含空态跳过）
 */
async function saveWorldbookForm(skipEmpty) {
    if (!currentWorldId) { alert('请先在「世界观」tab 选定或新建世界'); return false; }
    const title = document.getElementById('worldbookTitleInput').value.trim();
    if (!title) {
        if (skipEmpty) return true;   // 表单未在编辑：试玩静默跳过
        alert('请输入条目标题');
        return false;
    }
    // 编辑态沿用原 sortOrder，新建态置 0（后端也会兑底）
    const existing = currentWorldbookId
        ? savedWorldbookEntries.find(en => en.id === currentWorldbookId) : null;
    const data = {
        worldId: currentWorldId,
        title: title,
        keywords: document.getElementById('worldbookKeywordsInput').value,
        content: document.getElementById('worldbookContentInput').value,
        enabled: document.getElementById('worldbookEnabledInput').checked,
        sortOrder: existing && existing.sortOrder != null ? existing.sortOrder : 0
    };
    if (currentWorldbookId) data.id = currentWorldbookId;   // 含 id 走 UPDATE
    try {
        const res = await fetch('/rpg/workshop/worldbook', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('保存失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return false;
        }
        const saved = await res.json();
        currentWorldbookId = saved.id;
        document.getElementById('deleteWorldbookBtn').hidden = false;
        alert('世界书条目已保存: ' + saved.title);
        await refreshWorkshopWorldbook(currentWorldId);
        const sel = document.getElementById('worldbookSelect');
        if (sel) sel.value = saved.id;
        return true;
    } catch (e) { alert('保存失败: ' + e.message); return false; }
}

document.getElementById('deleteWorldbookBtn').addEventListener('click', async () => {
    if (!currentWorldbookId) return;
    if (!confirm('确认删除当前世界书条目？此操作不可撤销。')) return;
    try {
        const res = await fetch(`/rpg/workshop/worldbook/${encodeURIComponent(currentWorldbookId)}`, {method: 'DELETE'});
        if (res.status === 404) { alert('条目不存在或已删除'); return; }
        if (!res.ok) { alert('删除失败 (HTTP ' + res.status + ')'); return; }
        alert('条目已删除');
        await refreshWorkshopWorldbook(currentWorldId);
    } catch (e) { alert('删除失败: ' + e.message); }
});

// LLM 生成：展开关键词输入区
document.getElementById('generateWorldbookBtn').addEventListener('click', () => {
    document.getElementById('worldbookLlmInput').hidden = false;
});

// LLM 生成：调端点取草稿数组，渲染为草稿列表（不落库），用户逐条「填入编辑区」后再保存
document.getElementById('confirmGenerateWorldbookBtn').addEventListener('click', async () => {
    const keywords = document.getElementById('worldbookGenKeywords').value.trim();
    if (!keywords) { alert('请输入要生成的设定关键词'); return; }
    const btn = document.getElementById('confirmGenerateWorldbookBtn');
    const originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = '⏳ 生成中...';
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 60000); // 60s 超时
    try {
        const payload = {keywords};
        if (currentWorldId) payload.worldId = currentWorldId;   // 拼世界背景对齐调性
        const res = await fetch('/rpg/workshop/generate-worldbook', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload),
            signal: ctrl.signal
        });
        if (!res.ok) {
            const errText = await res.text().catch(() => '');
            // 内容审查拒答（input-content-moderation）：422 + {moderation:true,error}
            let msg = errText.substring(0, 200) || '服务器错误';
            try {
                const errJson = JSON.parse(errText);
                if (errJson && errJson.moderation && errJson.error) { alert(errJson.error); return; }
                if (errJson && errJson.error) msg = errJson.error;
            } catch (_) { /* 非 JSON 响应，沿用原文本 */ }
            alert('生成失败 (HTTP ' + res.status + '): ' + msg);
            return;
        }
        const drafts = await res.json();
        renderWorldbookDrafts(drafts);
        document.getElementById('worldbookLlmInput').hidden = true;
    } catch (e) {
        if (e.name === 'AbortError') { alert('生成超时，请稍后重试'); }
        else { alert('生成失败: ' + e.message); }
    } finally {
        clearTimeout(timer);
        btn.disabled = false;
        btn.textContent = originalText;
    }
});

/** 渲染 LLM 草稿列表：每条一张卡 + 「填入编辑区」按钮（点击后进入新建态并预填，供用户复核再保存） */
function renderWorldbookDrafts(drafts) {
    const box = document.getElementById('worldbookDraftList');
    if (!box) return;
    box.innerHTML = '';
    if (!drafts || drafts.length === 0) {
        box.hidden = true;
        return;
    }
    const heading = document.createElement('div');
    heading.className = 'worldbook-drafts-heading';
    heading.textContent = `LLM 生成 ${drafts.length} 条草稿（未保存，点「填入编辑区」复核后逐条保存）：`;
    box.appendChild(heading);
    drafts.forEach(draft => {
        const card = document.createElement('div');
        card.className = 'worldbook-draft-card';
        const title = document.createElement('div');
        title.className = 'worldbook-draft-title';
        title.textContent = draft.title || '(无标题)';
        const kw = document.createElement('div');
        kw.className = 'worldbook-draft-kw';
        kw.textContent = '关键词：' + (draft.keywords || '（无）');
        const content = document.createElement('div');
        content.className = 'worldbook-draft-content';
        content.textContent = draft.content || '';
        const useBtn = document.createElement('button');
        useBtn.type = 'button';
        useBtn.className = 'btn-secondary';
        useBtn.textContent = '填入编辑区';
        useBtn.addEventListener('click', () => {
            resetWorldbookForm();
            document.getElementById('worldbookTitleInput').value = draft.title || '';
            document.getElementById('worldbookKeywordsInput').value = draft.keywords || '';
            document.getElementById('worldbookContentInput').value = draft.content || '';
            document.getElementById('worldbookEnabledInput').checked = true;
        });
        card.appendChild(title);
        card.appendChild(kw);
        card.appendChild(content);
        card.appendChild(useBtn);
        box.appendChild(card);
    });
    box.hidden = false;
}

/** 隐藏并清空草稿列表 */
function hideWorldbookDrafts() {
    const box = document.getElementById('worldbookDraftList');
    if (box) { box.innerHTML = ''; box.hidden = true; }
    const gen = document.getElementById('worldbookLlmInput');
    if (gen) gen.hidden = true;
}

// ===== 世界书 tab 双轨：设定集模板 CRUD（workshop-soft-delete-cascade 7.2）=====

/** 当前轨：worldbook=通用（默认，现状延续）/ star=设定集 */
let workshopTrack = 'worldbook';
/** 当前编辑中的设定集模板行 id（null = 新建态，保存走 INSERT） */
let currentStarId = null;
/** 当前世界的设定集模板行（选择器数据源，按 id 取完整对象） */
let savedStarEntries = [];

// 轨切换：对齐游戏内弹窗 lorebook-tabs 交互（active 样式 + pane 显隐）
document.querySelectorAll('#workshopTrackTabs .lorebook-tab').forEach(btn => {
    btn.addEventListener('click', () => {
        const track = btn.dataset.track;
        if (track === workshopTrack) return;
        workshopTrack = track;
        document.querySelectorAll('#workshopTrackTabs .lorebook-tab').forEach(b =>
            b.classList.toggle('active', b === btn));
        document.getElementById('starTrackPane').hidden = track !== 'star';
        document.getElementById('worldbookTrackPane').hidden = track !== 'worldbook';
    });
});

/** 前端判预设世界（只读降级）；后端写端点另有 400 拒绝双重保险 */
function isPresetWorld(worldId) {
    return !!worldId && worldId.startsWith('preset-');
}

/**
 * 随选中世界刷新设定集轨：列表渲染 + 只读态切换（预设世界显示提示、隐藏写按钮）。
 * worldId 为空（新建态）时清空选择器与表单。
 */
async function refreshWorkshopStars(worldId) {
    const sel = document.getElementById('starSelect');
    if (!sel) return;
    resetStarForm();
    sel.innerHTML = '<option value="">（新建）</option>';
    savedStarEntries = [];
    const preset = isPresetWorld(worldId);
    document.getElementById('newStarBtn').hidden = preset;
    document.getElementById('saveStarBtn').hidden = preset;
    // deleteStarBtn 由 resetStarForm 隐藏（仅非预设世界编辑态显示）
    const hint = document.getElementById('starTrackHint');
    if (hint) {
        // 轨定位说明常驻（rename-star-lorebook-compendium D5）：预设世界显示物化只读提示，
        // 非预设世界显示设定集轨定位说明并互文指向通用世界书
        hint.hidden = false;
        hint.textContent = preset
            ? '预设世界的设定集由 md 物化维护，此处只读；重新物化时全量重写。'
              + '每局开局的专属副本编辑在游戏内「📖 资料库」弹窗。'
            : '世界观词条档案：条目名或别名首次被提及即“揭示”，档案归档、本局常驻，GM 可随时查阅——适合人物、组织、核心概念。只想当轮提醒一下的背景请用「📚 通用世界书」。';
    }
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/star?worldId=${encodeURIComponent(worldId)}`);
        if (!res.ok) return;
        savedStarEntries = await res.json();
        // 分类 optgroup 分组（add-star-lorebook-category D4）：无分类条目平铺于无分组区
        // （「（新建）」保持首位），有分类按首条目出现顺序建组；〔不可命中〕前缀保留在 option 文本内
        const starGroups = new Map();
        savedStarEntries.forEach(en => {
            const opt = document.createElement('option');
            opt.value = en.id;
            opt.textContent = (en.rollable === false ? '〔不可命中〕' : '')
                + (en.starName || '?') + '·' + (en.warlordName || '?');
            const cat = en.category ? String(en.category).trim() : '';
            if (cat) {
                let grp = starGroups.get(cat);
                if (!grp) {
                    grp = document.createElement('optgroup');
                    grp.label = cat;
                    starGroups.set(cat, grp);
                    sel.appendChild(grp);
                }
                grp.appendChild(opt);
            } else {
                sel.appendChild(opt);
            }
        });
        // 历史分类建议（D5）：datalist 聚合当前世界模板行已用分类（去重）
        const dl = document.getElementById('starCategoryList');
        if (dl) {
            dl.innerHTML = '';
            const seen = new Set();
            savedStarEntries.forEach(en => {
                const cat = en.category ? String(en.category).trim() : '';
                if (cat && !seen.has(cat)) {
                    seen.add(cat);
                    const o = document.createElement('option');
                    o.value = cat;
                    dl.appendChild(o);
                }
            });
        }
    } catch (e) { console.error('加载设定集失败', e); }
}

document.getElementById('starSelect').addEventListener('change', (e) => {
    const id = e.target.value;
    if (!id) { resetStarForm(); return; }
    const entry = savedStarEntries.find(en => en.id === id);
    if (entry) loadStarIntoForm(entry);
});

/** 把选中武将条目加载到表单（进入编辑态；预设世界只读，不显删除按钮） */
function loadStarIntoForm(en) {
    currentStarId = en.id;
    document.getElementById('starNameInput').value = en.starName || '';
    document.getElementById('starWarlordInput').value = en.warlordName || '';
    document.getElementById('starLoreInput').value = en.lore || '';
    document.getElementById('starAliasesInput').value = en.aliases || '';
    document.getElementById('starCategoryInput').value = en.category || '';
    document.getElementById('starRollableInput').checked = en.rollable !== false;
    document.getElementById('deleteStarBtn').hidden = isPresetWorld(currentWorldId);
}

/** 清空冥星轨表单并进入新建态（保存时走 INSERT） */
function resetStarForm() {
    currentStarId = null;
    document.getElementById('starNameInput').value = '';
    document.getElementById('starWarlordInput').value = '';
    document.getElementById('starLoreInput').value = '';
    document.getElementById('starAliasesInput').value = '';
    document.getElementById('starCategoryInput').value = '';
    document.getElementById('starRollableInput').checked = true;
    document.getElementById('deleteStarBtn').hidden = true;
    const sel = document.getElementById('starSelect');
    if (sel) sel.value = '';
}

document.getElementById('newStarBtn').addEventListener('click', resetStarForm);

document.getElementById('saveStarBtn').addEventListener('click', () => saveStarForm());

/**
 * 保存设定集（冥星轨）条目表单（works-playtest-entry D1：saveStarBtn listener 体抽取）。
 * @param skipEmpty 空态短路：试玩编排传 true——必填空/预设世界禁用态均静默返回 true
 *        不调接口（预设世界 saveStarBtn 本就 hidden，design D1）；手动保存（缺省）
 *        保持原 alert 提示。
 * @returns {Promise<boolean>} 是否保存成功（含空态跳过）
 */
async function saveStarForm(skipEmpty) {
    if (!currentWorldId) { alert('请先在「世界观」tab 选定或新建世界'); return false; }
    if (isPresetWorld(currentWorldId)) {
        if (skipEmpty) return true;   // 预设世界禁用态：试玩静默跳过
        alert('预设世界的设定集由 md 物化维护，不可在工坊编辑');
        return false;
    }
    const starName = document.getElementById('starNameInput').value.trim();
    const warlordName = document.getElementById('starWarlordInput').value.trim();
    if (!starName || !warlordName) {
        if (skipEmpty) return true;   // 表单未在编辑：试玩静默跳过
        alert('请输入标题与名称');
        return false;
    }
    // 编辑态沿用原 sortOrder，新建态置 0（后端也会兑底）
    const existing = currentStarId
        ? savedStarEntries.find(en => en.id === currentStarId) : null;
    const data = {
        worldId: currentWorldId,
        starName: starName,
        warlordName: warlordName,
        lore: document.getElementById('starLoreInput').value,
        aliases: document.getElementById('starAliasesInput').value,
        category: document.getElementById('starCategoryInput').value.trim(),
        rollable: document.getElementById('starRollableInput').checked,
        sortOrder: existing && existing.sortOrder != null ? existing.sortOrder : 0
    };
    if (currentStarId) data.id = currentStarId;   // 含 id 且模板行存在则 UPDATE
    try {
        const res = await fetch('/rpg/workshop/star', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('保存失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return false;
        }
        const saved = await res.json();
        currentStarId = saved.id;
        document.getElementById('deleteStarBtn').hidden = false;
        alert('设定集条目已保存: ' + saved.starName + '·' + saved.warlordName);
        await refreshWorkshopStars(currentWorldId);
        const sel = document.getElementById('starSelect');
        if (sel) sel.value = saved.id;
        return true;
    } catch (e) { alert('保存失败: ' + e.message); return false; }
}

document.getElementById('deleteStarBtn').addEventListener('click', async () => {
    if (!currentStarId) return;
    if (!confirm('确认删除当前设定集条目？此操作不可撤销。\n（不影响已开局存档的资料库副本）')) return;
    try {
        const res = await fetch(`/rpg/workshop/star/${encodeURIComponent(currentStarId)}`, {method: 'DELETE'});
        if (res.status === 404) { alert('条目不存在或已删除'); return; }
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('删除失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return;
        }
        alert('设定集条目已删除');
        await refreshWorkshopStars(currentWorldId);
    } catch (e) { alert('删除失败: ' + e.message); }
});
