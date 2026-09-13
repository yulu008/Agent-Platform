/**
 * 沙盒 RPG 前端交互逻辑
 * 工坊模式 + 游戏模式（SSE 流式叙述 + state_delta 过滤 + Markdown 渲染）
 */

// ===== 全局状态 =====
let currentWorldId = null;
let currentGameStateId = null;
let currentSessionId = null;
let isGameMode = false;
let abortController = null;
let narrationBuffer = '';

// 行内 Markdown（代码、加粗）
function inlineMarkdown(s) {
    return s
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

// 简易 Markdown 渲染（块级：标题/引用/列表/分隔线；行内：代码/加粗）
function renderMarkdown(text) {
    const lines = String(text == null ? '' : text).split('\n');
    let html = '';
    let listType = null; // 'ul' | 'ol'
    const closeList = () => { if (listType) { html += '</' + listType + '>'; listType = null; } };
    for (const line of lines) {
        const ul = line.match(/^\s*[-*]\s+(.*)$/);
        const ol = line.match(/^\s*\d+[.)]\s+(.*)$/);
        if (ul) {
            if (listType !== 'ul') { closeList(); html += '<ul>'; listType = 'ul'; }
            html += '<li>' + inlineMarkdown(ul[1]) + '</li>';
            continue;
        }
        if (ol) {
            if (listType !== 'ol') { closeList(); html += '<ol>'; listType = 'ol'; }
            html += '<li>' + inlineMarkdown(ol[1]) + '</li>';
            continue;
        }
        closeList();
        if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) { html += '<hr>'; continue; }
        const h3 = line.match(/^###\s+(.*)$/);
        const h2 = line.match(/^##\s+(.*)$/);
        const h1 = line.match(/^#\s+(.*)$/);
        const bq = line.match(/^>\s?(.*)$/);
        if (h3) { html += '<h3>' + inlineMarkdown(h3[1]) + '</h3>'; continue; }
        if (h2) { html += '<h2>' + inlineMarkdown(h2[1]) + '</h2>'; continue; }
        if (h1) { html += '<h1>' + inlineMarkdown(h1[1]) + '</h1>'; continue; }
        if (bq) { html += '<blockquote>' + inlineMarkdown(bq[1]) + '</blockquote>'; continue; }
        if (line.trim() === '') { html += '<br>'; continue; }
        html += inlineMarkdown(line) + '<br>';
    }
    closeList();
    return html;
}

// state_delta 过滤（客户端二次过滤，后端引擎已过滤一次）
function filterStateDelta(text) {
    return text.replace(/<state_delta>[\s\S]*?<\/state_delta>/gi, '');
}

// ===== 消息列表渲染（追加式，不覆盖历史）=====
const RPG_STORAGE_KEY = 'rpg.identity';

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
 * @param {{synthetic?:boolean}} [options] synthetic=true 渲染为被压缩摘要块
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
    container.appendChild(wrap);
    scrollNarrationToBottom();
    return bubble;
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

// ===== 身份持久化（sessionId + gameStateId）=====
function persistIdentity() {
    try {
        if (currentSessionId && currentGameStateId) {
            localStorage.setItem(RPG_STORAGE_KEY, JSON.stringify({
                sessionId: currentSessionId,
                gameStateId: currentGameStateId
            }));
        }
    } catch (e) { /* localStorage 不可用时忽略 */ }
}

function restoreIdentity() {
    try {
        const raw = localStorage.getItem(RPG_STORAGE_KEY);
        if (!raw) return null;
        const data = JSON.parse(raw);
        if (data && data.sessionId && data.gameStateId) return data;
    } catch (e) { /* 解析失败忽略 */ }
    return null;
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
        const messages = await res.json();
        if (!Array.isArray(messages) || messages.length === 0) return false;
        messages.forEach(m => {
            // 工具事件已在后端过滤；此处仅渲染 user / assistant / 合成摘要
            appendMessage(m.role === 'user' ? 'user' : 'assistant', m.content, {
                synthetic: m.synthetic === true
            });
        });
        return true;
    } catch (e) {
        console.warn('历史重放失败', e);
        return false;
    }
}

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
    try {
        const res = await fetch('/rpg/workshop/world', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        const world = await res.json();
        currentWorldId = world.id;
        alert('世界观已保存: ' + world.name);
        refreshWorldSelects();
    } catch (e) { alert('保存失败: ' + e.message); }
});

document.getElementById('generateWorldBtn').addEventListener('click', () => {
    document.getElementById('worldLlmInput').hidden = false;
});

document.getElementById('confirmGenerateWorldBtn').addEventListener('click', async () => {
    const keywords = document.getElementById('worldKeywords').value;
    if (!keywords) return;
    try {
        const res = await fetch('/rpg/workshop/generate-world', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({keywords})
        });
        if (!res.ok) { alert('生成失败'); return; }
        const world = await res.json();
        document.getElementById('worldNameInput').value = world.name || '';
        document.getElementById('worldEraInput').value = world.era || '';
        document.getElementById('worldDescInput').value = world.settingDesc || '';
        document.getElementById('worldRulesInput').value = world.rules || '[]';
        document.getElementById('worldAtmosphereInput').value = world.atmosphere || '';
        document.getElementById('worldLlmInput').hidden = true;
    } catch (e) { alert('生成失败: ' + e.message); }
});

// ===== 角色卡保存与生成 =====
document.getElementById('saveCharBtn').addEventListener('click', async () => {
    if (!currentWorldId) { alert('请先保存世界观'); return; }
    const data = {
        worldId: currentWorldId,
        type: document.getElementById('charType').value,
        name: document.getElementById('charNameInput').value,
        identity: document.getElementById('charIdentityInput').value,
        personality: document.getElementById('charPersonalityInput').value,
        background: document.getElementById('charBackgroundInput').value,
        motivation: document.getElementById('charMotivationInput').value,
        speechStyle: document.getElementById('charSpeechStyleInput').value,
        knowledge: '[]'
    };
    if (!data.name) { alert('请输入角色名称'); return; }
    try {
        const res = await fetch('/rpg/workshop/character', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });
        const char = await res.json();
        alert('角色卡已保存: ' + char.name);
        refreshCharList();
    } catch (e) { alert('保存失败: ' + e.message); }
});

document.getElementById('generateCharBtn').addEventListener('click', () => {
    document.getElementById('charLlmInput').hidden = false;
});

document.getElementById('confirmGenerateCharBtn').addEventListener('click', async () => {
    const desc = document.getElementById('charDesc').value;
    const type = document.getElementById('charType').value;
    if (!desc) return;
    try {
        const res = await fetch('/rpg/workshop/generate-character', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({description: desc, type})
        });
        if (!res.ok) { alert('生成失败'); return; }
        const char = await res.json();
        document.getElementById('charNameInput').value = char.name || '';
        document.getElementById('charIdentityInput').value = char.identity || '';
        document.getElementById('charPersonalityInput').value = char.personality || '';
        document.getElementById('charBackgroundInput').value = char.background || '';
        document.getElementById('charMotivationInput').value = char.motivation || '';
        document.getElementById('charSpeechStyleInput').value = char.speechStyle || '';
        document.getElementById('charLlmInput').hidden = true;
    } catch (e) { alert('生成失败: ' + e.message); }
});

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

// ===== 开始冒险 =====
document.getElementById('startAdventureBtn').addEventListener('click', async () => {
    await refreshWorldSelects();
    document.getElementById('startOptions').hidden = false;
});

async function refreshWorldSelects() {
    try {
        const res = await fetch('/rpg/workshop/worlds');
        const worlds = await res.json();
        const sel = document.getElementById('selectWorld');
        sel.innerHTML = '<option value="">选择世界观...</option>';
        worlds.forEach(w => {
            const opt = document.createElement('option');
            opt.value = w.id;
            opt.textContent = w.name;
            sel.appendChild(opt);
        });
    } catch (e) { console.error('加载世界观列表失败', e); }
}

document.getElementById('selectWorld').addEventListener('change', async (e) => {
    const worldId = e.target.value;
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/characters?worldId=${worldId}&type=player`);
        const chars = await res.json();
        const sel = document.getElementById('selectPlayer');
        sel.innerHTML = '<option value="">选择玩家角色...</option>';
        chars.forEach(c => {
            const opt = document.createElement('option');
            opt.value = c.id;
            opt.textContent = c.name;
            sel.appendChild(opt);
        });
    } catch (e) { console.error('加载角色列表失败', e); }
});

document.getElementById('confirmStartBtn').addEventListener('click', async () => {
    const worldId = document.getElementById('selectWorld').value;
    const playerCharId = document.getElementById('selectPlayer').value;
    if (!worldId || !playerCharId) { alert('请选择世界观和玩家角色'); return; }

    currentSessionId = crypto.randomUUID();
    try {
        // 创建 GameState
        const res = await fetch('/rpg/workshop/start', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({worldId, playerCharId, sessionId: currentSessionId})
        });
        const gs = await res.json();
        currentGameStateId = gs.id;
        currentWorldId = worldId;
        // 持久化身份（sessionId + gameStateId），支持刷新恢复
        persistIdentity();
        // 切换到游戏模式
        switchToGameMode();
        // 新开局：清空容器后展示开场白
        clearMessages();
        await startOpening(gs.id);
    } catch (e) { alert('启动游戏失败: ' + e.message); }
});

// ===== 模式切换 =====
function switchToGameMode() {
    isGameMode = true;
    document.getElementById('workshopMode').hidden = true;
    document.getElementById('gameMode').hidden = false;
    document.getElementById('modeToggle').textContent = '工坊模式';
    document.getElementById('saveBtn').hidden = false;
    document.getElementById('loadBtn').hidden = false;
}

document.getElementById('modeToggle').addEventListener('click', () => {
    if (isGameMode) {
        document.getElementById('gameMode').hidden = true;
        document.getElementById('workshopMode').hidden = false;
        document.getElementById('modeToggle').textContent = '游戏模式';
        isGameMode = false;
    } else if (currentGameStateId) {
        document.getElementById('workshopMode').hidden = true;
        document.getElementById('gameMode').hidden = false;
        document.getElementById('modeToggle').textContent = '工坊模式';
        isGameMode = true;
    }
});

// ===== 开场白 SSE 流式 =====
async function startOpening(gameStateId) {
    document.getElementById('inputArea').hidden = true;
    // 开场白作为独立块置顶（不套 GM 卡片），流式只更新该块
    const openingEl = appendOpening();
    narrationBuffer = '';

    try {
        const res = await fetch('/rpg/game/start', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({gameStateId, sessionId: currentSessionId})
        });
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const {done, value} = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, {stream: true});
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                if (line.startsWith('data:')) {
                    const data = line.slice(5).trim();
                    if (data === '[DONE]') continue;
                    try {
                        const parsed = JSON.parse(data);
                        if (parsed.content) {
                            narrationBuffer += parsed.content;
                            // 过滤 state_delta 后渲染，仅更新开场白块
                            const filtered = filterStateDelta(narrationBuffer);
                            if (openingEl) openingEl.innerHTML = renderMarkdown(filtered);
                            scrollNarrationToBottom();
                        } else if (parsed.type === 'opening_complete') {
                            // 开场白完成
                        }
                    } catch (e) { /* ignore parse errors */ }
                }
            }
        }
    } catch (e) {
        console.error('开场白加载失败', e);
    }

    // 显示输入区域
    document.getElementById('inputArea').hidden = false;
    document.getElementById('gameInput').focus();
    updateStatusPanel(gameStateId);
}

// ===== 游戏回合 SSE 流式 =====
const gameForm = document.getElementById('gameForm');
const gameInput = document.getElementById('gameInput');
const gameSendBtn = document.getElementById('gameSendBtn');
const gameStopBtn = document.getElementById('gameStopBtn');
const modelSelector = document.getElementById('modelSelector');

// ===== 模型选择器（参考 chat.js）=====
async function loadModels() {
    if (!modelSelector) return;
    try {
        const res = await fetch('/chat/models');
        if (!res.ok) return;
        const models = await res.json();
        // 保留默认占位项（value="" 表示用后端 default 模型）
        modelSelector.innerHTML = '<option value="">默认（云端）</option>';
        models.forEach(name => {
            const opt = document.createElement('option');
            opt.value = name;
            opt.textContent = name;
            modelSelector.appendChild(opt);
        });
    } catch (e) {
        console.warn('模型列表加载失败:', e);
    }
}
loadModels();

gameForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const message = gameInput.value.trim();
    if (!message || !currentGameStateId) return;

    gameInput.value = '';
    gameInput.disabled = true;
    gameSendBtn.hidden = true;
    gameStopBtn.hidden = false;

    abortController = new AbortController();

    // 追加玩家气泡（右、红底白字）；随后追加一个空 GM 卡片承接流式叙述（不再插入 <hr>）
    appendMessage('user', message);
    const gmBubble = appendMessage('assistant', '');
    narrationBuffer = '';

    try {
        const res = await fetch('/rpg/game/turn', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                gameStateId: currentGameStateId,
                sessionId: currentSessionId,
                message,
                model: modelSelector ? modelSelector.value : ''
            }),
            signal: abortController.signal
        });

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const {done, value} = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, {stream: true});
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                if (line.startsWith('data:')) {
                    const data = line.slice(5).trim();
                    if (data === '[DONE]') continue;
                    try {
                        const parsed = JSON.parse(data);
                        if (parsed.content) {
                            narrationBuffer += parsed.content;
                            // 流式只更新当前 GM 气泡，历史气泡不被触碰
                            const filtered = filterStateDelta(narrationBuffer);
                            if (gmBubble) gmBubble.innerHTML = renderMarkdown(filtered);
                            scrollNarrationToBottom();
                        } else if (parsed.error) {
                            if (gmBubble) {
                                gmBubble.innerHTML += `<p class="rpg-error">⚠️ ${parsed.error}</p>`;
                            }
                        }
                    } catch (e) { /* ignore */ }
                }
            }
        }
    } catch (e) {
        if (e.name === 'AbortError') {
            console.log('流式已中止');
        } else {
            console.error('回合请求失败', e);
        }
    }

    gameInput.disabled = false;
    gameSendBtn.hidden = false;
    gameStopBtn.hidden = true;
    abortController = null;
    gameInput.focus();
    updateStatusPanel(currentGameStateId);
    refreshContextInfo();
});

gameStopBtn.addEventListener('click', () => {
    if (abortController) abortController.abort();
});

// ===== 上下文用量进度条（参考 chat.js，复用 /chat/context-info 计量端点）=====
const contextBar = document.getElementById('contextBar');
const contextBarFill = document.getElementById('contextBarFill');
const contextBarText = document.getElementById('contextBarText');
const compactBtn = document.getElementById('compactBtn');

async function refreshContextInfo() {
    if (!currentSessionId) return;
    try {
        const res = await fetch(`/chat/context-info?sessionId=${encodeURIComponent(currentSessionId)}`);
        if (!res.ok) return;
        const info = await res.json();

        const percent = info.usagePercent || 0;
        const used = info.totalTokens || 0;
        const max = info.maxTokens || 128000;

        // 格式化数字（k 单位）
        const formatK = (n) => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : n;

        // 更新进度条宽度与颜色分级
        contextBarFill.style.width = Math.min(percent, 100) + '%';
        contextBarFill.className = 'context-bar-fill';
        if (percent > 85) {
            contextBarFill.classList.add('danger');
        } else if (percent > 60) {
            contextBarFill.classList.add('warning');
        }

        // 文本：RPG 滑窗策略不产出 synthetic 事件，compactionCount 恒为 0，故不显示「已压缩 N 次」（方案 A）
        contextBarText.textContent = `${formatK(used)} / ${formatK(max)}（${percent.toFixed(1)}%）`;
        contextBar.classList.remove('hidden');
    } catch (e) {
        // 静默失败，不影响游戏主流程
    }
}

// ===== 手动压缩上下文（沿用 RPG 自身 SlidingWindow 策略，与 chat 各自独立）=====
compactBtn.addEventListener('click', async () => {
    // 流式中禁用
    if (abortController) return;
    if (!currentSessionId) return;

    compactBtn.classList.add('loading');
    compactBtn.disabled = true;

    try {
        const response = await fetch(`/rpg/sessions/${encodeURIComponent(currentSessionId)}/compact`, {
            method: 'POST'
        });
        const result = await response.json();

        if (result.archivedCount > 0) {
            showCompactToast(`✅ 已压缩 ${result.archivedCount} 条消息`);
            // 压缩成功后重放历史区并刷新进度条
            clearMessages();
            const replayed = await loadHistory();
            if (!replayed) {
                appendMessage('assistant', '（较早剧情轮次已归档，可继续输入行动。）');
            }
            refreshContextInfo();
        } else {
            showCompactToast(`ℹ️ ${result.summaryPreview}`);
        }
    } catch (e) {
        showCompactToast(`❌ 压缩失败: ${e.message}`);
    } finally {
        compactBtn.classList.remove('loading');
        compactBtn.disabled = false;
    }
});

function showCompactToast(message) {
    const toast = document.createElement('div');
    toast.className = 'compact-toast';
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 5000);
}

// ===== 状态面板更新 =====
async function updateStatusPanel(gameStateId) {
    if (!gameStateId) return;
    try {
        const res = await fetch(`/rpg/game/state?gameStateId=${gameStateId}`);
        const gs = await res.json();
        document.getElementById('statusLocation').textContent = gs.currentLocation || '-';
        document.getElementById('statusTurn').textContent = gs.turnCount || 0;
        // 解析 NPC 状态
        if (gs.npcStates) {
            try {
                const npcStates = JSON.parse(gs.npcStates);
                const npcNames = Object.keys(npcStates);
                document.getElementById('statusNpcs').textContent =
                    npcNames.length > 0 ? npcNames.join(', ') : '-';
            } catch { document.getElementById('statusNpcs').textContent = '-'; }
        }
    } catch (e) { console.error('状态面板更新失败', e); }
}

// ===== 存档/读档 =====
document.getElementById('saveBtn').addEventListener('click', async () => {
    if (!currentGameStateId) return;
    try {
        const res = await fetch('/rpg/game/save', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({gameStateId: currentGameStateId})
        });
        const result = await res.json();
        if (result.success) alert('存档成功');
        else alert('存档失败');
    } catch (e) { alert('存档失败: ' + e.message); }
});

document.getElementById('loadBtn').addEventListener('click', async () => {
    try {
        const res = await fetch('/rpg/workshop/game-states');
        const states = await res.json();
        const list = document.getElementById('saveList');
        list.innerHTML = '';
        states.forEach(gs => {
            const item = document.createElement('div');
            item.className = 'save-item';
            item.innerHTML = `<span>轮次 ${gs.turnCount} | ${gs.currentLocation || '未知'}</span><span>${gs.id.substring(0,8)}</span>`;
            item.addEventListener('click', async () => {
                currentGameStateId = gs.id;
                currentWorldId = gs.worldId || currentWorldId;
                // 复用存档关联的 session 以重放历史；无关联时才新建
                currentSessionId = gs.sessionId || crypto.randomUUID();
                persistIdentity();
                // 更新 session 关联
                await fetch('/rpg/game/load', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({gameStateId: gs.id, sessionId: currentSessionId})
                });
                document.getElementById('loadOverlay').hidden = true;
                switchToGameMode();
                clearMessages();
                // 重放该存档关联 session 的历史；为空（旧存档/空会话）则回退到重新生成开场白
                const replayed = await loadHistory();
                if (replayed) {
                    document.getElementById('inputArea').hidden = false;
                    document.getElementById('gameInput').focus();
                    updateStatusPanel(gs.id);
                } else {
                    await startOpening(gs.id);
                }
            });
            list.appendChild(item);
        });
        document.getElementById('loadOverlay').hidden = false;
    } catch (e) { alert('加载存档列表失败: ' + e.message); }
});

document.getElementById('closeLoadBtn').addEventListener('click', () => {
    document.getElementById('loadOverlay').hidden = true;
});

// ===== 辅助方法 =====
async function refreshCharList() {
    if (!currentWorldId) return;
    try {
        const res = await fetch(`/rpg/workshop/characters?worldId=${currentWorldId}`);
        const chars = await res.json();
        const container = document.getElementById('charListItems');
        container.innerHTML = '';
        chars.forEach(c => {
            const item = document.createElement('div');
            item.className = 'list-item';
            item.innerHTML = `<span>${c.name} (${c.type})</span><span>${c.id.substring(0,8)}</span>`;
            container.appendChild(item);
        });
    } catch (e) { console.error('加载角色列表失败', e); }
}

// ===== 页面初始化：恢复持久化身份并重放历史（支持刷新恢复）=====
(function initRpg() {
    const saved = restoreIdentity();
    if (!saved) return;
    currentSessionId = saved.sessionId;
    currentGameStateId = saved.gameStateId;
    // 自动进入游戏模式并重放历史
    switchToGameMode();
    clearMessages();
    loadHistory().then(replayed => {
        document.getElementById('inputArea').hidden = false;
        if (!replayed) {
            appendMessage('assistant', '（未找到可恢复的对话历史，可继续输入行动或重新读档。）');
        }
        updateStatusPanel(currentGameStateId);
        refreshContextInfo();
    });
})();
