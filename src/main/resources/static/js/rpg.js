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

// 简易 Markdown 渲染
function renderMarkdown(text) {
    return text
        .replace(/^### (.+)$/gm, '<h3>$1</h3>')
        .replace(/^## (.+)$/gm, '<h2>$1</h2>')
        .replace(/^# (.+)$/gm, '<h1>$1</h1>')
        .replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>')
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br>');
}

// state_delta 过滤（客户端二次过滤，后端引擎已过滤一次）
function filterStateDelta(text) {
    return text.replace(/<state_delta>[\s\S]*?<\/state_delta>/gi, '');
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
        // 切换到游戏模式
        switchToGameMode();
        // 启动开场白
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
    const narrationContent = document.getElementById('narrationContent');
    narrationContent.innerHTML = '';
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
                            // 过滤 state_delta 后渲染
                            const filtered = filterStateDelta(narrationBuffer);
                            narrationContent.innerHTML = renderMarkdown(filtered);
                            document.getElementById('narrationArea').scrollTop =
                                document.getElementById('narrationArea').scrollHeight;
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

gameForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const message = gameInput.value.trim();
    if (!message || !currentGameStateId) return;

    gameInput.value = '';
    gameInput.disabled = true;
    gameSendBtn.hidden = true;
    gameStopBtn.hidden = false;

    abortController = new AbortController();

    // 追加玩家行动到叙述区域
    const narrationContent = document.getElementById('narrationContent');
    narrationContent.innerHTML += `<p><em>你：${message}</em></p><hr>`;
    narrationBuffer = '';

    try {
        const res = await fetch('/rpg/game/turn', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                gameStateId: currentGameStateId,
                sessionId: currentSessionId,
                message
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
                            const filtered = filterStateDelta(narrationBuffer);
                            narrationContent.innerHTML = renderMarkdown(filtered);
                            document.getElementById('narrationArea').scrollTop =
                                document.getElementById('narrationArea').scrollHeight;
                        } else if (parsed.error) {
                            narrationContent.innerHTML += `<p style="color:#e94560">⚠️ ${parsed.error}</p>`;
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
});

gameStopBtn.addEventListener('click', () => {
    if (abortController) abortController.abort();
});

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
                currentSessionId = currentSessionId || crypto.randomUUID();
                // 更新 session 关联
                await fetch('/rpg/game/load', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({gameStateId: gs.id, sessionId: currentSessionId})
                });
                document.getElementById('loadOverlay').hidden = true;
                switchToGameMode();
                await startOpening(gs.id);
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
