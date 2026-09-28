/**
 * 首页存档卡片列表（加载/继续/删除）（rpg-js-split 拆分自 rpg.js，
 * rpg-home-entry：渲染目标从 loadOverlay 弹窗迁至首页 home-grid）
 *
 * 本文件产出的全局符号：
 *   函数：refreshHomeSaves, renderSaveCards, buildSaveCard, formatSaveTime,
 *         continueSave, deleteSaveCard, updateSaveCount
 *   依赖（运行时）：state.js（currentGameStateId, currentSessionId, currentWorldId,
 *         persistIdentity, RPG_STORAGE_KEY）；game.js（showView, startOpening,
 *         updateStatusPanel）；messages.js（clearMessages, loadHistory）
 */

// ===== 首页存档列表拉取与渲染 =====
/** 拉取全部存档卡片并渲染到首页网格，同步计数与空态 */
async function refreshHomeSaves() {
    try {
        const res = await fetch('/rpg/game/saves');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const cards = await res.json();
        renderSaveCards(cards);
    } catch (e) { alert('加载存档列表失败: ' + e.message); }
}

/** 按首页剩余卡片数同步计数与空态显隐 */
function updateSaveCount() {
    const list = document.getElementById('saveList');
    const empty = document.getElementById('saveEmpty');
    const count = list ? list.querySelectorAll('.save-card').length : 0;
    document.getElementById('homeSaveCount').textContent = '共 ' + count + ' 个存档';
    empty.hidden = count > 0;
}

function renderSaveCards(cards) {
    const list = document.getElementById('saveList');
    list.innerHTML = '';
    if (Array.isArray(cards)) {
        cards.forEach(card => list.appendChild(buildSaveCard(card)));
    }
    updateSaveCount();
}

function buildSaveCard(card) {
    const el = document.createElement('div');
    el.className = 'save-card';

    const head = document.createElement('div');
    head.className = 'save-card-head';
    const world = document.createElement('span');
    world.className = 'save-card-world';
    world.textContent = card.worldName || '-';
    world.title = world.textContent;
    const time = document.createElement('span');
    time.className = 'save-card-time';
    time.textContent = formatSaveTime(card.lastPlayedAt);
    head.appendChild(world);
    head.appendChild(time);

    const charRow = document.createElement('div');
    charRow.className = 'save-card-char';
    const char = document.createElement('span');
    char.textContent = card.playerCharName || '-';
    const loc = document.createElement('span');
    loc.className = 'save-card-location';
    loc.textContent = ' · ' + (card.currentLocation || '-');
    charRow.appendChild(char);
    charRow.appendChild(loc);

    const meta = document.createElement('div');
    meta.className = 'save-card-meta';
    meta.textContent = '轮次 ' + (card.turnCount == null ? 0 : card.turnCount);

    const actions = document.createElement('div');
    actions.className = 'save-card-actions';
    const continueBtn = document.createElement('button');
    continueBtn.className = 'save-card-btn continue';
    continueBtn.textContent = '继续';
    continueBtn.addEventListener('click', () => continueSave(card));
    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'save-card-btn delete';
    deleteBtn.textContent = '删除';
    deleteBtn.addEventListener('click', async () => deleteSaveCard(card, el));
    actions.appendChild(continueBtn);
    actions.appendChild(deleteBtn);

    el.appendChild(head);
    el.appendChild(charRow);
    el.appendChild(meta);
    el.appendChild(actions);
    return el;
}

function formatSaveTime(iso) {
    if (!iso) return '-';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '-';
    const min = Math.floor((Date.now() - d.getTime()) / 60000);
    if (min < 1) return '刚刚';
    if (min < 60) return min + ' 分钟前';
    const hour = Math.floor(min / 60);
    if (hour < 24) return hour + ' 小时前';
    const day = Math.floor(hour / 24);
    if (day < 30) return day + ' 天前';
    return d.toLocaleDateString('zh-CN');
}

async function continueSave(card) {
    try {
        currentGameStateId = card.gameStateId;
        currentWorldId = card.worldId || currentWorldId;
        // 复用存档关联的 session 以重放历史；无关联时才新建
        currentSessionId = card.sessionId || crypto.randomUUID();
        persistIdentity();
        // 更新 session 关联
        await fetch('/rpg/game/load', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({gameStateId: card.gameStateId, sessionId: currentSessionId})
        });
        showView('game');
        clearMessages();
        // 重放该存档关联 session 的历史；为空（旧存档/空会话）则回退到重新生成开场白
        const replayed = await loadHistory();
        if (replayed) {
            document.getElementById('inputArea').hidden = false;
            document.getElementById('gameInput').focus();
            updateStatusPanel(card.gameStateId);
        } else {
            await startOpening(card.gameStateId);
        }
    } catch (e) { alert('继续存档失败: ' + e.message); }
}

async function deleteSaveCard(card, cardEl) {
    const ok = confirm('确定删除该存档？\n\n世界：' + (card.worldName || '-') +
        '\n角色：' + (card.playerCharName || '-') +
        '\n轮次：' + (card.turnCount == null ? 0 : card.turnCount) +
        '\n\n删除后将清除全部剧情与存档数据，不可恢复。');
    if (!ok) return;
    try {
        const res = await fetch('/rpg/game/state/' + encodeURIComponent(card.gameStateId), {method: 'DELETE'});
        if (res.status === 204) {
            cardEl.remove();
            // 删除的是当前在玩存档：复位本地状态，停留首页（design D6）
            if (card.gameStateId === currentGameStateId) {
                currentGameStateId = null;
                currentSessionId = null;
                try { localStorage.removeItem(RPG_STORAGE_KEY); } catch (e) {}
                clearMessages();
            }
            updateSaveCount();
        } else if (res.status === 409) {
            const body = await res.json().catch(() => ({}));
            alert(body.error || '该存档当前有回合正在进行，请稍后再试');
        } else if (res.status === 404) {
            alert('存档不存在，可能已被删除');
            cardEl.remove();
            updateSaveCount();
        } else {
            alert('删除失败，请稍后再试');
        }
    } catch (e) { alert('删除失败: ' + e.message); }
}
