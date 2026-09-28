/**
 * 工坊模式：Tab 切换、世界观/角色卡/地点/触发器/关系 CRUD、LLM 生成、
 * 批量中文化改名（rpg-js-split 拆分自 rpg.js；rpg-home-entry：
 * 开始冒险选择器已迁至 game.js，工坊回归纯创作工具）
 *
 * 本文件产出的全局符号：
 *   变量：savedChars
 *   函数：resetWorldForm, loadWorldsIntoSelector, loadCharsIntoSelector,
 *         loadCharacterIntoForm, resetCharForm, formatLocalizeSummary
 *   依赖（运行时）：state.js（currentWorldId 等）；game.js（worldCharCards 缓存失效，
 *         refreshWorldSelects：保存/删除世界后同步首页新开冒险下拉）
 */

// ===== 工坊模式 Tab 切换 =====
document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
        btn.classList.add('active');
        document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
    });
});

// ===== 世界观保存与生成 =====
document.getElementById('saveWorldBtn').addEventListener('click', async () => {
    const data = {
        name: document.getElementById('worldNameInput').value,
        era: document.getElementById('worldEraInput').value,
        settingDesc: document.getElementById('worldDescInput').value,
        rules: document.getElementById('worldRulesInput').value,
        atmosphere: document.getElementById('worldAtmosphereInput').value
    };
    if (!data.name) { alert('请输入世界名称'); return; }
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
        refreshWorldSelects();
        // 同步更新工坊世界观选择器
        await loadWorldsIntoSelector();
        // 选中当前保存的世界
        const sel = document.getElementById('worldSelect');
        if (sel) sel.value = world.id;
    } catch (e) { alert('保存失败: ' + e.message); }
});

// ===== 世界观新建与删除 =====
/** 清空世界观表单并进入新建态（保存时走 INSERT） */
function resetWorldForm() {
    currentWorldId = null;
    document.getElementById('worldNameInput').value = '';
    document.getElementById('worldEraInput').value = '';
    document.getElementById('worldDescInput').value = '';
    document.getElementById('worldRulesInput').value = '';
    document.getElementById('worldAtmosphereInput').value = '';
    const sel = document.getElementById('worldSelect');
    if (sel) sel.value = '';
    document.getElementById('deleteWorldBtn').hidden = true;
    loadCharsIntoSelector();
}

document.getElementById('newWorldBtn').addEventListener('click', resetWorldForm);

document.getElementById('deleteWorldBtn').addEventListener('click', async () => {
    if (!currentWorldId) return;
    if (!confirm('确认删除当前世界观？此操作不可撤销。\n（旗下仍有地点/角色/触发器/存档时会被拒绝）')) return;
    try {
        const res = await fetch(`/rpg/workshop/world/${encodeURIComponent(currentWorldId)}`, {method: 'DELETE'});
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
        resetWorldForm();
        await loadWorldsIntoSelector();
        await refreshWorldSelects();
    } catch (e) { alert('删除失败: ' + e.message); }
});

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
document.getElementById('saveCharBtn').addEventListener('click', async () => {
    if (!currentWorldId) { alert('请先选择或新建世界观'); return; }
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
    if (!data.name) { alert('请输入角色名称'); return; }
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
    } catch (e) { alert('保存失败: ' + e.message); }
});

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

// ===== 地点保存 =====
document.getElementById('saveLocBtn').addEventListener('click', async () => {
    if (!currentWorldId) { alert('请先保存世界观'); return; }
    const data = {
        worldId: currentWorldId,
        name: document.getElementById('locNameInput').value,
        description: document.getElementById('locDescInput').value,
        npcIds: '[]', triggerIds: '[]'
    };
    if (!data.name) { alert('请输入地点名称'); return; }
    try {
        const res = await fetch('/rpg/workshop/location', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        const loc = await res.json();
        alert('地点已保存: ' + loc.name);
    } catch (e) { alert('保存失败: ' + e.message); }
});

// ===== 触发器保存 =====
document.getElementById('saveTriggerBtn').addEventListener('click', async () => {
    if (!currentWorldId) { alert('请先保存世界观'); return; }
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
    try {
        const res = await fetch('/rpg/workshop/trigger', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        const trigger = await res.json();
        alert('触发器已保存: ' + trigger.id);
    } catch (e) { alert('保存失败: ' + e.message); }
});

// ===== 关系保存 =====
document.getElementById('saveRelBtn').addEventListener('click', async () => {
    const data = {
        charAId: document.getElementById('relCharA').value,
        charBId: document.getElementById('relCharB').value,
        attitude: parseInt(document.getElementById('relAttitude').value),
        trust: parseInt(document.getElementById('relTrust').value),
        notes: ''
    };
    try {
        const res = await fetch('/rpg/workshop/relationship', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        alert('关系已保存');
    } catch (e) { alert('保存失败: ' + e.message); }
});

// ===== 辅助方法 =====

/** 加载已保存的世界观到选择器 */
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
    } catch (e) { console.error('加载世界观列表失败', e); }
}

/** 选择已保存的世界观后，加载其详情到表单 */
document.getElementById('worldSelect').addEventListener('change', async (e) => {
    const worldId = e.target.value;
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
        document.getElementById('worldNameInput').value = world.name || '';
        document.getElementById('worldEraInput').value = world.era || '';
        document.getElementById('worldDescInput').value = world.settingDesc || '';
        document.getElementById('worldRulesInput').value = world.rules || '[]';
        document.getElementById('worldAtmosphereInput').value = world.atmosphere || '';
        // 切换世界观后角色表单回到新建态，避免旧世界角色误挂到新世界
        resetCharForm();
        loadCharsIntoSelector();
    } catch (e) { console.error('加载世界观详情失败', e); }
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
    if (!confirm('确认删除当前角色？此操作不可撤销。\n（被关系/触发器/存档引用时会被拒绝）')) return;
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
