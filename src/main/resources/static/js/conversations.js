/**
 * 存档页（rpg-conversation-list，原「你的对话」）：RPG 存档对话列表的拉取、渲染、继续与删除
 *
 * 数据源复用 GET /rpg/game/saves（SaveCard 契约 +lastMessage 读时富化，design D1/D2）：
 * 卡片 framing = 标题「worldName」+ 预览行（最近一行可回放对话，null 不渲染 D6）
 * + 状态行（📍地点 · 轮次 · 时间）；角色名已全链路退场（D4）。
 * 继续 = 深链 /rpg?resume=<gameStateId>（init.js 合成伪 card 喂 continueSave，D3）；
 * 删除 = 免确认直发 DELETE /rpg/game/state/{id}（404/409/204 语义复用）。
 *
 * 依赖（运行时）：state.js（currentGameStateId, currentSessionId, RPG_STORAGE_KEY,
 *         restoreIdentity）——仅为删除当前在玩存档时的本地身份守卫（D4）；
 *         本页无消息容器，不调 clearMessages。
 * formatSaveTime 自 saves.js 移入（原宿主函数已退役）。
 */

// ===== 身份恢复：删除守卫需要 currentGameStateId =====
(function restoreForGuard() {
    const saved = restoreIdentity();
    if (saved) {
        currentSessionId = saved.sessionId;
        currentGameStateId = saved.gameStateId;
    }
})();

// ===== 列表拉取与渲染 =====
async function refreshConversations() {
    try {
        const res = await fetch('/rpg/game/saves');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const cards = await res.json();
        renderConversationCards(cards);
    } catch (e) { alert('加载对话列表失败: ' + e.message); }
}

function renderConversationCards(cards) {
    const list = document.getElementById('convList');
    list.innerHTML = '';
    if (Array.isArray(cards)) {
        cards.forEach(card => list.appendChild(buildConversationCard(card)));
    }
    updateConvCount();
}

/** 按页内剩余卡片数同步计数与空态显隐 */
function updateConvCount() {
    const list = document.getElementById('convList');
    const empty = document.getElementById('convEmpty');
    const count = list ? list.querySelectorAll('.save-card').length : 0;
    document.getElementById('convCount').textContent = '共 ' + count + ' 场对话';
    empty.hidden = count > 0;
}

function buildConversationCard(card) {
    const el = document.createElement('div');
    el.className = 'save-card';

    // 标题：所属世界名（缺失占位符 "-" 继承 SaveCard 契约）
    const title = document.createElement('div');
    title.className = 'save-card-title';
    title.textContent = card.worldName || '-';
    title.title = title.textContent;

    // 预览行：最近一行可回放对话（后端读时富化；null = 无可回放消息，不渲染该行 D6）
    const preview = card.lastMessage ? document.createElement('div') : null;
    if (preview) {
        preview.className = 'save-card-preview';
        preview.textContent = card.lastMessage;
        preview.title = card.lastMessage;
    }

    // 状态行：📍地点 · 轮次 · 最后游玩时间
    const status = document.createElement('div');
    status.className = 'save-card-status';
    const loc = document.createElement('span');
    loc.textContent = '📍 ' + (card.currentLocation || '-');
    const turns = document.createElement('span');
    turns.textContent = ' · 轮次 ' + (card.turnCount == null ? 0 : card.turnCount);
    const time = document.createElement('span');
    time.className = 'save-card-time';
    time.textContent = ' · ' + formatSaveTime(card.lastPlayedAt);
    status.appendChild(loc);
    status.appendChild(turns);
    status.appendChild(time);

    const actions = document.createElement('div');
    actions.className = 'save-card-actions';
    const continueBtn = document.createElement('button');
    continueBtn.className = 'save-card-btn continue';
    continueBtn.textContent = '继续';
    continueBtn.addEventListener('click', () => {
        location.href = '/rpg?resume=' + encodeURIComponent(card.gameStateId);
    });
    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'save-card-btn delete';
    deleteBtn.textContent = '删除';
    deleteBtn.addEventListener('click', () => deleteConversation(card, el));
    actions.appendChild(continueBtn);
    actions.appendChild(deleteBtn);

    el.appendChild(title);
    if (preview) el.appendChild(preview);
    el.appendChild(status);
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

// ===== 删除：免确认直删（用户产品决策，不可逆级联清理）→ 端点语义复用 + 本地身份守卫 =====
async function deleteConversation(card, cardEl) {
    try {
        const res = await fetch('/rpg/game/state/' + encodeURIComponent(card.gameStateId), {method: 'DELETE'});
        if (res.status === 204) {
            cardEl.remove();
            // 删除的是当前在玩存档：清本地身份，不留指向已删数据的脏身份（D4）；
            // 本页无消息容器，不调 clearMessages
            if (card.gameStateId === currentGameStateId) {
                currentGameStateId = null;
                currentSessionId = null;
                try { localStorage.removeItem(RPG_STORAGE_KEY); } catch (e) {}
            }
            updateConvCount();
        } else if (res.status === 409) {
            const body = await res.json().catch(() => ({}));
            alert(body.error || '该存档当前有回合正在进行，请稍后再试');
        } else if (res.status === 404) {
            alert('存档不存在，可能已被删除');
            cardEl.remove();
            updateConvCount();
        } else {
            alert('删除失败，请稍后再试');
        }
    } catch (e) { alert('删除失败: ' + e.message); }
}

refreshConversations();
