/**
 * 游戏模式：三视图切换（works-view-and-world-publish D7：works / preset / game）、
 * 作品卡 chips 开局（world-card-gallery）、开场白/回合 SSE 流式、模型选择器、
 * 上下文进度条、手动压缩、toast、状态面板（rpg-js-split 拆分自 rpg.js）
 *
 * 本文件产出的全局符号：
 *   变量：gameForm, gameInput, gameSendBtn, gameStopBtn, modelSelector,
 *         contextBar, contextBarFill, contextBarText, compactBtn,
 *         worldCharCards, cachedCharWorldId
 *   函数：showView, goWorks, startOpening, sendTurn,
 *         loadModels, refreshContextInfo, showCompactToast, loadWorldCharCards,
 *         displayNpcKey, updateStatusPanel,
 *         expandWorldCardChips, renderCharChips（通用角色 chips 渲染，
 *         works-playtest-entry D2：自 expandWorldCardChips 抽出，编辑视图
 *         「▶ 试玩」选角复用）, startGameWith,
 *         openSandboxSourceDialog, closeSandboxSourceDialog, renderSandboxSources,
 *         loadSandboxStorySelect（开局故事图下拉，story-graph 4.10），
 *         openStoryForkDialog / closeStoryForkDialog / renderStoryForks /
 *         handleForkClick / requestStoryBacktrack（图上回溯弹窗，story-graph 4.10）
 *   依赖（运行时）：state.js（全局状态）；gallery.js（fetchWorldCards 数据拉取）；
 *         workshop.js（renderWorks/enterWorksSelect/enterWorksEdit：先加载的文件定义，
 *         此处运行时调用无碍）；markdown.js / messages.js / backtrack.js 的渲染与
 *         交互函数；hud.js（attachHudComponents / invalidateHudChoices，尾包富组件）；
 *         lorebook.js（fetchLorebookSources / renderLorebookSourceList /
 *         lorebookHintNode，沙盒开局「资料来源」弹窗共用）
 *         （存档网格已迁出，goWorks 不刷存档列表，rpg-conversation-list）
 */

// ===== 三视图切换（works-view-and-world-publish D7：works / preset / game）=====
/**
 * 集中控制三个 main 的显隐与顶栏导航高亮，禁止散落赋值。
 * @param {'works'|'game'|'preset'} view 目标视图
 */
function showView(view) {
    isGameMode = (view === 'game');
    document.getElementById('worksMode').hidden = view !== 'works';
    document.getElementById('gameMode').hidden = view !== 'game';
    document.getElementById('presetMode').hidden = view !== 'preset';
    // 作品两级视图：进作品默认落「作品选择」首屏（enterWorksSelect 由先加载的
    // workshop.js 定义，此处运行时调用无碍）
    if (view === 'works') enterWorksSelect();
    // 顶栏高亮联动（unified-global-topbar D3）：/rpg 页三视图均属「🎨 创建作品」
    // 入口管辖，单入口恒亮（game/preset 也由该入口进入）
    const navRpg = document.getElementById('navRpgLink');
    if (navRpg) navRpg.classList.add('active');
}

/** 回作品墙：切视图并刷新作品卡（存档列表已迁至 📜 存档 页，rpg-conversation-list） */
async function goWorks() {
    showView('works');
    // showView 内 enterWorksSelect 已 fire 一刷，此处幂等重刷并等待完成：
    // ?world= 深链展开依赖卡已渲染（见 init.js）
    await renderWorks();
}

// 同页拦截（unified-global-topbar D2）：人在 /rpg 时点顶栏 🎨 无刷新回作品墙并清 view 参数；
// 跨页不触发（整页跳转走深链），拦截异常时默认跳转仍由浏览器兑底
const navWorksLink = document.getElementById('navRpgLink');
if (navWorksLink) {
    navWorksLink.addEventListener('click', (ev) => {
        ev.preventDefault();
        goWorks();
        const url = new URL(location.href);
        url.searchParams.delete('view');
        history.replaceState(null, '', url);
    });
}


/**
 * 展开/收起作品卡内的角色 chips（?world= 深链与卡上「▶」悬浮钮都走这里）。
 * chips 点选角色后走既有 /rpg/workshop/start 开局。
 * @return 找到对应卡片返回 true（深链落地校验用）
 */
async function expandWorldCardChips(worldId) {
    const wall = document.getElementById('worksWorldCards');
    const target = wall && wall.querySelector(`.world-card[data-id="${CSS.escape(worldId)}"]`);
    if (!target) return false;
    const chips = target.querySelector('.world-card-chips');
    // 再点已展开的卡 = 收起
    if (!chips.hidden && target.classList.contains('chips-loaded')) {
        chips.hidden = true;
        target.classList.remove('expanded');
        return true;
    }
    // 同墙互斥：先收起其他展开卡
    wall.querySelectorAll('.world-card.expanded').forEach(c => {
        c.classList.remove('expanded');
        const box = c.querySelector('.world-card-chips');
        if (box) box.hidden = true;
    });
    chips.hidden = false;
    target.classList.add('expanded');
    // 渲染委托通用 renderCharChips（works-playtest-entry D2 抽出，编辑视图试玩复用）：
    // 空态引导与点卡同语义——进编辑视图并载入该世界（worldSelect 联动全部子 tab）
    const ok = await renderCharChips(chips, worldId, openSandboxSourceDialog, {
        onEmpty: () => {
            enterWorksEdit();
            const sel = document.getElementById('worldSelect');
            if (sel && sel.value !== worldId) {
                sel.value = worldId;
                sel.dispatchEvent(new Event('change'));
            }
        }
    });
    // 成功才标记 chips-loaded（失败时再点「▶」重试，与原行为一致）
    if (ok) target.classList.add('chips-loaded');
    return true;
}

/**
 * 通用角色 chips 渲染（works-playtest-entry D2：自 expandWorldCardChips 抽出，
 * 编辑视图「▶ 试玩」选角复用同一 chips 视觉与交互语言）。
 * @param container chips 容器（调用方负责显隐，本函数只填充内容）
 * @param worldId 世界 id
 * @param onPick 点选角色回调 onPick(worldId, charId)——作品墙传 openSandboxSourceDialog
 *        （先选资料来源/故事图）；编辑视图试玩传 startGameWith 直开（design D2）
 * @param opts {emptyText?, onEmpty?}：空角色引导文案与点击回调（作品墙=进编辑视图
 *        载入该世界；编辑视图=切角色卡 tab）
 * @return 渲染成功返回 true；请求异常返回 false（chips-loaded 标记由调用方决定）
 */
async function renderCharChips(container, worldId, onPick, opts) {
    container.innerHTML = '<span class="world-chip loading">加载角色…</span>';
    try {
        const res = await fetch(`/rpg/workshop/characters?worldId=${encodeURIComponent(worldId)}`);
        const chars = res.ok ? await res.json() : [];
        container.innerHTML = '';
        if (!chars.length) {
            const empty = document.createElement('span');
            empty.className = 'world-chip empty';
            empty.textContent = (opts && opts.emptyText) || '暂无角色，点我去补角色';
            empty.addEventListener('click', ev => {
                ev.stopPropagation();
                if (opts && opts.onEmpty) opts.onEmpty();
            });
            container.appendChild(empty);
        } else {
            // 不区分玩家/NPC：列出该世界全部角色，选谁谁就是玩家（同原下拉语义）
            chars.forEach(c => {
                const chip = document.createElement('button');
                chip.type = 'button';
                chip.className = 'world-chip';
                chip.textContent = c.name;
                chip.addEventListener('click', ev => {
                    ev.stopPropagation();
                    onPick(worldId, c.id);
                });
                container.appendChild(chip);
            });
        }
        return true;
    } catch (e) {
        container.innerHTML = '<span class="world-chip empty">角色加载失败，请重试</span>';
        return false;
    }
}

/** 开局流程（原 confirmStartBtn 监听体抽出）：创建 GameState → 游戏模式 → 开场白；inheritSetId 为资料来源（缺省=世界默认）；storyGraphId 为挂图开局（null=自由沙帛，story-graph 4.10，后端 bootstrap 物化入口 on_enter） */
async function startGameWith(worldId, playerCharId, inheritSetId, storyGraphId) {
    currentSessionId = crypto.randomUUID();
    try {
        const res = await fetch('/rpg/workshop/start', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({worldId, playerCharId, sessionId: currentSessionId,
                inheritSetId: inheritSetId || null, storyGraphId: storyGraphId || null})
        });
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            alert('启动游戏失败: ' + ((body && body.error) || ('HTTP ' + res.status)));
            return;
        }
        const gs = await res.json();
        currentGameStateId = gs.id;
        currentWorldId = worldId;
        // 作者主题注入（rpg-hud-bubble-components 4.2）：按世界卡应用，失败走默认；
        // fire-and-forget 不阻断开局链（首轮组件在 turn_persisted 后才出现，时序充裕）
        applyWorldThemeById(worldId);
        // 持久化身份（sessionId + gameStateId），仅作“当前在玩存档”标记
        persistIdentity();
        // 切换到游戏模式
        showView('game');
        // 新开局：清空容器后展示开场白
        clearMessages();
        await startOpening(gs.id);
    } catch (e) { alert('启动游戏失败: ' + e.message); }
}

// ===== 沙盒开局「资料来源」选择弹窗（add-rpg-lorebook-sets D4/D10）=====
// 点角色 chip 不再直接开局，先选资料来源（世界默认 / 继承本世界任一 set，含孤儿）。
let sandboxPendingStart = null;   // {worldId, playerCharId}
let sandboxInheritSetId = null;   // 选中来源 set id（null=世界默认）
let sandboxStoryGraphId = null;   // 选中故事图 id（null=不挂图·自由沙帛，story-graph 4.10）
let sandboxSources = [];

document.getElementById('sandboxSourceStartBtn').addEventListener('click', () => {
    document.getElementById('sandboxSourceOverlay').hidden = true;
    if (sandboxPendingStart) {
        startGameWith(sandboxPendingStart.worldId, sandboxPendingStart.playerCharId,
                sandboxInheritSetId, sandboxStoryGraphId);
    }
    sandboxPendingStart = null;
});
document.getElementById('sandboxSourceCancelBtn').addEventListener('click', closeSandboxSourceDialog);
document.getElementById('closeSandboxSourceBtn').addEventListener('click', closeSandboxSourceDialog);
document.getElementById('sandboxStorySelect').addEventListener('change', e => {
    sandboxStoryGraphId = e.target.value || null;   // story-graph 4.10：选中图 id，空串归 null（不挂图）
});

function closeSandboxSourceDialog() {
    document.getElementById('sandboxSourceOverlay').hidden = true;
    sandboxPendingStart = null;
}

async function openSandboxSourceDialog(worldId, playerCharId) {
    sandboxPendingStart = {worldId, playerCharId};
    sandboxInheritSetId = null;
    sandboxStoryGraphId = null;
    document.getElementById('sandboxSourceOverlay').hidden = false;
    const container = document.getElementById('sandboxSourceList');
    container.replaceChildren(lorebookHintNode('加载中…'));
    // 故事图下拉（story-graph 4.10）：拉 world 图清单填充（与来源列表并行，fire-and-forget 不阻断）
    loadSandboxStorySelect(worldId);
    sandboxSources = await fetchLorebookSources(worldId);
    renderSandboxSources();
}

/** 填充开局故事图下拉（story-graph 4.10）：全部图可选；未整稿图开局时后端 400 明确提示 */
async function loadSandboxStorySelect(worldId) {
    const sel = document.getElementById('sandboxStorySelect');
    if (!sel) return;
    sel.innerHTML = '<option value="">（不挂图 · 自由沙帛）</option>';
    sandboxStoryGraphId = null;
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/worlds/${encodeURIComponent(worldId)}/story-graphs`);
        if (!res.ok) return;
        (await res.json()).forEach(g => {
            const opt = document.createElement('option');
            opt.value = g.id;
            opt.textContent = g.name || '(未命名)';
            sel.appendChild(opt);
        });
    } catch (e) { console.warn('故事图清单加载失败:', e); }
}

function renderSandboxSources() {
    renderLorebookSourceList(document.getElementById('sandboxSourceList'), sandboxSources,
        sandboxInheritSetId,
        setId => { sandboxInheritSetId = setId; renderSandboxSources(); },
        async () => {
            if (!sandboxPendingStart) return;
            sandboxSources = await fetchLorebookSources(sandboxPendingStart.worldId);
            renderSandboxSources();
        });
}

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
 * 拉取当前节点的图选项视图（story-graph 4.5）：尾包时 currentNode 已推进到
 * 本轮到达节点，出边即本节点选项（本地快查询）。未挂图 → 后端空视图
 * （edges 空且 improviseAllowed=true，选项区零增量）；请求失败 → null 同样零增量。
 */
async function fetchStoryOptions() {
    if (!currentGameStateId) return null;
    try {
        const res = await fetch(`/rpg/game/story-options?gameStateId=${encodeURIComponent(currentGameStateId)}`);
        if (!res.ok) return null;
        return await res.json();
    } catch (e) {
        console.warn('图选项加载失败:', e);
        return null;
    }
}

// ===== 图上回溯：分叉点列表弹窗（story-graph 4.10，spec「图上回溯与确定性重放」）=====
// 回退到任意已走分叉点：后端确定性重放恢复 GameState（不动会话消息——对话记录
// 保留）；成功后刷新状态面板 + 按当前节点重建图边按钮组（旧选项全部失效置灰）。
document.getElementById('storyForkBtn').addEventListener('click', openStoryForkDialog);
document.getElementById('closeStoryForkBtn').addEventListener('click', closeStoryForkDialog);
document.getElementById('storyForkCancelBtn').addEventListener('click', closeStoryForkDialog);

function closeStoryForkDialog() {
    document.getElementById('storyForkOverlay').hidden = true;
}

/** 打开弹窗并拉取分叉点列表（未挂图 → 空列表提示，零增量） */
async function openStoryForkDialog() {
    if (!currentGameStateId || abortController) return;
    document.getElementById('storyForkOverlay').hidden = false;
    const list = document.getElementById('storyForkList');
    list.replaceChildren(lorebookHintNode('加载中…'));
    let forks = [];
    try {
        const res = await fetch(`/rpg/game/story-forks?gameStateId=${encodeURIComponent(currentGameStateId)}`);
        if (res.ok) forks = await res.json();
    } catch (e) { console.warn('分叉点加载失败:', e); }
    renderStoryForks(Array.isArray(forks) ? forks : []);
}

/** 分叉点列表渲染（复用资料来源列表项结构：序号 + 拍子 + 来路 + 类型徽标） */
function renderStoryForks(forks) {
    const list = document.getElementById('storyForkList');
    list.replaceChildren();
    if (forks.length === 0) {
        list.appendChild(lorebookHintNode('本存档未挂故事图或尚未走图，暂无回退点。'));
        return;
    }
    forks.forEach((f, i) => {
        const item = document.createElement('div');
        item.className = 'lorebook-source-item fork-item';
        item.tabIndex = 0;

        const marker = document.createElement('span');
        marker.className = 'lorebook-source-marker';
        marker.textContent = String(i + 1);
        item.appendChild(marker);

        const body = document.createElement('div');
        body.className = 'lorebook-source-body';
        const name = document.createElement('div');
        name.className = 'lorebook-source-name';
        name.textContent = f.beat || f.nodeId || '（未命名拍子）';
        body.appendChild(name);
        const meta = document.createElement('div');
        meta.className = 'lorebook-source-meta';
        meta.textContent = f.arrivedViaLabel ? `来路：${f.arrivedViaLabel}` : '开局入口';
        body.appendChild(meta);
        item.appendChild(body);

        item.appendChild(forkTypeBadge(f.nodeType));

        item.addEventListener('click', () => handleForkClick(f, item));
        item.addEventListener('keydown', e => {
            if (e.key === 'Enter') handleForkClick(f, item);
        });
        list.appendChild(item);
    });
}

/** 节点类型徽标（scene 场景 / anchor 锚点 / ending 结局） */
function forkTypeBadge(nodeType) {
    const badge = document.createElement('span');
    const t = nodeType || 'scene';
    badge.className = 'fork-type-badge ' + t;
    badge.textContent = t === 'anchor' ? '锚点' : (t === 'ending' ? '结局' : '场景');
    return badge;
}

/** 点击分叉点：confirm → 回退 → 刷新（状态面板 + 图边按钮组重建） */
async function handleForkClick(f, item) {
    if (abortController) return;
    const label = f.beat || f.nodeId;
    if (!confirm(`回退到「${label}」？\n\n状态将按「初始态 + 保留已走边」确定性重放，之后的图选择不再生效；对话记录保留。`)) return;
    item.classList.add('selected');
    const result = await requestStoryBacktrack(f.nodeId);
    if (!result) { item.classList.remove('selected'); return; }
    closeStoryForkDialog();
    showCompactToast(`✅ 已回退到「${label}」（状态已确定性重放）`);
    // 刷新：状态面板（重放态）+ 选项区（旧图边/即兴全部失效，按当前节点重建图边）
    updateStatusPanel(currentGameStateId);
    refreshContextInfo();
    const storyOpts = await fetchStoryOptions();
    refreshGraphChoicesAfterBacktrack(storyOpts, sendTurn);
}

/** 调 story-backtrack 端点；成功返回响应体，失败 toast 并返回 null */
async function requestStoryBacktrack(nodeId) {
    try {
        const res = await fetch('/rpg/game/story-backtrack', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({gameStateId: currentGameStateId, nodeId})
        });
        const body = await res.json().catch(() => null);
        if (!res.ok || !body || !body.success) {
            showCompactToast(`❌ 回退失败：${(body && body.error) || ('HTTP ' + res.status)}`);
            return null;
        }
        return body;
    } catch (e) {
        showCompactToast(`❌ 回退失败：${e.message}`);
        return null;
    }
}

/**
 * 发送一个游戏回合（SSE 流式）。
 * submit 与「↻ 重新生成」共用：后者在 backtrack 后以玩家原文自动重调。
 * @param {string} message 玩家行动文本（图边按钮发起时为边 quote）
 * @param {string} [edgeId] 图边 ID（story-graph 4.5）：非空表示本回合由图边
 *        按钮发起，后端先确定性结算选边（守卫复验/效果/推进运行副本）再组装 prompt
 */
async function sendTurn(message, edgeId) {
    if (abortController) return;
    if (!message || !currentGameStateId) return;

    // 新回合开始：旧轮的重新生成按钮即刻失效（尾包落库后重新挂到新 GM 气泡上）；
    // 历史气泡上的 HUD 选项全量置灰（rpg-hud-bubble-components D11，与 regenerate 清理同位置同模式）
    document.querySelectorAll('#messagesContainer .regenerate-btn').forEach(b => b.remove());
    invalidateHudChoices();
    // 流式期间禁用全部回溯/重新生成按钮与选项，避免与进行中的回合竞态
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
                model: modelSelector ? modelSelector.value : '',
                edgeId: edgeId || ''
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
                            // 尾包：回合已落库，把事件 ID 补挂到本局 live 气泡并渲染操作按钮；
                            // state 携带本轮修复后 state_delta（与 EventLog 快照同源，D3），
                            // 到达时组装 GM 气泡富组件并淡入（D5：叙述流式 → 组件后到）。
                            // 组件先行挂载（插在 actions 行之前），随后 applyTurnPersisted
                            // 挂回溯/重新生成行保持最尾。无 state（中止对账/漏 delta）→ 纯叙述（D8）。
                            if (parsed.state && liveGmWrap) {
                                // 图选项混排（story-graph 4.5）：尾包后 currentNode 已是
                                // 本轮到达节点，此刻出边即本节点选项（本地快查询，
                                // await 可接受——尾包之后只剩 [DONE]）；未挂图/失败 →
                                // 空视图零增量（不渲染图边、不过滤 GM choices）
                                const storyOpts = await fetchStoryOptions();
                                attachHudComponents(liveGmWrap, parsed.state, {
                                    live: true,
                                    onChoice: sendTurn,   // 点击选项 = 以 quote 发起新回合（D11）；图边按钮额外携带 edgeId
                                    graphEdges: storyOpts ? storyOpts.edges : null,
                                    improviseAllowed: !storyOpts || storyOpts.improviseAllowed !== false
                                });
                                scrollNarrationToBottom();
                            }
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

// status 汉化映射表（rpg-scene-cast D9）：prompt 枚举 → 中文标签；表外值（GM 自由文本，如「交谈中」）原样透传
const NPC_STATUS_LABELS = {
    idle: '旁观',
    confronting: '对峙',
    fleeing: '逃窜',
    dead: '死亡'
};

/** status 值 → 中文标签：键 trim 后大小写不敏感匹配，表外返回原值 */
function displayNpcStatus(status) {
    return NPC_STATUS_LABELS[status.trim().toLowerCase()] || status;
}

// ===== 小宇宙感阶分档（rpg-cosmo-sense）=====
// 真源：后端 CosmoSense 枚举（0=不具备第六感 / 1-300 第六感 / 301-700 第七感 /
// 701-1200 第八感 / 1201+ 第九感）。定义在 game.js（早于 preset.js 加载），
// 供状态面板与步骤②结果卡（preset.js renderCosmoRow）共用同一份渲染，避免多处漂移。
// ⚠️ 后端调档时这里会静默漂移，故 cosmoRender 对非数值一律兜底显原值（不白屏）。
const COSMO_SENSE_TIERS = [
    {max: 0, label: '不具备第六感'},
    {max: 300, label: '第六感'},
    {max: 700, label: '第七感'},
    {max: 1200, label: '第八感'},
    {max: Infinity, label: '第九感'}
];

/** 感阶措辞：按分档表取标签，负值夹取到 0 */
function cosmoSenseLabel(value) {
    const n = Math.max(0, Math.floor(value));
    for (const tier of COSMO_SENSE_TIERS) {
        if (n <= tier.max) return tier.label;
    }
    return COSMO_SENSE_TIERS[COSMO_SENSE_TIERS.length - 1].label;
}

/** 渲染「N（X感）」，与后端 CosmoSense.render 对齐；0 特例不带数值；非数值兜底显原值 */
function cosmoRender(value) {
    if (value === null || value === undefined || value === '') return '-';
    if (isNaN(value)) return String(value);
    const n = Math.max(0, Math.floor(Number(value)));
    if (n === 0) return cosmoSenseLabel(0);
    return `${n}（${cosmoSenseLabel(n)}）`;
}

// ===== 招式熟练度分档（rpg-technique-tree-data）=====
// 真源：后端 TechniqueProficiency 枚举（0 未学会 / 1-199 初窥门径 / 200-399 入门 /
// 400-599 小成 / 600-799 大成 / 800-999 圆满 / 1000 究极奥义）。定义在 game.js
// （早于 preset.js 加载），供状态面板招式区块与步骤②招式树共用同一份渲染，避免多处漂移。
const TECH_PROFICIENCY_TIERS = [
    {max: 0, label: '未学会'},
    {max: 199, label: '初窥门径'},
    {max: 399, label: '入门'},
    {max: 599, label: '小成'},
    {max: 799, label: '大成'},
    {max: 999, label: '圆满'},
    {max: Infinity, label: '究极奥义'}
];

function techProfLabel(value) {
    const n = Math.max(0, Math.floor(Number(value) || 0));
    for (const tier of TECH_PROFICIENCY_TIERS) {
        if (n <= tier.max) return tier.label;
    }
    return TECH_PROFICIENCY_TIERS[TECH_PROFICIENCY_TIERS.length - 1].label;
}

/** 渲染「数值/档位」（如「620/大成」），与后端 TechniqueProficiency.render 对齐；0 特例直接显「未学会」 */
function techProfRender(value) {
    const n = Math.max(0, Math.floor(Number(value) || 0));
    if (n === 0) return techProfLabel(0);
    return `${n}/${techProfLabel(n)}`;
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
        // NPC 行只显示 in_scene===true 的当前场景班底（带中文状态标签后缀，见 displayNpcStatus），
        // 折叠区「已结识 (N)」展示全量花名册；两处均逐行竖排（\n 分隔 + CSS
        // pre-line 渲染，见 .status-item-npc）；旧存档无戳 → 在场空集 → '-'。
        if (gs.npcStates) {
            try {
                const npcStates = JSON.parse(gs.npcStates);
                const entries = Object.entries(npcStates)
                    .filter(([, v]) => v && typeof v === 'object');
                const sceneNames = entries
                    .filter(([, v]) => v.in_scene === true)
                    .map(([key, v]) => {
                        const name = displayNpcKey(key);
                        return typeof v.status === 'string' && v.status ? `${name}（${displayNpcStatus(v.status)}）` : name;
                    });
                document.getElementById('statusNpcs').textContent =
                    sceneNames.length > 0 ? sceneNames.join('\n') : '-';
                const roster = document.getElementById('statusRoster');
                if (entries.length > 0) {
                    roster.hidden = false;
                    document.getElementById('statusRosterSummary').textContent = `已结识 (${entries.length})`;
                    document.getElementById('statusRosterList').textContent =
                        entries.map(([key]) => displayNpcKey(key)).join('\n');
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
    } catch (e) { console.error('状态面板更新失败', e); }
}
