/**
 * 消息列表渲染与回溯按钮 UI（rpg-js-split 拆分自 rpg.js）
 *
 * 本文件产出的全局符号：
 *   函数：getMessagesContainer, clearMessages, scrollNarrationToBottom,
 *         appendMessage, applyMessageMeta, ensureActionsRow, addBacktrackButton,
 *         addRegenerateButton, markLatestRegenerate, refreshBacktrackButtonStates,
 *         setBacktrackButtonsEnabled, appendOpening, loadHistory
 *   依赖（运行时）：state.js 的全局状态变量；markdown.js 的渲染/过滤函数
 */

// ===== 消息列表渲染（追加式，不覆盖历史）=====
function getMessagesContainer() {
    return document.getElementById('messagesContainer');
}

function clearMessages() {
    const c = getMessagesContainer();
    if (c) c.innerHTML = '';
}

function scrollNarrationToBottom() {
    const area = document.getElementById('narrationArea');
    if (area) area.scrollTop = area.scrollHeight;
}

/**
 * 追加一条消息气泡（追加式，历史气泡不被触碰）。
 * @param {'user'|'assistant'} role 玩家 / GM
 * @param {string} content 文本内容
 * @param {{synthetic?:boolean, eventId?:string, approxTurn?:number}} [options]
 *        synthetic=true 渲染为被压缩摘要块；eventId 气泡回溯锚点；approxTurn 近似轮次（置灰用）
 * @returns {HTMLElement|null} 可用于流式更新的内容元素
 */
function appendMessage(role, content, options = {}) {
    const container = getMessagesContainer();
    if (!container) return null;

    // 被压缩摘要：独立摘要样式，区别于普通 GM 气泡
    if (options.synthetic) {
        const wrap = document.createElement('div');
        wrap.className = 'rpg-message summary';
        const bubble = document.createElement('div');
        bubble.className = 'rpg-bubble';
        const label = document.createElement('div');
        label.className = 'summary-label';
        label.textContent = '📝 早期剧情摘要';
        const body = document.createElement('div');
        body.className = 'narration-content';
        body.innerHTML = renderMarkdown(filterStateDelta(content || ''));
        bubble.appendChild(label);
        bubble.appendChild(body);
        wrap.appendChild(bubble);
        container.appendChild(wrap);
        scrollNarrationToBottom();
        return body;
    }

    const wrap = document.createElement('div');
    if (role === 'user') {
        // 玩家气泡：右对齐、红底白字（纯文本，防注入）
        wrap.className = 'rpg-message user';
        const bubble = document.createElement('div');
        bubble.className = 'rpg-bubble';
        bubble.textContent = content || '';
        wrap.appendChild(bubble);
        applyMessageMeta(wrap, options);
        container.appendChild(wrap);
        scrollNarrationToBottom();
        return bubble;
    }

    // GM 叙述卡片：左对齐、宽卡片，沉浸式排版作用域收窄到卡片内部
    wrap.className = 'rpg-message gm';
    const bubble = document.createElement('div');
    bubble.className = 'rpg-bubble narration-content';
    bubble.innerHTML = renderMarkdown(filterStateDelta(content || ''));
    wrap.appendChild(bubble);
    applyMessageMeta(wrap, options);
    container.appendChild(wrap);
    scrollNarrationToBottom();
    return bubble;
}

/** 挂载回溯元数据：eventId 锦点 + 近似轮次；有锚点时渲染【回溯】按钮 */
function applyMessageMeta(wrap, options = {}) {
    if (options.eventId) {
        wrap.dataset.eventId = options.eventId;
        addBacktrackButton(wrap);
    }
    if (options.approxTurn != null) {
        wrap.dataset.approxTurn = String(options.approxTurn);
    }
}

// ===== 消息级回溯/重新生成（rpg-chat-backtrack）=====

/** 气泡下操作行（懒创建） */
function ensureActionsRow(wrap) {
    let row = wrap.querySelector('.rpg-msg-actions');
    if (!row) {
        row = document.createElement('div');
        row.className = 'rpg-msg-actions';
        wrap.appendChild(row);
    }
    return row;
}

/** 【↩ 回溯】：删除该消息起的后续内容，状态与记忆一并回退 */
function addBacktrackButton(wrap) {
    if (!wrap || wrap.querySelector('.backtrack-btn')) return;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'rpg-action-btn backtrack-btn';
    btn.textContent = '↩ 回溯';
    btn.title = '删除该消息起的后续内容，游戏状态与 GM 记忆一并回退';
    btn.addEventListener('click', () => handleBacktrackClick(wrap));
    ensureActionsRow(wrap).appendChild(btn);
}

/** 【↻ 重新生成】：仅最新 GM 轮可见 */
function addRegenerateButton(wrap) {
    if (!wrap || wrap.querySelector('.regenerate-btn')) return;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'rpg-action-btn regenerate-btn';
    btn.textContent = '↻ 重新生成';
    btn.title = '删除最后一轮并重掷触发器/概率，自动重发玩家原文';
    btn.addEventListener('click', () => handleRegenerateClick(wrap));
    ensureActionsRow(wrap).appendChild(btn);
}

/** 重新生成按钮只保留在最新一条带锚点的 GM 气泡上（新回合开始/历史重放后调用） */
function markLatestRegenerate() {
    document.querySelectorAll('#messagesContainer .regenerate-btn').forEach(b => b.remove());
    const gmWraps = [...document.querySelectorAll('#messagesContainer .rpg-message.gm')]
        .filter(w => w.dataset.eventId);
    if (gmWraps.length > 0) addRegenerateButton(gmWraps[gmWraps.length - 1]);
}

/** 超过 3 轮的气泡置灰回溯按钮（前端近似值；后端校验是唯一真源） */
function refreshBacktrackButtonStates() {
    document.querySelectorAll('#messagesContainer .rpg-message').forEach(wrap => {
        if (!wrap.dataset.eventId) return;
        const turn = parseInt(wrap.dataset.approxTurn || '0', 10);
        const tooDeep = lastKnownTurn - turn + 1 > MAX_BACKTRACK_TURNS;
        wrap.querySelectorAll('.backtrack-btn').forEach(btn => {
            btn.disabled = tooDeep;
            btn.title = tooDeep
                ? `最多回溯 ${MAX_BACKTRACK_TURNS} 轮（该消息约在第 ${turn} 轮）`
                : '删除该消息起的后续内容，游戏状态与 GM 记忆一并回退';
        });
    });
}

/** 流式进行中统一禁用/恢复回溯与重新生成按钮（恢复时按深度重新置灰） */
function setBacktrackButtonsEnabled(enabled) {
    document.querySelectorAll('#messagesContainer .rpg-action-btn').forEach(btn => {
        btn.disabled = !enabled;
    });
    if (enabled) refreshBacktrackButtonStates();
}

/**
 * 追加开场白独立块（不套 GM 卡片），返回用于流式填充的内容元素。
 */
function appendOpening() {
    const container = getMessagesContainer();
    if (!container) return null;
    const block = document.createElement('div');
    block.className = 'rpg-opening';
    const inner = document.createElement('div');
    inner.className = 'narration-content';
    block.appendChild(inner);
    container.appendChild(block);
    scrollNarrationToBottom();
    return inner;
}

/**
 * 拉取并重放会话历史（后端 read-time 清洗后的干净消息）。
 * @returns {Promise<boolean>} 是否重放了至少一条消息
 */
async function loadHistory() {
    if (!currentSessionId) return false;
    try {
        const res = await fetch(`/rpg/game/history?sessionId=${encodeURIComponent(currentSessionId)}`);
        if (!res.ok) return false;
        const data = await res.json();
        // 新结构 {currentTurn, messages}；兜底兼容旧数组结构
        const messages = Array.isArray(data) ? data
            : (data && Array.isArray(data.messages) ? data.messages : []);
        if (messages.length === 0) return false;
        if (data && typeof data.currentTurn === 'number') {
            lastKnownTurn = data.currentTurn;
        }
        // 近似轮次倒推（D7）：从末尾向前，每条非合成 user 消息开起一轮；
        // 合成摘要不占轮次，仅作渲染。approxTurn 只用于前端置灰，后端校验才是真源。
        let approxTurn = lastKnownTurn;
        for (let i = messages.length - 1; i >= 0; i--) {
            const m = messages[i];
            m._turn = approxTurn;
            if (m.role === 'user' && m.synthetic !== true) approxTurn--;
        }
        messages.forEach(m => {
            // 工具事件已在后端过滤；此处仅渲染 user / assistant / 合成摘要
            appendMessage(m.role === 'user' ? 'user' : 'assistant', m.content, {
                synthetic: m.synthetic === true,
                eventId: m.eventId,
                approxTurn: m._turn
            });
        });
        // 重新生成按钮只落在最新一条带锚点的 GM 气泡上；再按深度刷新置灰
        markLatestRegenerate();
        refreshBacktrackButtonStates();
        return true;
    } catch (e) {
        console.warn('历史重放失败', e);
        return false;
    }
}
