/**
 * 资料库弹窗（双轨就地编辑器）与工坊名录块（add-rpg-lorebook-sets D9/D10）
 *
 * 游戏模式弹窗（lorebookBtn）：经 /rpg/lorebook-set/** 对当前存档的世界书副本（lorebook set）
 *   双轨（设定集 + 通用世界书）就地增删改停用、从世界模板单向导入；写入仅作用于本存档 set。
 *   老存档未物化时展示模板行，首次写入后端事务内物化（前端重拉 current 消化 id 变化）。
 * 工坊世界观 tab（lorebookBlock）：仍走只读端点 GET /rpg/lorebook/{worldId}，复用 renderLorebookEntries。
 *
 * 本文件产出的全局符号：
 *   函数：closeLorebookDialog, renderLorebookEntries, lorebookBadgeNode, lorebookHintNode,
 *         refreshWorkshopLorebook
 *   依赖（运行时）：state.js（currentWorldId / currentGameStateId）；
 *         game.js（showCompactToast）；workshop.js 调用 refreshWorkshopLorebook
 *   加载顺序：memory-dialog.js 之后、init.js 之前
 */

// ===== 游戏模式：资料库弹窗（双轨编辑器）=====
let lbCurrent = null;              // 最近一次 /current 响应（CurrentView）
let lbActiveTrack = 'star';        // 当前轨：star=设定集 / worldbook=通用世界书
let lbEditing = null;              // 正在编辑的条目上下文（{track,id,isNew}）；null=浏览态
let lbImportSelected = new Set();  // 导入抽屉已选 ref 集（键：track:ref）

document.getElementById('lorebookBtn').addEventListener('click', openLorebookDialog);
document.getElementById('closeLorebookBtn').addEventListener('click', closeLorebookDialog);
document.getElementById('lorebookTabs').addEventListener('click', e => {
    const btn = e.target.closest('.lorebook-tab');
    if (btn) switchLorebookTrack(btn.dataset.track);
});
document.getElementById('lorebookNewBtn').addEventListener('click', () => openLorebookEntryForm(null));
document.getElementById('lorebookImportBtn').addEventListener('click', openLorebookImportDrawer);
document.getElementById('lorebookSaveBtn').addEventListener('click', saveLorebookEntryFromForm);
document.getElementById('lorebookCancelBtn').addEventListener('click', showLorebookBrowse);
document.getElementById('lorebookImportClose').addEventListener('click', showLorebookBrowse);
document.getElementById('lorebookImportToggleAll').addEventListener('click', toggleAllLorebookImport);
document.getElementById('lorebookImportConfirm').addEventListener('click', confirmLorebookImport);

async function openLorebookDialog() {
    if (!currentGameStateId) {
        showCompactToast('请先开始冒险或读取存档');
        return;
    }
    document.getElementById('lorebookOverlay').hidden = false;
    showLorebookBrowse();
    await loadLorebookCurrent();
}

function closeLorebookDialog() {
    document.getElementById('lorebookOverlay').hidden = true;
    document.getElementById('lorebookImportDrawer').hidden = true;
}

/** 拉取当前存档双轨视图并重渲染；任何写操作后都重拉，自动消化懒物化后的 id 变化 */
async function loadLorebookCurrent() {
    const list = document.getElementById('lorebookList');
    try {
        const res = await fetch(`/rpg/lorebook-set/current?gameStateId=${encodeURIComponent(currentGameStateId)}`);
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            throw new Error((body && body.error) || ('HTTP ' + res.status));
        }
        lbCurrent = await res.json();
    } catch (e) {
        lbCurrent = null;
        renderLorebookSourceLine();
        list.replaceChildren(lorebookHintNode(`资料库加载失败：${e.message}`));
        return;
    }
    renderLorebookSourceLine();
    renderLorebookTrackList();
}

/** 抬头出处行：副本名 + 出处 + 未物化标示（D10） */
function renderLorebookSourceLine() {
    const line = document.getElementById('lorebookSourceLine');
    line.replaceChildren();
    if (!lbCurrent) {
        line.classList.remove('unmaterialized');
        line.textContent = `存档 ${currentGameStateId.substring(0, 8)}`;
        return;
    }
    if (!lbCurrent.materialized || !lbCurrent.set) {
        line.classList.add('unmaterialized');
        line.textContent = '尚未物化 · 当前展示世界模板内容，首次编辑/新增将创建本存档专属副本';
        return;
    }
    line.classList.remove('unmaterialized');
    const set = lbCurrent.set;
    const name = document.createElement('strong');
    name.textContent = set.name || '世界书副本';
    line.appendChild(name);
    const meta = document.createElement('span');
    meta.className = 'lorebook-source-meta';
    const created = set.createdAt ? String(set.createdAt).replace('T', ' ').substring(0, 16) : '';
    meta.textContent = ` · ${set.origin || ''}${created ? ' · ' + created : ''} · 仅作用于本存档`;
    line.appendChild(meta);
}

function switchLorebookTrack(track) {
    if (track !== 'star' && track !== 'worldbook') return;
    lbActiveTrack = track;
    document.querySelectorAll('#lorebookTabs .lorebook-tab').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.track === track);
    });
    showLorebookBrowse();
    renderLorebookTrackList();
}

/** 回到浏览态（隐藏表单与导入抽屉） */
function showLorebookBrowse() {
    lbEditing = null;
    document.getElementById('lorebookBrowse').hidden = false;
    document.getElementById('lorebookEntryForm').hidden = true;
    document.getElementById('lorebookImportDrawer').hidden = true;
}

/** 当前轨是否可写（单轨停用 503 门控的前端只读降级依据，D9） */
function lbTrackEnabled(track) {
    if (!lbCurrent) return false;
    return track === 'star' ? lbCurrent.lorebookEnabled : lbCurrent.worldbookEnabled;
}

/** 渲染当前轨条目列表（带编辑/删除/停用动作 + 揭示/抢星/NPC 徽章） */
function renderLorebookTrackList() {
    const list = document.getElementById('lorebookList');
    const hint = document.getElementById('lorebookTrackHint');
    const newBtn = document.getElementById('lorebookNewBtn');
    const importBtn = document.getElementById('lorebookImportBtn');
    list.replaceChildren();
    if (!lbCurrent) return;

    const editable = lbTrackEnabled(lbActiveTrack);
    newBtn.disabled = !editable;
    importBtn.disabled = !editable;
    // 轨定位说明常驻显示（rename-star-lorebook-compendium D5）：切轨即可见一句说明；
    // 单轨停用时停用文案在前、轨说明叠加其后（叠加而非替换，降级信息优先）
    hint.hidden = false;
    const trackHint = lbActiveTrack === 'star'
        ? '首次提及即揭示，档案本局常驻，GM 可随时查阅。编辑仅影响本存档副本。'
        : '关键词命中仅当轮注入正文，滑出即忘。编辑仅影响本存档副本。';
    hint.textContent = editable
        ? trackHint
        : (lbActiveTrack === 'star'
                ? '设定集功能已停用（rpg.lorebook.enabled=false），本轨只读；另一轨不受影响。'
                : '通用世界书功能已停用（rpg.worldbook.enabled=false），本轨只读；另一轨不受影响。')
          + ' ' + trackHint;

    if (lbActiveTrack === 'star') {
        const stars = lbCurrent.stars || [];
        if (stars.length === 0) {
            list.appendChild(lorebookHintNode('本副本暂无设定集条目。点「＋ 新增条目」或「从世界导入」。'));
            return;
        }
        // 分类分组（add-star-lorebook-category D4）：分类切换处插分组标题行（与注入块 D3
        // 同算法——stars 已按 sortOrder 排序，遍历序即感知序）；无分类条目平铺不插标题行
        let lastCat = null;
        stars.forEach(s => {
            const cat = s.category ? String(s.category).trim() : '';
            if (cat && cat !== lastCat) {
                const grp = document.createElement('div');
                grp.className = 'lorebook-group-title';
                grp.textContent = cat;
                list.appendChild(grp);
            }
            lastCat = cat;
            list.appendChild(renderLorebookStarCard(s, editable));
        });
    } else {
        const entries = lbCurrent.entries || [];
        if (entries.length === 0) {
            list.appendChild(lorebookHintNode('本副本暂无通用世界书条目。点「＋ 新增条目」或「从世界导入」。'));
            return;
        }
        entries.forEach(e => list.appendChild(renderLorebookWorldbookCard(e, editable)));
    }
}

/** 设定集条目卡（保留揭示/抢星/不可命中徽章；可写时附编辑/删除动作） */
function renderLorebookStarCard(s, editable) {
    const item = document.createElement('div');
    item.className = 'lorebook-entry editable';
    if (s.claimedByPlayer) item.classList.add('claimed');
    else if (s.activated) item.classList.add('revealed');

    const head = document.createElement('div');
    head.className = 'lorebook-entry-head';
    const title = document.createElement('span');
    title.className = 'lorebook-entry-title';
    title.textContent = `${s.starName}·${s.warlordName}`;
    head.appendChild(title);
    if (!s.rollable) head.appendChild(lorebookBadgeNode('npc', '不可命中'));
    if (s.claimedByPlayer) head.appendChild(lorebookBadgeNode('claimed', '⭐ 星归玩家'));
    else if (s.activated) head.appendChild(lorebookBadgeNode('revealed',
        `已揭示${s.activatedTurn != null ? ` · 第 ${s.activatedTurn} 轮` : ''}`));
    if (editable) head.appendChild(buildLorebookCardActions(s, 'star', false));
    item.appendChild(head);

    if (s.lore) {
        const lore = document.createElement('div');
        lore.className = 'lorebook-entry-lore';
        lore.textContent = s.lore;
        item.appendChild(lore);
    }
    if (s.aliases) {
        const aliases = document.createElement('div');
        aliases.className = 'lorebook-entry-aliases';
        aliases.textContent = `别名：${s.aliases}`;
        item.appendChild(aliases);
    }
    return item;
}

/** 通用世界书条目卡（停用态灰显 + 启用/停用快捷切换） */
function renderLorebookWorldbookCard(e, editable) {
    const item = document.createElement('div');
    item.className = 'lorebook-entry editable worldbook';
    const disabled = e.enabled === false;
    if (disabled) item.classList.add('disabled');

    const head = document.createElement('div');
    head.className = 'lorebook-entry-head';
    const title = document.createElement('span');
    title.className = 'lorebook-entry-title';
    title.textContent = e.title || '（无标题）';
    head.appendChild(title);
    if (disabled) head.appendChild(lorebookBadgeNode('off', '已停用'));
    if (editable) head.appendChild(buildLorebookCardActions(e, 'worldbook', disabled));
    item.appendChild(head);

    if (e.keywords) {
        const kw = document.createElement('div');
        kw.className = 'lorebook-entry-aliases';
        kw.textContent = `关键词：${e.keywords}`;
        item.appendChild(kw);
    }
    if (e.content) {
        const content = document.createElement('div');
        content.className = 'lorebook-entry-lore';
        content.textContent = e.content;
        item.appendChild(content);
    }
    return item;
}

/** 条目卡右上角动作组：编辑 / 删除（通用轨另附 启用/停用 快捷切换） */
function buildLorebookCardActions(entry, track, disabled) {
    const actions = document.createElement('div');
    actions.className = 'lorebook-entry-actions';
    if (track === 'worldbook') {
        const toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'lorebook-mini-btn';
        toggle.textContent = disabled ? '启用' : '停用';
        toggle.addEventListener('click', ev => { ev.stopPropagation(); toggleLorebookEntryEnabled(entry); });
        actions.appendChild(toggle);
    }
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'lorebook-mini-btn';
    edit.textContent = '编辑';
    edit.addEventListener('click', ev => { ev.stopPropagation(); openLorebookEntryForm(entry); });
    actions.appendChild(edit);

    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'lorebook-mini-btn danger';
    del.textContent = '删除';
    del.addEventListener('click', ev => { ev.stopPropagation(); deleteLorebookEntry(entry, track); });
    actions.appendChild(del);
    return actions;
}

/** 打开共享条目表单（entry=null 为新增）；按当前轨显隐字段组 */
function openLorebookEntryForm(entry) {
    if (!lbCurrent) return;
    const track = lbActiveTrack;
    if (!lbTrackEnabled(track)) { showCompactToast('本轨已停用，无法编辑'); return; }
    lbEditing = { track, id: entry ? entry.id : null, isNew: !entry };

    document.getElementById('lorebookStarFields').hidden = track !== 'star';
    document.getElementById('lorebookWorldbookFields').hidden = track !== 'worldbook';
    document.getElementById('lorebookFormTitle').textContent =
        (entry ? '编辑' : '新增') + (track === 'star' ? '设定集条目' : '通用世界书条目');

    if (track === 'star') {
        document.getElementById('lbStarName').value = entry ? (entry.starName || '') : '';
        document.getElementById('lbWarlordName').value = entry ? (entry.warlordName || '') : '';
        document.getElementById('lbLore').value = entry ? (entry.lore || '') : '';
        document.getElementById('lbAliases').value = entry ? (entry.aliases || '') : '';
        document.getElementById('lbCategory').value = entry ? (entry.category || '') : '';
        document.getElementById('lbRollable').checked = entry ? entry.rollable !== false : true;
        // 历史分类建议（add-star-lorebook-category D5）：datalist 聚合本 set 现有条目分类（去重）
        const dl = document.getElementById('lbCategoryList');
        if (dl) {
            dl.replaceChildren();
            const seen = new Set();
            (lbCurrent.stars || []).forEach(s => {
                const cat = s.category ? String(s.category).trim() : '';
                if (cat && !seen.has(cat)) {
                    seen.add(cat);
                    const o = document.createElement('option');
                    o.value = cat;
                    dl.appendChild(o);
                }
            });
        }
    } else {
        document.getElementById('lbTitle').value = entry ? (entry.title || '') : '';
        document.getElementById('lbKeywords').value = entry ? (entry.keywords || '') : '';
        document.getElementById('lbContent').value = entry ? (entry.content || '') : '';
        document.getElementById('lbEnabled').checked = entry ? entry.enabled !== false : true;
    }
    document.getElementById('lorebookBrowse').hidden = true;
    document.getElementById('lorebookImportDrawer').hidden = true;
    document.getElementById('lorebookEntryForm').hidden = false;
    document.getElementById(track === 'star' ? 'lbStarName' : 'lbTitle').focus();
}

/** 提交表单（编辑/新增同路径 POST /entry）；成功后重拉 current 消化懒物化 id 变化 */
async function saveLorebookEntryFromForm() {
    if (!lbEditing) return;
    const track = lbEditing.track;
    const payload = { gameStateId: currentGameStateId, track };
    if (track === 'star') {
        const starName = document.getElementById('lbStarName').value.trim();
        const warlordName = document.getElementById('lbWarlordName').value.trim();
        if (!starName || !warlordName) { showCompactToast('❌ 标题与名称不能为空'); return; }
        payload.star = {
            id: lbEditing.id, starName, warlordName,
            lore: document.getElementById('lbLore').value,
            aliases: document.getElementById('lbAliases').value.trim(),
            category: document.getElementById('lbCategory').value.trim(),
            rollable: document.getElementById('lbRollable').checked
        };
    } else {
        const title = document.getElementById('lbTitle').value.trim();
        if (!title) { showCompactToast('❌ 标题不能为空'); return; }
        payload.worldbook = {
            id: lbEditing.id, title,
            keywords: document.getElementById('lbKeywords').value.trim(),
            content: document.getElementById('lbContent').value,
            enabled: document.getElementById('lbEnabled').checked
        };
    }
    const btn = document.getElementById('lorebookSaveBtn');
    btn.disabled = true;
    try {
        const res = await fetch('/rpg/lorebook-set/entry', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const body = await res.json().catch(() => null);
        if (!res.ok) throw new Error((body && body.error) || ('HTTP ' + res.status));
        showCompactToast(lbEditing.isNew ? '✅ 条目已新增' : '✅ 条目已保存');
        showLorebookBrowse();
        await loadLorebookCurrent();
    } catch (e) {
        showCompactToast(`❌ 保存失败：${e.message}`);
    } finally {
        btn.disabled = false;
    }
}

/** 删除条目（原生 confirm 二次确认）；成功后重拉 current */
async function deleteLorebookEntry(entry, track) {
    const label = track === 'star' ? `${entry.starName}·${entry.warlordName}` : (entry.title || '该条目');
    if (!window.confirm(`确认从本存档副本删除「${label}」？此操作不影响世界模板与其他存档。`)) return;
    try {
        const res = await fetch(
            `/rpg/lorebook-set/entry/${encodeURIComponent(entry.id)}?gameStateId=${encodeURIComponent(currentGameStateId)}&track=${track}`,
            {method: 'DELETE'});
        if (!res.ok && res.status !== 204) {
            const body = await res.json().catch(() => null);
            throw new Error((body && body.error) || ('HTTP ' + res.status));
        }
        showCompactToast('🗑️ 条目已删除');
        await loadLorebookCurrent();
    } catch (e) {
        showCompactToast(`❌ 删除失败：${e.message}`);
    }
}

/** 通用轨快捷启用/停用（复用 POST /entry，仅翻转 enabled） */
async function toggleLorebookEntryEnabled(entry) {
    const next = entry.enabled === false;   // 当前停用 → 启用；反之停用
    try {
        const res = await fetch('/rpg/lorebook-set/entry', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                gameStateId: currentGameStateId, track: 'worldbook',
                worldbook: {
                    id: entry.id, title: entry.title, keywords: entry.keywords,
                    content: entry.content, enabled: next, sortOrder: entry.sortOrder
                }
            })
        });
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            throw new Error((body && body.error) || ('HTTP ' + res.status));
        }
        showCompactToast(next ? '✅ 已启用' : '⏸️ 已停用');
        await loadLorebookCurrent();
    } catch (e) {
        showCompactToast(`❌ 操作失败：${e.message}`);
    }
}

/** 打开导入抽屉：拉取候选（模板中 ref 不在本 set 的条目） */
async function openLorebookImportDrawer() {
    if (!lbCurrent) return;
    if (!lbTrackEnabled(lbActiveTrack)) { showCompactToast('本轨已停用，无法导入'); return; }
    lbImportSelected = new Set();
    document.getElementById('lorebookBrowse').hidden = true;
    document.getElementById('lorebookEntryForm').hidden = true;
    document.getElementById('lorebookImportDrawer').hidden = false;
    const list = document.getElementById('lorebookImportList');
    list.replaceChildren(lorebookHintNode('加载中…'));
    try {
        const res = await fetch(`/rpg/lorebook-set/import-candidates?gameStateId=${encodeURIComponent(currentGameStateId)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        renderLorebookImportList(await res.json());
    } catch (e) {
        list.replaceChildren(lorebookHintNode(`候选加载失败：${e.message}`));
    }
}

/** 渲染导入候选（双轨分组，复选框逐条选择） */
function renderLorebookImportList(cand) {
    const list = document.getElementById('lorebookImportList');
    list.replaceChildren();
    const stars = cand.stars || [];
    const entries = cand.entries || [];
    if (stars.length === 0 && entries.length === 0) {
        list.appendChild(lorebookHintNode('世界模板没有可导入的新条目（都已在本副本中）。'));
        return;
    }
    if (stars.length > 0) {
        list.appendChild(buildLorebookImportGroup('📖 设定集', stars, 'star',
            s => `${s.starName}·${s.warlordName}`, s => s.starName));
    }
    if (entries.length > 0) {
        list.appendChild(buildLorebookImportGroup('📚 通用世界书', entries, 'worldbook',
            e => e.title || '（无标题）', e => e.id));
    }
}

function buildLorebookImportGroup(title, items, track, labelFn, refFn) {
    const wrap = document.createElement('div');
    wrap.className = 'lorebook-import-group';
    const head = document.createElement('div');
    head.className = 'memory-group-title';
    head.textContent = `${title}（${items.length}）`;
    wrap.appendChild(head);
    items.forEach(it => {
        const row = document.createElement('label');
        row.className = 'lorebook-import-item';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.value = refFn(it);
        cb.dataset.track = track;
        cb.addEventListener('change', () => {
            const key = cb.dataset.track + ':' + cb.value;
            if (cb.checked) lbImportSelected.add(key); else lbImportSelected.delete(key);
        });
        row.appendChild(cb);
        const span = document.createElement('span');
        span.textContent = labelFn(it);
        row.appendChild(span);
        wrap.appendChild(row);
    });
    return wrap;
}

/** 全选/全不选当前抽屉内所有候选 */
function toggleAllLorebookImport() {
    const boxes = document.querySelectorAll('#lorebookImportList input[type="checkbox"]');
    const allChecked = boxes.length > 0 && Array.from(boxes).every(b => b.checked);
    lbImportSelected = new Set();
    boxes.forEach(b => {
        b.checked = !allChecked;
        if (b.checked) lbImportSelected.add(b.dataset.track + ':' + b.value);
    });
}

/** 提交导入：按轨拆分 refs，POST /import；成功后重拉 current 并回浏览态 */
async function confirmLorebookImport() {
    if (lbImportSelected.size === 0) { showCompactToast('请先勾选要导入的条目'); return; }
    const starRefs = [];
    const entryRefs = [];
    lbImportSelected.forEach(key => {
        const idx = key.indexOf(':');
        const track = key.substring(0, idx);
        const ref = key.substring(idx + 1);
        if (track === 'star') starRefs.push(ref); else entryRefs.push(ref);
    });
    const btn = document.getElementById('lorebookImportConfirm');
    btn.disabled = true;
    try {
        const res = await fetch('/rpg/lorebook-set/import', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({gameStateId: currentGameStateId, starRefs, entryRefs})
        });
        const body = await res.json().catch(() => null);
        if (!res.ok) throw new Error((body && body.error) || ('HTTP ' + res.status));
        showCompactToast(`✅ 已导入 ${body.imported} 条`);
        showLorebookBrowse();
        await loadLorebookCurrent();
    } catch (e) {
        showCompactToast(`❌ 导入失败：${e.message}`);
    } finally {
        btn.disabled = false;
    }
}

/**
 * 渲染名录条目（全 DOM API，无 innerHTML 拼接——lore 虽来自 md 真源仍防注入）。
 * @param withRuntime 存档视图（true）时渲染已揭示 / 星归玩家标注；
 *                    静态视图（工坊块）只渲染名录与不可命中标注
 */
function renderLorebookEntries(container, entries, withRuntime) {
    container.replaceChildren();
    if (!entries || entries.length === 0) {
        container.appendChild(lorebookHintNode('该世界暂无设定集条目（名录随预设世界物化写入）。'));
        return;
    }
    entries.forEach(entry => {
        const item = document.createElement('div');
        item.className = 'lorebook-entry';
        if (entry.claimedByPlayer) {
            // 抢星条目（存档级）：视觉最重——红调加粗边框
            item.classList.add('claimed');
        } else if (withRuntime && entry.activated) {
            // 已揭示（sticky 常驻）：轻红边框
            item.classList.add('revealed');
        }

        const head = document.createElement('div');
        head.className = 'lorebook-entry-head';
        const title = document.createElement('span');
        title.className = 'lorebook-entry-title';
        title.textContent = `${entry.starName}·${entry.warlordName}`;
        head.appendChild(title);
        // 不可命中（天兽/天魔，rollable=false）：不可被玩家推演命中
        if (!entry.rollable) {
            head.appendChild(lorebookBadgeNode('npc', '不可命中'));
        }
        if (withRuntime && entry.claimedByPlayer) {
            head.appendChild(lorebookBadgeNode('claimed', '⭐ 星归玩家'));
        } else if (withRuntime && entry.activated) {
            head.appendChild(lorebookBadgeNode('revealed',
                    `已揭示${entry.activatedTurn != null ? ` · 第 ${entry.activatedTurn} 轮` : ''}`));
        }
        item.appendChild(head);

        if (entry.lore) {
            const lore = document.createElement('div');
            lore.className = 'lorebook-entry-lore';
            lore.textContent = entry.lore;
            item.appendChild(lore);
        }
        if (entry.aliases) {
            const aliases = document.createElement('div');
            aliases.className = 'lorebook-entry-aliases';
            aliases.textContent = `别名：${entry.aliases}`;
            item.appendChild(aliases);
        }
        container.appendChild(item);
    });
}

function lorebookBadgeNode(kind, text) {
    const span = document.createElement('span');
    span.className = `lorebook-badge ${kind}`;
    span.textContent = text;
    return span;
}

function lorebookHintNode(text) {
    const div = document.createElement('div');
    div.className = 'lorebook-hint';
    div.textContent = text;
    return div;
}

// ===== 工坊模式：世界观 tab 只读名录块 =====
/**
 * 世界观选中后刷新名录块；空名录（自定义世界 / 未物化）或端点不可用（503 停用）
 * 时整块隐藏——名录是增强展示，绝不影响工坊表单交互。
 */
async function refreshWorkshopLorebook(worldId) {
    const block = document.getElementById('lorebookBlock');
    const list = document.getElementById('lorebookWorkshopList');
    if (!block || !list) return;
    if (!worldId) {
        block.hidden = true;
        list.replaceChildren();
        return;
    }
    try {
        const res = await fetch(`/rpg/lorebook/${encodeURIComponent(worldId)}`);
        if (!res.ok) {
            block.hidden = true;
            return;
        }
        const entries = await res.json();
        if (!entries || entries.length === 0) {
            block.hidden = true;
            list.replaceChildren();
            return;
        }
        block.hidden = false;
        renderLorebookEntries(list, entries, false);
    } catch (e) {
        // 网络异常静默降级：工坊表单不受影响
        block.hidden = true;
    }
}

// ===== 开局「资料来源」步（preset.js 三步向导 / game.js 沙盒选择弹窗共用，D4/D10）=====

/** 拉取本世界全部 set（含孤儿）；失败返回空数组（来源步降级为仅「世界默认」） */
async function fetchLorebookSources(worldId) {
    if (!worldId) return [];
    try {
        const res = await fetch(`/rpg/lorebook-set/sources?worldId=${encodeURIComponent(worldId)}`);
        if (!res.ok) return [];
        return await res.json();
    } catch (e) {
        return [];
    }
}

/**
 * 渲染资料来源单选列表：首项恒为「世界默认」（id=null），其后本世界各 set
 * （孤儿标「存档已删」+ 删除按钮）。点整卡选中，回调 onSelect(setId|null)。
 * @param container  目标容器
 * @param sources    SourceView[]（fetchLorebookSources 结果）
 * @param selectedId 当前选中 set id（null=世界默认）
 * @param onSelect   选中回调 (setId|null) => void（调用方更新自身状态后重渲染）
 * @param onDeleted  孤儿删除成功后回调（用于重拉列表）
 */
function renderLorebookSourceList(container, sources, selectedId, onSelect, onDeleted) {
    container.replaceChildren();
    container.appendChild(buildLorebookSourceItem(
        {id: null, name: '世界默认', origin: '按世界模板物化的初始名录', orphan: false},
        selectedId, onSelect, onDeleted));
    (sources || []).forEach(s => {
        container.appendChild(buildLorebookSourceItem(s, selectedId, onSelect, onDeleted));
    });
}

function buildLorebookSourceItem(s, selectedId, onSelect, onDeleted) {
    const item = document.createElement('div');
    item.className = 'lorebook-source-item';
    const isSelected = s.id == null ? selectedId == null : s.id === selectedId;
    if (isSelected) item.classList.add('selected');

    const marker = document.createElement('span');
    marker.className = 'lorebook-source-marker';
    marker.textContent = isSelected ? '◉' : '○';
    item.appendChild(marker);

    const body = document.createElement('div');
    body.className = 'lorebook-source-body';
    const name = document.createElement('div');
    name.className = 'lorebook-source-name';
    name.textContent = s.name || '（未命名副本）';
    body.appendChild(name);
    if (s.origin || s.createdAt) {
        const meta = document.createElement('div');
        meta.className = 'lorebook-source-meta';
        const created = s.createdAt ? String(s.createdAt).replace('T', ' ').substring(0, 16) : '';
        meta.textContent = `${s.origin || ''}${created ? ' · ' + created : ''}`;
        body.appendChild(meta);
    }
    item.appendChild(body);

    if (s.orphan) {
        item.appendChild(lorebookBadgeNode('off', '存档已删'));
        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'lorebook-mini-btn danger';
        del.textContent = '删除';
        del.addEventListener('click', async ev => {
            ev.stopPropagation();
            if (!window.confirm(`确认删除孤儿世界书「${s.name || '未命名'}」？此操作不可撤销。`)) return;
            try {
                const res = await fetch(`/rpg/lorebook-set/${encodeURIComponent(s.id)}`, {method: 'DELETE'});
                if (!res.ok && res.status !== 204) {
                    const b = await res.json().catch(() => null);
                    throw new Error((b && b.error) || ('HTTP ' + res.status));
                }
                showCompactToast('🗑️ 孤儿世界书已删除');
                if (onDeleted) onDeleted();
            } catch (e) {
                showCompactToast(`❌ 删除失败：${e.message}`);
            }
        });
        item.appendChild(del);
    }
    item.addEventListener('click', () => onSelect(s.id));
    return item;
}
