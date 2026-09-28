/**
 * 游戏模式：三视图切换（rpg-home-entry）、新开冒险、开场白/回合 SSE 流式、
 * 模型选择器、上下文进度条、手动压缩、toast、状态面板
 * （rpg-js-split 拆分自 rpg.js）
 *
 * 本文件产出的全局符号：
 *   变量：gameForm, gameInput, gameSendBtn, gameStopBtn, modelSelector,
 *         contextBar, contextBarFill, contextBarText, compactBtn,
 *         worldCharCards, cachedCharWorldId
 *   函数：showView, goHome, startOpening, sendTurn,
 *         loadModels, refreshContextInfo, showCompactToast, loadWorldCharCards,
 *         displayNpcKey, updateStatusPanel,
 *         refreshWorldSelects, resetPlayerSelect, refreshPlayerSelect
 *   依赖（运行时）：state.js（全局状态）；markdown.js / messages.js / backtrack.js 的渲染与交互函数；
 *         saves.js（refreshHomeSaves，回首页时刷新存档列表，加载顺序在末文件 init.js 前均已就绪）
 */

// ===== 三视图切换（rpg-home-entry：首页/工坊/游戏，进入游戏仅两条路径）=====
/**
 * 集中控制三个 main 的显隐与顶栏导航按钮，禁止散落赋值（design D1）。
 * @param {'home'|'workshop'|'game'} view 目标视图
 */
function showView(view) {
    isGameMode = (view === 'game');
    document.getElementById('homeMode').hidden = view !== 'home';
    document.getElementById('workshopMode').hidden = view !== 'workshop';
    document.getElementById('gameMode').hidden = view !== 'game';
    // 顶栏导航：首页视图隐藏「回首页」，工坊视图隐藏「进入工坊」；平台主页链接常显
    document.getElementById('homeNavBtn').hidden = view === 'home';
    document.getElementById('workshopNavBtn').hidden = view === 'workshop';
}

/** 回首页：切视图并刷新存档列表与计数 */
async function goHome() {
    showView('home');
    await refreshHomeSaves();
}

document.getElementById('homeNavBtn').addEventListener('click', () => { goHome(); });
document.getElementById('workshopNavBtn').addEventListener('click', () => { showView('workshop'); });

// ===== 新开冒险（rpg-home-entry：从工坊 startOptions 迁入，进入游戏模式的路径②）=====
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

/** 重置玩家角色选择器为占位项 */
function resetPlayerSelect() {
    const sel = document.getElementById('selectPlayer');
    if (sel) sel.innerHTML = '<option value="">选择玩家角色...</option>';
}

/** 实时加载指定世界的角色列表到玩家角色选择器（保留仍存在的选中项） */
async function refreshPlayerSelect(worldId) {
    const sel = document.getElementById('selectPlayer');
    if (!sel || !worldId) return;
    const previous = sel.value;
    try {
        const res = await fetch(`/rpg/workshop/characters?worldId=${worldId}`);
        if (!res.ok) return;
        const chars = await res.json();
        sel.innerHTML = '<option value="">选择玩家角色...</option>';
        chars.forEach(c => {
            const opt = document.createElement('option');
            opt.value = c.id;
            opt.textContent = c.name;
            sel.appendChild(opt);
        });
        // 之前选中的角色仍存在则保留，否则回到占位项
        sel.value = chars.some(c => c.id === previous) ? previous : '';
    } catch (e) { console.error('加载角色列表失败', e); }
}

document.getElementById('selectWorld').addEventListener('change', async (e) => {
    const worldId = e.target.value;
    if (!worldId) return;
    // 不区分玩家/NPC：列出该世界全部角色，选谁谁就是玩家
    await refreshPlayerSelect(worldId);
});

// 展开玩家角色下拉时实时重查列表，而非沿用旧缓存
document.getElementById('selectPlayer').addEventListener('focus', () => {
    const worldId = document.getElementById('selectWorld').value;
    if (worldId) refreshPlayerSelect(worldId);
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
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('启动游戏失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return;
        }
        const gs = await res.json();
        currentGameStateId = gs.id;
        currentWorldId = worldId;
        // 持久化身份（sessionId + gameStateId），仅作“当前在玩存档”标记
        persistIdentity();
        // 切换到游戏模式
        showView('game');
        // 新开局：清空容器后展示开场白
        clearMessages();
        await startOpening(gs.id);
    } catch (e) { alert('启动游戏失败: ' + e.message); }
});

// ===== 开场白 SSE 流式 =====
async function startOpening(gameStateId) {
    document.getElementById('inputArea').hidden = true;
    // 开场白作为独立块置顶（不套 GM 卡片），流式只更新该块
    const openingEl = appendOpening();
    narrationBuffer = '';
    showGenerating(openingEl);

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
        settlePlaceholder(openingEl, '（开场白加载失败）', true);
    }
    // 流正常结束但未收到任何内容时也要清掉占位
    settlePlaceholder(openingEl, '（开场白无内容）');

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
    await sendTurn(message);
});

/**
 * 发送一个游戏回合（SSE 流式）。
 * submit 与「↻ 重新生成」共用：后者在 backtrack 后以玩家原文自动重调。
 * @param {string} message 玩家行动文本
 */
async function sendTurn(message) {
    if (abortController) return;
    if (!message || !currentGameStateId) return;

    // 新回合开始：旧轮的重新生成按钮即刻失效（尾包落库后重新挂到新 GM 气泡上）
    document.querySelectorAll('#messagesContainer .regenerate-btn').forEach(b => b.remove());
    // 流式期间禁用全部回溯/重新生成按钮，避免与进行中的回合竞态
    setBacktrackButtonsEnabled(false);

    gameInput.disabled = true;
    gameSendBtn.hidden = true;
    gameStopBtn.hidden = false;

    abortController = new AbortController();

    // 本局进行中的轮次（近似值 = 最近已知轮次 + 1；落库后由状态面板/尾包对账校正）
    const roundNo = lastKnownTurn + 1;

    // 追加玩家气泡（右、红底白字）；随后追加一个空 GM 卡片承接流式叙述（不再插入 <hr>）
    const userBubble = appendMessage('user', message);
    const gmBubble = appendMessage('assistant', '');
    // appendMessage 返回内容元素，回溯元数据挂在 wrap 上（bubble.parentElement）
    liveUserWrap = userBubble ? userBubble.parentElement : null;
    liveGmWrap = gmBubble ? gmBubble.parentElement : null;
    narrationBuffer = '';
    showGenerating(gmBubble);

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
                        } else if (parsed.tool) {
                            // GM 工具进度事件（与主聊天同协议）：叙述前后的静默期
                            // （如模型连续写记忆文件）在气泡上有可见反馈，避免“输出完了还在执行”的体感
                            if (parsed.tool === 'start') showToolStatus(gmBubble, parsed.name);
                        } else if (parsed.type === 'reset') {
                            // 降级重跑（stream-fallback-duplicate-output）：服务端已放弃本回合已发的
                            // 半截叙述，改为非流式重跑。前端清空叙述缓冲与当前 GM 气泡并恢复
                            // 生成中占位，随后重发的完整内容将从零重新填充（幂等：多次 reset 等价一次）。
                            // 仅重置本回合 live 气泡，不触碰历史气泡 DOM 与回溯元数据。
                            narrationBuffer = '';
                            if (gmBubble) {
                                gmBubble.innerHTML = GENERATING_HTML;
                                scrollNarrationToBottom();
                            }
                        } else if (parsed.type === 'turn_persisted') {
                            // 尾包：回合已落库，把事件 ID 补挂到本局 live 气泡并渲染操作按钮
                            applyTurnPersisted(parsed, roundNo);
                        } else if (parsed.error) {
                            if (gmBubble) {
                                if (narrationBuffer) {
                                    gmBubble.innerHTML += `<p class="rpg-error">⚠️ ${parsed.error}</p>`;
                                } else {
                                    // 尚无叙述内容：错误行替换占位，而不是拼在「生成中」后面
                                    settlePlaceholder(gmBubble, '⚠️ ' + parsed.error, true);
                                }
                            }
                        }
                    } catch (e) { /* ignore */ }
                }
            }
        }
    } catch (e) {
        if (e.name === 'AbortError') {
            console.log('流式已中止');
            settlePlaceholder(gmBubble, '（已中止）');
        } else {
            console.error('回合请求失败', e);
            settlePlaceholder(gmBubble, '⚠️ 回合请求失败: ' + e.message, true);
        }
    }
    // 叙述完成后若最后一个动作是工具调用（如整理记忆），状态行不会被后续内容事件
    // 覆盖，回合收尾时统一清除；无叙述时由 settlePlaceholder 兜底替换占位
    if (narrationBuffer && gmBubble) {
        gmBubble.querySelectorAll('.rpg-tool-status').forEach(el => el.remove());
    }
    // 流正常结束但未收到任何内容时也要清掉占位
    settlePlaceholder(gmBubble, '（本轮无叙述）');

    gameInput.disabled = false;
    gameSendBtn.hidden = false;
    gameStopBtn.hidden = true;
    abortController = null;
    setBacktrackButtonsEnabled(true);
    gameInput.focus();
    updateStatusPanel(currentGameStateId);
    refreshContextInfo();
}

gameStopBtn.addEventListener('click', () => {
    if (!abortController) return;
    // ① 先断本地流：气泡立即进（已中止）终态，输入区恢复
    abortController.abort();
    // ② 再通知服务端真停止（rpg-server-side-abort，design D4）：fire-and-forget、
    //    keepalive 覆盖停止后立刻关页场景；失败静默——退化为服务端自然收尾（不劣于改动前）
    if (currentGameStateId) {
        fetch('/rpg/game/abort', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({gameStateId: currentGameStateId}),
            keepalive: true
        }).catch(() => { /* 静默：服务端收尾竞态由守卫 doFinally 兑底 */ });
    }
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
// npcStates 的 key 在迁移后是角色卡 ID，需经世界角色卡列表解析为卡名再显示。
// 缓存按 worldId 作键：刷新恢复路径不设 currentWorldId，故 worldId 从存档接口拿。
let worldCharCards = {};
let cachedCharWorldId = null;

async function loadWorldCharCards(worldId) {
    try {
        const res = await fetch(`/rpg/workshop/characters?worldId=${encodeURIComponent(worldId)}`);
        if (!res.ok) return;
        const chars = await res.json();
        worldCharCards = {};
        chars.forEach(c => { worldCharCards[c.id] = c.name; });
        cachedCharWorldId = worldId;
    } catch (e) {
        console.warn('角色卡缓存加载失败', e);
    }
}

/** key 命中角色卡 ID 时显示卡名，未命中原样显示（兼容未迁移存档） */
function displayNpcKey(key) {
    return worldCharCards[key] || key;
}

async function updateStatusPanel(gameStateId) {
    if (!gameStateId) return;
    try {
        const res = await fetch(`/rpg/game/state?gameStateId=${gameStateId}`);
        const gs = await res.json();
        if (gs.worldId && gs.worldId !== cachedCharWorldId) {
            await loadWorldCharCards(gs.worldId);
        }
        document.getElementById('statusLocation').textContent = gs.currentLocation || '-';
        document.getElementById('statusTurn').textContent = gs.turnCount || 0;
        // 状态面板是轮次真源（含中止轮），同步到回溯置灰逻辑
        if (typeof gs.turnCount === 'number' && gs.turnCount > 0) {
            lastKnownTurn = gs.turnCount;
            refreshBacktrackButtonStates();
        }
        // 解析 NPC 状态（rpg-scene-cast）：一次解析双用途——
        // NPC 行只显示 in_scene===true 的当前场景班底（带 status 后缀），
        // 折叠区「已结识 (N)」展示全量花名册；旧存档无戳 → 在场空集 → '-'。
        if (gs.npcStates) {
            try {
                const npcStates = JSON.parse(gs.npcStates);
                const entries = Object.entries(npcStates)
                    .filter(([, v]) => v && typeof v === 'object');
                const sceneNames = entries
                    .filter(([, v]) => v.in_scene === true)
                    .map(([key, v]) => {
                        const name = displayNpcKey(key);
                        return typeof v.status === 'string' && v.status ? `${name}(${v.status})` : name;
                    });
                document.getElementById('statusNpcs').textContent =
                    sceneNames.length > 0 ? sceneNames.join(', ') : '-';
                const roster = document.getElementById('statusRoster');
                if (entries.length > 0) {
                    roster.hidden = false;
                    document.getElementById('statusRosterSummary').textContent = `已结识 (${entries.length})`;
                    document.getElementById('statusRosterList').textContent =
                        entries.map(([key]) => displayNpcKey(key)).join('、');
                } else {
                    roster.hidden = true;
                }
            } catch {
                document.getElementById('statusNpcs').textContent = '-';
                document.getElementById('statusRoster').hidden = true;
            }
        } else {
            document.getElementById('statusNpcs').textContent = '-';
            document.getElementById('statusRoster').hidden = true;
        }
        // 解析 PC 结构化状态（player_states，缺失/非法 JSON 均降级为空态）
        renderPlayerStates(gs.playerStates);
    } catch (e) { console.error('状态面板更新失败', e); }
}

/**
 * 渲染 PC 结构化状态（金钱/称号/能力）到状态面板玩家区块。
 * <p>
 * 容错：playerStates 为 null/undefined/非法 JSON 时三个字段均显示 '-'；
 * abilities 为对象时只显示键列表（值留给叙事与 GmMemory 工具）。
 */
function renderPlayerStates(playerStatesJson) {
    const moneyEl = document.getElementById('statusMoney');
    const titlesEl = document.getElementById('statusTitles');
    const abilitiesEl = document.getElementById('statusAbilities');
    let ps = null;
    if (playerStatesJson) {
        try { ps = JSON.parse(playerStatesJson); } catch { ps = null; }
    }
    moneyEl.textContent = (ps && typeof ps.money === 'number') ? ps.money : '-';
    titlesEl.textContent = (ps && Array.isArray(ps.titles) && ps.titles.length > 0)
        ? ps.titles.join(', ') : '-';
    abilitiesEl.textContent = (ps && ps.abilities && typeof ps.abilities === 'object'
        && !Array.isArray(ps.abilities))
        ? (Object.keys(ps.abilities).join(', ') || '-') : '-';
}
