/**
 * 共享世界书库（add-shared-worldbook-publish design D8）
 *
 * 工坊世界书 tab 两个入口：
 *   「🚀 发布到共享库」弹窗：拉当前世界双轨模板行（两轨端点均只返回 set_id IS NULL 的
 *     模板层，存档 set 副本天然不可选）勾选子集，连同包名/简介快照发布到平台共享库；
 *     同租户同世界重复发布覆盖自己的旧包（design D4）。
 *   「🌐 共享库」弹窗：全平台在架包卡片墙（登录即可见，无租户过滤）→ 详情 → 导入到
 *     当前打开的世界。导入落模板层，此后 fork/继承/弹窗编辑/开局来源步等既有机制
 *     照常接管（design D5）；导入前置校验的 400/409 错误文案在详情区内呈现。
 *
 * 本文件产出的全局符号：无（事件在加载时绑定，init.js 无需接入）
 *   依赖（运行时）：state.js（currentWorldId）；game.js（showCompactToast）；
 *         workshop.js（refreshWorkshopWorldbook）、lorebook.js（refreshWorkshopLorebook）
 *         —— 导入成功后刷新工坊双轨展示（动态判存在，防加载顺序变动）
 *   加载顺序：lorebook.js 之后、init.js 之前
 */

// ===== 状态 =====
let spPublishSelected = new Set();   // 发布弹窗已选条目（键 track:id，两轨 id 域独立）
let spDetailPackId = null;           // 共享库详情态当前包 id
let spDetail = null;                 // 最近一次包详情响应（{pack, worldbookEntries, starEntries}）

// ===== 发布弹窗（design D4：勾选子集 + 覆盖更新旧包）=====

document.getElementById('publishSharedPackBtn').addEventListener('click', openSharedPublishDialog);
document.getElementById('closeSharedPublishBtn').addEventListener('click', hideSharedPublishDialog);
document.getElementById('sharedPublishCancelBtn').addEventListener('click', hideSharedPublishDialog);
document.getElementById('sharedPublishToggleAll').addEventListener('click', toggleAllSharedPublish);
document.getElementById('sharedPublishConfirmBtn').addEventListener('click', confirmSharedPublish);

async function openSharedPublishDialog() {
    if (!currentWorldId) { showCompactToast('请先在「世界观」tab 选定或新建世界'); return; }
    document.getElementById('sharedPublishOverlay').hidden = false;
    spPublishSelected = new Set();
    document.getElementById('sharedPackNameInput').value = '';
    document.getElementById('sharedPackBlurbInput').value = '';
    await loadSharedPublishEntries();
}

function hideSharedPublishDialog() {
    document.getElementById('sharedPublishOverlay').hidden = true;
}

/** 并行拉当前世界双轨模板行（均用工坊端点，条目携带 id 供勾选提交）；任一轨端点异常（如 503 停用）按空轨降级，不阻断另一轨 */
async function loadSharedPublishEntries() {
    const list = document.getElementById('sharedPublishList');
    list.replaceChildren(spHintNode('模板条目加载中…'));
    let entries = [];
    let stars = [];
    try {
        const [wbRes, starRes] = await Promise.all([
            fetch(`/rpg/workshop/worldbook?worldId=${encodeURIComponent(currentWorldId)}`),
            fetch(`/rpg/workshop/star?worldId=${encodeURIComponent(currentWorldId)}`)
        ]);
        if (wbRes.ok) entries = await wbRes.json();
        if (starRes.ok) stars = await starRes.json();
    } catch (e) {
        list.replaceChildren(spHintNode(`模板条目加载失败：${e.message}`));
        return;
    }
    renderSharedPublishList(entries || [], stars || []);
}

function renderSharedPublishList(entries, stars) {
    const list = document.getElementById('sharedPublishList');
    list.replaceChildren();
    if (entries.length === 0 && stars.length === 0) {
        list.appendChild(spHintNode('当前世界暂无可发布的模板条目（只有存档副本或空世界）。'));
        return;
    }
    if (stars.length > 0) {
        list.appendChild(buildSharedPublishGroup('📖 设定集', 'star', stars));
    }
    if (entries.length > 0) {
        list.appendChild(buildSharedPublishGroup('📚 通用世界书', 'worldbook', entries));
    }
}

/** 单轨勾选组（复用 lorebook 导入抽屉的 group/item 形态；设定集含不可命中徽章、通用含停用标；star 轨勾选项按分类归组） */
function buildSharedPublishGroup(title, track, items) {
    const wrap = document.createElement('div');
    wrap.className = 'lorebook-import-group';
    const head = document.createElement('div');
    head.className = 'memory-group-title';
    head.textContent = `${title}（${items.length}）`;
    wrap.appendChild(head);
    // 分类归组（add-star-lorebook-category D4）：勾选项切换处插子分组标题行（展示层组织，
    // 不影响勾选与打包数据）；无分类条目平铺
    let lastCat = null;
    items.forEach(it => {
        if (track === 'star') {
            const cat = it.category ? String(it.category).trim() : '';
            if (cat && cat !== lastCat) {
                const sub = document.createElement('div');
                sub.className = 'lorebook-group-title';
                sub.textContent = cat;
                wrap.appendChild(sub);
            }
            lastCat = cat;
        }
        const row = document.createElement('label');
        row.className = 'lorebook-import-item';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.value = it.id;
        cb.dataset.track = track;
        cb.addEventListener('change', () => {
            const key = track + ':' + it.id;
            if (cb.checked) spPublishSelected.add(key); else spPublishSelected.delete(key);
        });
        row.appendChild(cb);
        const label = document.createElement('span');
        if (track === 'star') {
            label.textContent = `${it.starName}·${it.warlordName}`;
        } else {
            label.textContent = (it.enabled === false ? '〔停用〕' : '') + (it.title || '(无标题)');
        }
        row.appendChild(label);
        if (track === 'star' && it.rollable === false) {
            row.appendChild(spBadgeNode('npc', '不可命中'));
        }
        wrap.appendChild(row);
    });
    return wrap;
}

/** 全选/全不选发布弹窗所有条目（对齐 lorebook 导入抽屉切换语义） */
function toggleAllSharedPublish() {
    const boxes = document.querySelectorAll('#sharedPublishList input[type="checkbox"]');
    const allChecked = boxes.length > 0 && Array.from(boxes).every(b => b.checked);
    spPublishSelected = new Set();
    boxes.forEach(b => {
        b.checked = !allChecked;
        if (b.checked) spPublishSelected.add(b.dataset.track + ':' + b.value);
    });
}

/** 提交发布：按轨拆分 id，POST /rpg/shared-packs；同世界旧包由后端覆盖（overwrite 回执） */
async function confirmSharedPublish() {
    if (!currentWorldId) { showCompactToast('请先选定世界'); return; }
    const name = document.getElementById('sharedPackNameInput').value.trim();
    if (!name) { showCompactToast('❌ 包名不能为空'); return; }
    const worldbookIds = [];
    const starIds = [];
    spPublishSelected.forEach(key => {
        const idx = key.indexOf(':');
        const track = key.substring(0, idx);
        const id = key.substring(idx + 1);
        if (track === 'star') starIds.push(id); else worldbookIds.push(id);
    });
    if (worldbookIds.length === 0 && starIds.length === 0) {
        showCompactToast('请先勾选要发布的条目');
        return;
    }
    const btn = document.getElementById('sharedPublishConfirmBtn');
    btn.disabled = true;
    try {
        const res = await fetch('/rpg/shared-packs', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                worldId: currentWorldId,
                name,
                blurb: document.getElementById('sharedPackBlurbInput').value.trim(),
                worldbookIds,
                starIds
            })
        });
        const body = await res.json().catch(() => null);
        if (!res.ok) throw new Error((body && body.error) || ('HTTP ' + res.status));
        showCompactToast(body.overwrite
            ? `✅ 已覆盖更新共享包（共 ${body.entryCount} 条）`
            : `✅ 已发布到共享库（共 ${body.entryCount} 条）`);
        hideSharedPublishDialog();
    } catch (e) {
        showCompactToast(`❌ 发布失败：${e.message}`);
    } finally {
        btn.disabled = false;
    }
}

// ===== 共享库弹窗（design D8：卡片墙 → 详情 → 导入/撤回）=====

document.getElementById('openSharedGalleryBtn').addEventListener('click', openSharedGalleryDialog);
document.getElementById('closeSharedGalleryBtn').addEventListener('click', closeSharedGalleryDialog);
document.getElementById('sharedBackBtn').addEventListener('click', () => showSharedGalleryList());
document.getElementById('sharedImportBtn').addEventListener('click', importCurrentSharedPack);
document.getElementById('sharedRevokeBtn').addEventListener('click', revokeCurrentSharedPack);

async function openSharedGalleryDialog() {
    document.getElementById('sharedGalleryOverlay').hidden = false;
    await showSharedGalleryList();
}

function closeSharedGalleryDialog() {
    document.getElementById('sharedGalleryOverlay').hidden = true;
}

/** 回画廊态：清详情上下文并重拉在架包列表 */
async function showSharedGalleryList() {
    spDetailPackId = null;
    spDetail = null;
    document.getElementById('sharedPackDetail').hidden = true;
    const list = document.getElementById('sharedGalleryList');
    list.hidden = false;
    list.replaceChildren(spHintNode('共享库加载中…'));
    try {
        const res = await fetch('/rpg/shared-packs');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const packs = await res.json();
        renderSharedGallery(packs || []);
    } catch (e) {
        list.replaceChildren(spHintNode(`共享库加载失败：${e.message}`));
    }
}

/** 在架包卡片墙（revoked 已被后端过滤；空库给引导文案） */
function renderSharedGallery(packs) {
    const list = document.getElementById('sharedGalleryList');
    list.replaceChildren();
    if (packs.length === 0) {
        list.appendChild(spHintNode('共享库还是空的。发布第一个包，或等其他团队分享。'));
        return;
    }
    packs.forEach(p => list.appendChild(buildSharedPackCard(p)));
}

/** 画廊卡片：名称 + 简介 + meta（发布者 · 条数 · 更新时间），整卡点开详情 */
function buildSharedPackCard(p) {
    const card = document.createElement('div');
    card.className = 'shared-pack-card';
    card.addEventListener('click', () => showSharedPackDetail(p.id));

    const name = document.createElement('div');
    name.className = 'shared-pack-name';
    name.textContent = p.name || '未命名包';
    card.appendChild(name);
    if (p.blurb) {
        const blurb = document.createElement('p');
        blurb.className = 'shared-pack-blurb';
        blurb.textContent = p.blurb;
        card.appendChild(blurb);
    }
    const meta = document.createElement('div');
    meta.className = 'shared-pack-meta';
    const updated = spTimeLabel(p.updatedAt);
    meta.textContent = `发布者：${spPublisherLabel(p)} · ${p.entryCount} 条${updated ? ' · 更新 ' + updated : ''}`;
    card.appendChild(meta);
    return card;
}

/** 包详情（404 = 已撤回/不存在 → 提示并回列表） */
async function showSharedPackDetail(packId) {
    try {
        const res = await fetch(`/rpg/shared-packs/${encodeURIComponent(packId)}`);
        if (!res.ok) {
            showCompactToast('该包已下架或不存在');
            await showSharedGalleryList();
            return;
        }
        spDetail = await res.json();
        spDetailPackId = packId;
    } catch (e) {
        showCompactToast(`包详情加载失败：${e.message}`);
        return;
    }
    document.getElementById('sharedGalleryList').hidden = true;
    hideSharedDetailMsg();
    renderSharedDetail();
    document.getElementById('sharedPackDetail').hidden = false;
}

/** 详情头（含出处世界名）+ 双轨条目只读列表（复用 lorebook-entry 卡形态） */
function renderSharedDetail() {
    if (!spDetail) return;
    const pack = spDetail.pack;

    const head = document.getElementById('sharedDetailHead');
    head.replaceChildren();
    const name = document.createElement('div');
    name.className = 'shared-pack-name';
    name.textContent = pack.name || '未命名包';
    head.appendChild(name);
    if (pack.blurb) {
        const blurb = document.createElement('p');
        blurb.className = 'shared-pack-blurb';
        blurb.textContent = pack.blurb;
        head.appendChild(blurb);
    }
    const meta = document.createElement('div');
    meta.className = 'shared-pack-meta';
    const origin = pack.originWorldName ? ` · 出处：${pack.originWorldName}` : '';
    const updated = spTimeLabel(pack.updatedAt);
    meta.textContent = `发布者：${spPublisherLabel(pack)}${origin} · ${pack.entryCount} 条${updated ? ' · 更新 ' + updated : ''}`;
    head.appendChild(meta);

    const list = document.getElementById('sharedDetailEntries');
    list.replaceChildren();
    const stars = spDetail.starEntries || [];
    const entries = spDetail.worldbookEntries || [];
    if (entries.length === 0 && stars.length === 0) {
        list.appendChild(spHintNode('该包没有条目（可能被发布者覆盖为空集）。'));
        return;
    }
    if (stars.length > 0) {
        const group = document.createElement('div');
        group.className = 'memory-group-title';
        group.textContent = `📖 设定集（${stars.length}）`;
        list.appendChild(group);
        // 分类归组（add-star-lorebook-category D4）：切换处插子分组标题行，无分类条目平铺
        let lastCat = null;
        stars.forEach(s => {
            const cat = s.category ? String(s.category).trim() : '';
            if (cat && cat !== lastCat) {
                const sub = document.createElement('div');
                sub.className = 'lorebook-group-title';
                sub.textContent = cat;
                list.appendChild(sub);
            }
            lastCat = cat;
            list.appendChild(spStarCard(s));
        });
    }
    if (entries.length > 0) {
        const group = document.createElement('div');
        group.className = 'memory-group-title';
        group.textContent = `📚 通用世界书（${entries.length}）`;
        list.appendChild(group);
        entries.forEach(e => list.appendChild(spWorldbookCard(e)));
    }
}

/** 设定集条目只读卡（保留不可命中徽章；无存档 runtime 徽章——包内容无存档上下文） */
function spStarCard(s) {
    const item = document.createElement('div');
    item.className = 'lorebook-entry';
    const head = document.createElement('div');
    head.className = 'lorebook-entry-head';
    const title = document.createElement('span');
    title.className = 'lorebook-entry-title';
    title.textContent = `${s.starName}·${s.warlordName}`;
    head.appendChild(title);
    if (s.rollable === false) head.appendChild(spBadgeNode('npc', '不可命中'));
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

/** 通用世界书条目只读卡（停用态灰显标） */
function spWorldbookCard(e) {
    const item = document.createElement('div');
    item.className = 'lorebook-entry worldbook';
    if (e.enabled === false) item.classList.add('disabled');
    const head = document.createElement('div');
    head.className = 'lorebook-entry-head';
    const title = document.createElement('span');
    title.className = 'lorebook-entry-title';
    title.textContent = e.title || '（无标题）';
    head.appendChild(title);
    if (e.enabled === false) head.appendChild(spBadgeNode('off', '已停用'));
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

/**
 * 导入到当前世界：成功提示双轨条数与目标世界名（design D8），并刷新工坊双轨展示。
 * 400（preset- 目标/目标世界无效）与 409（同名星冲突，error 带冲突星名清单）
 * 在详情区文案呈现，不打断浏览。
 */
async function importCurrentSharedPack() {
    if (!spDetailPackId) return;
    if (!currentWorldId) { showSpDetailMsg('请先在「世界观」tab 选定或新建世界，再导入。', 'error'); return; }
    if (currentWorldId.startsWith('preset-')) {
        // 物化预设世界：rewriteAll 会清掉导入的冥星模板行（design D5 坑 ①），前端先行拦截
        showSpDetailMsg('物化预设世界不支持导入（重物化会清除导入条目），请导入到自建世界。', 'error');
        return;
    }
    const btn = document.getElementById('sharedImportBtn');
    btn.disabled = true;
    try {
        const res = await fetch(`/rpg/shared-packs/${encodeURIComponent(spDetailPackId)}/import`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({worldId: currentWorldId})
        });
        const body = await res.json().catch(() => null);
        if (!res.ok) throw new Error((body && body.error) || ('HTTP ' + res.status));
        const worldName = document.getElementById('worldNameInput').value.trim() || '当前世界';
        showCompactToast(`✅ 已导入 ${body.worldbookCount + body.starCount} 条到「${worldName}」`);
        showSpDetailMsg(`已导入通用条目 ${body.worldbookCount} 条、冥星条目 ${body.starCount} 条到「${worldName}」，成为该世界的独立模板行。之后可在「已保存条目」与世界观名录中查看。`, 'ok');
        // 导入行落模板层：刷新工坊世界书选择器与世界观名录块（动态判存在，防加载顺序变动）
        if (typeof refreshWorkshopWorldbook === 'function') await refreshWorkshopWorldbook(currentWorldId);
        if (typeof refreshWorkshopLorebook === 'function') refreshWorkshopLorebook(currentWorldId);
    } catch (e) {
        showSpDetailMsg(`❌ 导入失败：${e.message}`, 'error');
    } finally {
        btn.disabled = false;
    }
}

/** 撤回下架（软标记）：204 成功回列表；404 = 非发布者或已下架（后端判权兜底） */
async function revokeCurrentSharedPack() {
    if (!spDetailPackId) return;
    const btn = document.getElementById('sharedRevokeBtn');
    btn.disabled = true;
    try {
        const res = await fetch(`/rpg/shared-packs/${encodeURIComponent(spDetailPackId)}`, {method: 'DELETE'});
        if (res.status === 204) {
            showCompactToast('✅ 已撤回下架（已导入副本不受影响）');
            await showSharedGalleryList();
        } else if (res.status === 404) {
            showSpDetailMsg('仅发布者可撤回（或该包已下架）。', 'error');
        } else {
            throw new Error('HTTP ' + res.status);
        }
    } catch (e) {
        showSpDetailMsg(`❌ 撤回失败：${e.message}`, 'error');
    } finally {
        btn.disabled = false;
    }
}

// ===== 小工具 =====

function showSpDetailMsg(text, kind) {
    const msg = document.getElementById('sharedDetailMsg');
    msg.textContent = text;
    msg.className = 'shared-detail-msg' + (kind ? ' ' + kind : '');
    msg.hidden = false;
}

function hideSharedDetailMsg() {
    const msg = document.getElementById('sharedDetailMsg');
    msg.hidden = true;
}

function spHintNode(text) {
    const div = document.createElement('div');
    div.className = 'lorebook-hint';
    div.textContent = text;
    return div;
}

function spBadgeNode(kind, text) {
    const span = document.createElement('span');
    span.className = `lorebook-badge ${kind}`;
    span.textContent = text;
    return span;
}

/** 发布者标识：租户 id 过长（UUID 形态）截 8 位展示 */
function spPublisherLabel(pack) {
    const t = pack.publisherTenantId || '';
    return t.length > 20 ? t.substring(0, 8) + '…' : (t || '未知');
}

/** Instant ISO → 本地可读短格式（对齐 lorebook.js 出处行的时间处理） */
function spTimeLabel(iso) {
    return iso ? String(iso).replace('T', ' ').substring(0, 16) : '';
}
