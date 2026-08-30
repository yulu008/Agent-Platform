/**
 * Agent Platform 聊天界面交互逻辑
 * 集成 SSE 流式对话、多会话管理、消息历史加载、抽屉交互
 */

// ===== sessionId 管理 =====
const SESSION_KEY = 'agentPlatformSessionId';

function getSessionId() {
    let id = localStorage.getItem(SESSION_KEY);
    if (!id) {
        id = crypto.randomUUID();
        localStorage.setItem(SESSION_KEY, id);
    }
    return id;
}

// 当前活跃会话 ID（可切换）
let currentSessionId = window.__SESSION_ID__ || getSessionId();

function setCurrentSession(id) {
    currentSessionId = id;
    localStorage.setItem(SESSION_KEY, id);
}

// ===== AbortController 与按钮状态机 =====
let abortController = null;

const SEND_ICON = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>`;
const STOP_ICON = `<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"></rect></svg>`;

function enterStreamingState() {
    abortController = new AbortController();
    sendBtn.classList.add('streaming');
    sendBtn.innerHTML = STOP_ICON;
    sendBtn.disabled = false;
    sendBtn.onclick = () => abortController.abort();
}

function exitStreamingState() {
    sendBtn.classList.remove('streaming');
    sendBtn.innerHTML = SEND_ICON;
    sendBtn.disabled = false;
    sendBtn.onclick = null;
    abortController = null;
}

// ===== DOM 元素 =====
const messagesContainer = document.getElementById('messagesContainer');
const chatInput = document.getElementById('chatInput');
const sendBtn = document.getElementById('sendBtn');
const chatForm = document.getElementById('chatForm');
const modelSelector = document.getElementById('modelSelector');
const imagePreview = document.getElementById('imagePreview');
const imagePreviewImg = document.getElementById('imagePreviewImg');
const removeImageBtn = document.getElementById('removeImageBtn');

// 当前待发送的图片（Base64 DataURL）
let currentImageBase64 = null;
const drawerToggle = document.getElementById('drawerToggle');
const drawerClose = document.getElementById('drawerClose');
const sessionDrawer = document.getElementById('sessionDrawer');
const drawerOverlay = document.getElementById('drawerOverlay');
const sessionList = document.getElementById('sessionList');
const newSessionBtn = document.getElementById('newSessionBtn');
const clearSessionBtn = document.getElementById('clearSessionBtn');
const compactBtn = document.getElementById('compactBtn');
const commandPalette = document.getElementById('commandPalette');

// ===== "/" 命令面板状态 =====
let paletteOpen = false;
let paletteItems = [];   // 扁平化可见列表 [{name, description, type}]
let activeIndex = 0;
let capabilitiesCache = null;

async function fetchCapabilities() {
    if (capabilitiesCache) return capabilitiesCache;
    try {
        const res = await fetch('/api/capabilities');
        if (res.ok) capabilitiesCache = await res.json();
    } catch (e) {
        console.warn('能力列表加载失败:', e);
    }
    return capabilitiesCache || { skills: [], agents: [] };
}

async function openPalette() {
    const data = await fetchCapabilities();
    paletteOpen = true;
    activeIndex = 0;
    renderPalette(getFilterText());
    commandPalette.classList.remove('hidden');
}

function closePalette() {
    paletteOpen = false;
    paletteItems = [];
    activeIndex = 0;
    commandPalette.classList.add('hidden');
}

function getFilterText() {
    const val = chatInput.value;
    if (val.startsWith('/')) {
        return val.slice(1).toLowerCase();
    }
    return '';
}

function renderPalette(filter) {
    const data = capabilitiesCache || { skills: [], agents: [] };
    const matchFn = (item) => !filter || item.name.toLowerCase().includes(filter);

    const skills = (data.skills || []).filter(matchFn);
    const agents = (data.agents || []).filter(matchFn);

    paletteItems = [
        ...skills.map(s => ({ ...s, type: 'skill' })),
        ...agents.map(a => ({ ...a, type: 'agent' }))
    ];

    if (paletteItems.length === 0) {
        commandPalette.innerHTML = '<div class="cmd-empty">无匹配结果</div>';
        return;
    }

    let html = '';
    if (skills.length > 0) {
        html += '<div class="group-title">🔧 Skills</div>';
        skills.forEach((s, i) => {
            const idx = i;
            html += `<div class="cmd-item${idx === activeIndex ? ' active' : ''}" data-index="${idx}">
                <span class="cmd-name">${escapeHtml(s.name)}</span>
                <span class="cmd-desc">${escapeHtml(s.description || '')}</span>
            </div>`;
        });
    }
    if (agents.length > 0) {
        html += '<div class="group-title">🤖 SubAgents</div>';
        agents.forEach((a, i) => {
            const idx = skills.length + i;
            html += `<div class="cmd-item${idx === activeIndex ? ' active' : ''}" data-index="${idx}">
                <span class="cmd-name">${escapeHtml(a.name)}</span>
                <span class="cmd-desc">${escapeHtml(a.description || '')}</span>
            </div>`;
        });
    }
    commandPalette.innerHTML = html;

    // 鼠标点击选中
    commandPalette.querySelectorAll('.cmd-item').forEach(el => {
        el.addEventListener('click', () => {
            const idx = parseInt(el.dataset.index);
            selectPaletteItem(paletteItems[idx]);
        });
    });
}

function selectPaletteItem(item) {
    if (!item) return;
    chatInput.value = '/' + item.name + ' ';
    closePalette();
    chatInput.focus();
    autoResize();
    checkSkillMatch();
}

/**
 * 检测输入框中的 skill 名并切换激活态样式
 */
function checkSkillMatch() {
    const val = chatInput.value;
    if (!val.startsWith('/')) {
        chatForm.classList.remove('skill-active');
        return;
    }
    const skillName = val.slice(1).split(' ')[0];
    if (!skillName) {
        chatForm.classList.remove('skill-active');
        return;
    }
    const data = capabilitiesCache || { skills: [] };
    const matched = (data.skills || []).some(s => s.name.toLowerCase() === skillName.toLowerCase());
    chatForm.classList.toggle('skill-active', matched);
}

function paletteKeyDown(e) {
    if (!paletteOpen) return false;
    if (e.key === 'ArrowDown') {
        e.preventDefault();
        activeIndex = (activeIndex + 1) % paletteItems.length;
        renderPalette(getFilterText());
        return true;
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        activeIndex = (activeIndex - 1 + paletteItems.length) % paletteItems.length;
        renderPalette(getFilterText());
        return true;
    } else if (e.key === 'Enter') {
        e.preventDefault();
        selectPaletteItem(paletteItems[activeIndex]);
        return true;
    } else if (e.key === 'Escape') {
        e.preventDefault();
        closePalette();
        return true;
    }
    return false;
}

// 点击面板外部关闭
document.addEventListener('click', (e) => {
    if (paletteOpen && !commandPalette.contains(e.target) && e.target !== chatInput) {
        closePalette();
    }
});

// ===== 追加消息气泡（Task 6.5 核心）=====
function appendMessage(role, content, options = {}) {
    // 如果存在欢迎信息，移除它
    const welcome = messagesContainer.querySelector('.welcome-message');
    if (welcome) welcome.remove();

    // 如果存在 typing indicator，移除它
    removeTypingIndicator();

    // 压缩摘要：渲染为分隔线
    if (role === 'synthetic' || options.synthetic) {
        const divider = document.createElement('div');
        divider.className = 'compaction-divider';
        const tokenCount = options.tokens || estimateTokensFrontend(content);
        divider.innerHTML = `
            <span class="compaction-divider-line"></span>
            <span class="compaction-divider-label" title="点击展开摘要">
                📝 上下文已压缩（~${tokenCount} tokens）
            </span>
            <span class="compaction-divider-line"></span>
            <div class="compaction-summary hidden">${renderMarkdown(content)}</div>
        `;
        divider.querySelector('.compaction-divider-label').addEventListener('click', () => {
            divider.querySelector('.compaction-summary').classList.toggle('hidden');
        });
        messagesContainer.appendChild(divider);
        scrollToBottom();
        return divider;
    }

    const messageEl = document.createElement('div');
    messageEl.className = `message ${role}`;

    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'message-bubble';
    if (role === 'assistant') {
        bubbleEl.innerHTML = renderMarkdown(content);
    } else {
        // 技能调用指令：以 / 开头，技能名加特殊样式，描述保持默认
        const trimmed = content.trim();
        if (trimmed.startsWith('/')) {
            const match = trimmed.match(/^\/(\S+)(?:\s+([\s\S]*))?$/);
            if (match) {
                const skillName = match[1];
                const desc = match[2] || '';
                bubbleEl.innerHTML = `<span class="skill-name">/${escapeHtml(skillName)}</span>${desc ? ' ' + escapeHtml(desc) : ''}`;
            } else {
                bubbleEl.textContent = content;
            }
        } else {
            bubbleEl.textContent = content;
        }
    }

    // 如果有图片，追加到气泡中
    if (options.imageBase64) {
        const img = document.createElement('img');
        img.src = options.imageBase64;
        img.className = 'message-image';
        img.alt = '图片';
        bubbleEl.appendChild(img);
    }

    messageEl.appendChild(bubbleEl);

    messagesContainer.appendChild(messageEl);
    scrollToBottom();
    return messageEl;
}

// ===== 加载动画 =====
function showTypingIndicator() {
    removeTypingIndicator();
    const wrapper = document.createElement('div');
    wrapper.className = 'message assistant';
    wrapper.id = 'typingIndicator';
    wrapper.innerHTML = `
        <div class="typing-indicator">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
        </div>
    `;
    messagesContainer.appendChild(wrapper);
    scrollToBottom();
}

function removeTypingIndicator() {
    const existing = document.getElementById('typingIndicator');
    if (existing) existing.remove();
}

// ===== 思考脉冲指示器 =====
let thinkingTimer = null;
let thinkingPulseEl = null;

function scheduleThinkingPulse() {
    clearTimeout(thinkingTimer);
    thinkingTimer = setTimeout(() => {
        if (!thinkingPulseEl) {
            ensureAiMessage();
            thinkingPulseEl = document.createElement('div');
            thinkingPulseEl.className = 'thinking-pulse';
            thinkingPulseEl.innerHTML = `<span class="dot"></span><span class="dot"></span><span class="dot"></span><span class="thinking-label">思考中</span>`;
            toolCardsContainer.appendChild(thinkingPulseEl);
            scrollToBottom();
        }
    }, 1500);
}

function removeThinkingPulse() {
    clearTimeout(thinkingTimer);
    thinkingTimer = null;
    if (thinkingPulseEl) {
        thinkingPulseEl.remove();
        thinkingPulseEl = null;
    }
}

function ensureAiMessage() {
    if (!aiBubbleEl) {
        aiBubbleEl = appendMessage('assistant', '');
        toolCardsContainer = document.createElement('div');
        toolCardsContainer.className = 'tool-cards-container';
        aiBubbleEl.appendChild(toolCardsContainer);
    }
    return aiBubbleEl;
}

// ===== 自动滚动 =====
function scrollToBottom() {
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

// ===== 错误提示 =====
function showError(message) {
    const toast = document.createElement('div');
    toast.className = 'error-toast';
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 5000);
}

// ===== 模型选择器 =====
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

// ===== 工具调用可视化卡片 =====

// 当前轮次待完成的工具卡片：[{name, el}]
let pendingToolCards = [];
let toolCardsContainer = null;
let aiBubbleEl = null;

/**
 * 根据工具名前缀映射图标和提示文案
 */
function getToolIcon(toolName) {
    if (!toolName) return { icon: '\u2699\uFE0F', label: '\u5DE5\u5177\u8C03\u7528\u4E2D...' };
    if (toolName.startsWith('Memory')) return { icon: '\uD83D\uDCDD', label: '\u6B63\u5728\u64CD\u4F5C\u8BB0\u5FC6...' };
    if (toolName === 'Skill') return { icon: '\uD83D\uDD27', label: '\u6B63\u5728\u6267\u884C\u6280\u80FD...' };
    if (toolName === 'Task') return { icon: '\uD83E\uDD16', label: '\u5B50Agent\u6267\u884C\u4E2D...' };
    return { icon: '\u2699\uFE0F', label: '\u5DE5\u5177\u8C03\u7528\u4E2D...' };
}

/**
 * 在工具卡片容器中追加一张 pending 状态的卡片
 */
function appendToolCard(container, toolName, icon, description) {
    // 后端未提供 icon 时，根据工具名前缀映射
    const fallback = getToolIcon(toolName);
    const displayIcon = icon || fallback.icon;
    const card = document.createElement('div');
    card.className = 'tool-card tool-pending';
    const descHtml = description ? `<span class="tool-description">${escapeHtml(description)}</span>` : '';
    card.innerHTML = `
        <span class="tool-icon">${displayIcon}</span>
        <span class="tool-name">${escapeHtml(toolName)}</span>
        ${descHtml}
        <span class="tool-status-dot"></span>
        <span class="tool-status-text">\u6267\u884C\u4E2D...</span>
    `;
    container.appendChild(card);
    pendingToolCards.push({ name: toolName, el: card });
    scrollToBottom();
    return card;
}

/**
 * 将指定工具名的最近一张 pending 卡片切换为完成/失败状态
 */
function completeToolCard(toolName, status) {
    for (let i = pendingToolCards.length - 1; i >= 0; i--) {
        if (pendingToolCards[i].name === toolName) {
            const card = pendingToolCards[i].el;
            card.classList.remove('tool-pending');
            const statusText = card.querySelector('.tool-status-text');
            if (status && status.startsWith('error')) {
                card.classList.add('tool-error');
                statusText.textContent = '\u5931\u8D25';
            } else {
                card.classList.add('tool-done');
                statusText.textContent = '\u5B8C\u6210';
            }
            pendingToolCards.splice(i, 1);
            break;
        }
    }
}

/**
 * 将所有 pending 卡片标记为完成（用于中止/结束场景）
 */
function completeAllPendingToolCards() {
    pendingToolCards.forEach(({ el }) => {
        el.classList.remove('tool-pending');
        el.classList.add('tool-done');
        const statusText = el.querySelector('.tool-status-text');
        if (statusText) statusText.textContent = '\u5B8C\u6210';
    });
    pendingToolCards = [];
}

/**
 * 处理单条 SSE 数据行，解析 JSON 并更新 UI。
 * 返回更新后的 fullResponse。
 */
function processSseLine(line, currentFullResponse) {
    if (!line.startsWith('data:')) return currentFullResponse;

    const data = line.slice(5).trim();
    if (data === '[DONE]') return currentFullResponse;

    try {
        const parsed = JSON.parse(data);
        if (parsed.error) {
            showError(parsed.error);
            return currentFullResponse;
        }
        // 工具调用事件
        if (parsed.tool) {
            if (parsed.tool === 'start') {
                removeThinkingPulse();
                ensureAiMessage();
                appendToolCard(toolCardsContainer, parsed.name, parsed.icon, parsed.description);
            } else if (parsed.tool === 'end') {
                completeToolCard(parsed.name, parsed.status);
                scheduleThinkingPulse();
            }
            return currentFullResponse;
        }
        const token = parsed.content || parsed.delta || '';
        if (token) {
            removeThinkingPulse();
            ensureAiMessage();
            currentFullResponse += token;
            // 更新 AI 气泡内容（rAF 批处理 Markdown 渲染）
            scheduleRafRender(aiBubbleEl.querySelector('.message-bubble'), currentFullResponse);
        }
    } catch (e) {
        // 非 JSON 格式的 data，直接作为文本处理
        if (data && data !== '[DONE]') {
            removeThinkingPulse();
            ensureAiMessage();
            currentFullResponse += data;
            scheduleRafRender(aiBubbleEl.querySelector('.message-bubble'), currentFullResponse);
        }
    }
    return currentFullResponse;
}

// ===== 发送消息 - SSE 流式 =====
async function sendMessage(message) {
    if (!message.trim()) return;

    // 显示用户消息（带上图片预览）
    appendMessage('user', message, { imageBase64: currentImageBase64 });

    // 进入流式状态（按钮变为停止）
    enterStreamingState();

    // 显示加载动画
    showTypingIndicator();

    // 创建 AI 消息气泡（稍后填充内容）
    aiBubbleEl = null;
    let fullResponse = '';

    try {
        const selectedModel = modelSelector ? modelSelector.value : '';
        const url = `/chat/stream${selectedModel ? '?model=' + encodeURIComponent(selectedModel) : ''}`;
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                message: message,
                sessionId: currentSessionId,
                imageBase64: currentImageBase64
            }),
            signal: abortController.signal
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        // 移除加载动画（AI 气泡延迟到首个内容到达时创建，避免空气泡）
        removeTypingIndicator();
        scheduleThinkingPulse();

        // 使用 ReadableStream 读取 SSE
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) {
                // 处理 buffer 中剩余的数据（可能没有以 \n 结尾）
                if (buffer) {
                    fullResponse = processSseLine(buffer, fullResponse);
                }
                break;
            }

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop(); // 保留不完整的行

            for (const line of lines) {
                fullResponse = processSseLine(line, fullResponse);
            }
        }

        // 流式结束后做一次最终完整渲染，确保未闭合的 Markdown 结构正确渲染
        removeThinkingPulse();
        if (fullResponse) {
            ensureAiMessage();
            aiBubbleEl.querySelector('.message-bubble').innerHTML = renderMarkdown(fullResponse);
        } else if (aiBubbleEl) {
            // 仅有工具调用无文本内容时，标记为执行完成
            aiBubbleEl.querySelector('.message-bubble').innerHTML = renderMarkdown('(执行完成)');
        } else {
            appendMessage('assistant', '(空回复)');
        }

    } catch (error) {
        if (error.name === 'AbortError') {
            // 用户主动中止，保留已有内容，不弹错误
            removeTypingIndicator();
            completeAllPendingToolCards();
            if (aiBubbleEl) {
                const bubble = aiBubbleEl.querySelector('.message-bubble');
                if (fullResponse) {
                    bubble.innerHTML = renderMarkdown(fullResponse);
                } else if (!bubble.textContent.trim()) {
                    bubble.textContent = '(已中止)';
                }
            }
        } else {
            console.error('SSE 流式请求失败:', error);
            removeTypingIndicator();
            if (!aiBubbleEl) {
                appendMessage('assistant', `连接失败: ${error.message}`);
            } else {
                aiBubbleEl.querySelector('.message-bubble').innerHTML = renderMarkdown(`连接失败: ${error.message}`);
            }
            showError(`请求失败: ${error.message}`);
        }
    } finally {
        removeThinkingPulse();
        completeAllPendingToolCards();
        exitStreamingState();
        chatInput.focus();
        // 消息交互后刷新上下文信息
        refreshContextInfo();
        // 发送完成后清除图片预览
        hideImagePreview();
    }
}

// ===== 历史消息加载 =====
async function loadHistory() {
    try {
        const response = await fetch(`/chat/history?sessionId=${encodeURIComponent(currentSessionId)}`);
        if (!response.ok) {
            console.warn('历史消息加载失败:', response.status);
            return;
        }

        const messages = await response.json();
        if (messages && messages.length > 0) {
            // 工具卡片缓冲：tool_call 先缓冲，挂到下一条 assistant 文本消息气泡之后（复刻 live DOM 顺序）
            let pendingCards = null;

            const flushPendingCards = () => {
                if (!pendingCards) return;
                // 历史末尾无后续文本消息：裸消息外壳兜底挂载（不创建空气泡）
                const wrapper = document.createElement('div');
                wrapper.className = 'message assistant';
                wrapper.appendChild(pendingCards);
                messagesContainer.appendChild(wrapper);
                pendingCards = null;
            };

            messages.forEach(msg => {
                // 工具调用事件：构建完成态卡片进缓冲，不创建气泡
                if (msg.type === 'tool_call' && msg.tools) {
                    if (!pendingCards) {
                        pendingCards = document.createElement('div');
                        pendingCards.className = 'tool-cards-container';
                    }
                    msg.tools.forEach(tool => {
                        // 优先用后端返回的图标，回退到前端映射
                        const fallback = getToolIcon(tool.name);
                        const icon = tool.icon || fallback.icon;
                        const descHtml = tool.description
                            ? `<span class="tool-description">${escapeHtml(tool.description)}</span>`
                            : '';
                        const card = document.createElement('div');
                        card.className = 'tool-card tool-done';
                        card.innerHTML = `
                            <span class="tool-icon">${icon}</span>
                            <span class="tool-name">${escapeHtml(tool.name || '')}</span>
                            ${descHtml}
                            <span class="tool-status-dot"></span>
                            <span class="tool-status-text">\u5B8C\u6210</span>
                        `;
                        pendingCards.appendChild(card);
                    });
                    return;
                }
                // 工具响应事件：内部数据，跳过渲染
                if (msg.type === 'tool_response') {
                    return;
                }
                // 普通消息（user / assistant / synthetic）
                const messageEl = appendMessage(msg.role, msg.content, {
                    synthetic: msg.synthetic === true,
                    imageBase64: msg.imageBase64
                });
                // 缓冲的工具卡片挂到 assistant 文本消息气泡之后（与 live 同序）
                if (pendingCards && msg.role === 'assistant' && !msg.synthetic && messageEl) {
                    messageEl.appendChild(pendingCards);
                    pendingCards = null;
                }
            });

            flushPendingCards();
        }
        // 历史加载后刷新上下文信息
        refreshContextInfo();
    } catch (error) {
        console.warn('历史消息加载异常:', error);
    }
}

// ===== 事件绑定 =====
chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const message = chatInput.value.trim();
    if (message && !abortController) {
        sendMessage(message);
        chatInput.value = '';
        autoResize();
        checkSkillMatch();
    }
});

chatInput.addEventListener('keydown', (e) => {
    // 命令面板打开时拦截键盘
    if (paletteKeyDown(e)) return;

    // Enter 发送，Shift+Enter 换行
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        const message = chatInput.value.trim();
        if (message && !abortController) {
            sendMessage(message);
            chatInput.value = '';
            autoResize();
            checkSkillMatch();
        }
    }
});

// 输入框自动高度
function autoResize() {
    chatInput.style.height = 'auto';
    chatInput.style.height = Math.min(chatInput.scrollHeight, 120) + 'px';
}

chatInput.addEventListener('input', () => {
    autoResize();
    // 实时检测 skill 名并切换激活态
    checkSkillMatch();
    // "/" 命令面板触发检测
    const val = chatInput.value;
    if (val.startsWith('/') && !val.includes(' ') && !abortController) {
        if (!paletteOpen) {
            openPalette();
        } else {
            activeIndex = 0;
            renderPalette(getFilterText());
        }
    } else if (paletteOpen) {
        closePalette();
    }
});

// ===== 图片粘贴与预览 =====

/**
 * 处理粘贴事件，提取图片文件
 */
function handlePaste(e) {
    const items = e.clipboardData?.items;
    if (!items) return;

    for (const item of items) {
        if (item.type.startsWith('image/')) {
            const file = item.getAsFile();
            if (file) {
                e.preventDefault();
                handleImageFile(file);
                break;
            }
        }
    }
}

/**
 * 处理图片文件：大小检查、读取预览
 */
function handleImageFile(file) {
    const MAX_SIZE = 2 * 1024 * 1024; // 2MB
    if (file.size > MAX_SIZE) {
        showError('图片大小不能超过 2MB');
        return;
    }

    const reader = new FileReader();
    reader.onload = (ev) => {
        currentImageBase64 = ev.target.result;
        showImagePreview(currentImageBase64);
    };
    reader.onerror = () => {
        showError('图片读取失败');
    };
    reader.readAsDataURL(file);
}

/**
 * 显示图片预览
 */
function showImagePreview(base64) {
    if (!imagePreviewImg || !imagePreview) return;
    imagePreviewImg.src = base64;
    imagePreview.classList.add('visible');
}

/**
 * 隐藏图片预览
 */
function hideImagePreview() {
    if (!imagePreviewImg || !imagePreview) return;
    imagePreviewImg.src = '';
    imagePreview.classList.remove('visible');
    currentImageBase64 = null;
}

chatInput.addEventListener('paste', handlePaste);
removeImageBtn?.addEventListener('click', hideImagePreview);

// ===== 抽屉交互 =====
function openDrawer() {
    sessionDrawer.classList.add('open');
    drawerOverlay.classList.add('visible');
    loadSessionList();
}

function closeDrawer() {
    sessionDrawer.classList.remove('open');
    drawerOverlay.classList.remove('visible');
}

drawerToggle.addEventListener('click', () => {
    if (sessionDrawer.classList.contains('open')) {
        closeDrawer();
    } else {
        openDrawer();
    }
});

drawerClose.addEventListener('click', closeDrawer);
drawerOverlay.addEventListener('click', closeDrawer);

// ===== 会话列表加载 =====
async function loadSessionList() {
    try {
        const response = await fetch('/chat/sessions');
        if (!response.ok) return;
        const sessions = await response.json();
        renderSessionList(sessions);
    } catch (e) {
        console.warn('会话列表加载失败:', e);
    }
}

function renderSessionList(sessions) {
    sessionList.innerHTML = '';
    if (!sessions || sessions.length === 0) {
        sessionList.innerHTML = '<li class="session-empty">还没有会话，点击“+ 新对话”开始</li>';
        return;
    }
    sessions.forEach(s => {
        const li = document.createElement('li');
        li.className = 'session-item' + (s.id === currentSessionId ? ' active' : '');
        li.innerHTML = `
            <div class="session-item-title">${escapeHtml(s.title || '新对话')}</div>
            <div class="session-item-time">${formatTime(s.updatedAt)}</div>
            <button class="session-delete" title="删除会话">✕</button>
        `;
        li.addEventListener('click', (e) => {
            if (e.target.closest('.session-delete')) return;
            switchSession(s.id);
        });
        li.querySelector('.session-delete').addEventListener('click', (e) => {
            e.stopPropagation();
            confirmDeleteSession(s.id, s.title || '新对话');
        });
        sessionList.appendChild(li);
    });
}

// ===== 删除会话 =====
function confirmDeleteSession(sessionId, title) {
    let dialog = document.getElementById('deleteSessionDialog');
    if (!dialog) {
        dialog = document.createElement('div');
        dialog.id = 'deleteSessionDialog';
        dialog.className = 'confirm-dialog';
        dialog.innerHTML = `
            <h3>🗑️ 删除会话</h3>
            <p>确定删除该会话及其所有消息吗？<br>此操作不可撤销。</p>
            <div class="dialog-actions">
                <button class="btn-cancel" id="delDialogCancel">取消</button>
                <button class="btn-confirm" id="delDialogConfirm">删除</button>
            </div>
        `;
        document.body.appendChild(dialog);
    }
    dialog.classList.add('visible');

    const onCancel = () => {
        dialog.classList.remove('visible');
        cleanup();
    };
    const onConfirm = async () => {
        dialog.classList.remove('visible');
        cleanup();
        await doDeleteSession(sessionId);
    };
    const cleanup = () => {
        document.getElementById('delDialogCancel').removeEventListener('click', onCancel);
        document.getElementById('delDialogConfirm').removeEventListener('click', onConfirm);
    };
    document.getElementById('delDialogCancel').addEventListener('click', onCancel);
    document.getElementById('delDialogConfirm').addEventListener('click', onConfirm);
}

async function doDeleteSession(sessionId) {
    try {
        const response = await fetch(`/chat/sessions/${encodeURIComponent(sessionId)}`, {
            method: 'DELETE'
        });
        if (!response.ok) throw new Error('删除失败');

        // 若删的是当前会话，切到列表第一个或新建
        if (sessionId === currentSessionId) {
            const listRes = await fetch('/chat/sessions');
            const sessions = listRes.ok ? await listRes.json() : [];
            if (sessions.length > 0) {
                await switchSession(sessions[0].id);
            } else {
                // 无剩余会话，新建一个
                const newRes = await fetch('/chat/sessions', { method: 'POST' });
                const data = await newRes.json();
                setCurrentSession(data.id);
                messagesContainer.innerHTML = '';
                showWelcome();
            }
        }
        // 刷新抽屉列表
        loadSessionList();
    } catch (e) {
        showError('删除会话失败: ' + e.message);
    }
}

// ===== 会话切换 =====
async function switchSession(id) {
    if (id === currentSessionId) {
        closeDrawer();
        return;
    }
    setCurrentSession(id);
    // 清空消息区，重新加载历史
    messagesContainer.innerHTML = '';
    await loadHistory();
    if (messagesContainer.children.length === 0) {
        showWelcome();
    }
    closeDrawer();
    chatInput.focus();
}

// ===== 新建会话 =====
newSessionBtn.addEventListener('click', async () => {
    try {
        const response = await fetch('/chat/sessions', { method: 'POST' });
        if (!response.ok) throw new Error('创建失败');
        const data = await response.json();
        setCurrentSession(data.id);
        messagesContainer.innerHTML = '';
        showWelcome();
        closeDrawer();
        chatInput.focus();
    } catch (e) {
        showError('创建新会话失败: ' + e.message);
    }
});

// ===== 清空当前会话 =====
clearSessionBtn.addEventListener('click', () => {
    showConfirmDialog();
});

function showConfirmDialog() {
    // 动态创建确认弹窗
    let dialog = document.getElementById('confirmDialog');
    if (!dialog) {
        dialog = document.createElement('div');
        dialog.id = 'confirmDialog';
        dialog.className = 'confirm-dialog';
        dialog.innerHTML = `
            <h3>🍂 清空会话</h3>
            <p>确定清空当前会话的所有消息吗？<br>会话本身不会被删除。</p>
            <div class="dialog-actions">
                <button class="btn-cancel" id="dialogCancel">取消</button>
                <button class="btn-confirm" id="dialogConfirm">清空</button>
            </div>
        `;
        document.body.appendChild(dialog);
        document.getElementById('dialogCancel').addEventListener('click', hideConfirmDialog);
        document.getElementById('dialogConfirm').addEventListener('click', doClearSession);
    }
    dialog.classList.add('visible');
}

function hideConfirmDialog() {
    const dialog = document.getElementById('confirmDialog');
    if (dialog) dialog.classList.remove('visible');
}

async function doClearSession() {
    hideConfirmDialog();
    try {
        const response = await fetch(`/chat/messages?sessionId=${encodeURIComponent(currentSessionId)}`, {
            method: 'DELETE'
        });
        if (!response.ok) throw new Error('清空失败');
        messagesContainer.innerHTML = '';
        showWelcome();
    } catch (e) {
        showError('清空会话失败: ' + e.message);
    }
}

// ===== 手动压缩上下文 =====
compactBtn.addEventListener('click', async () => {
    // 流式中禁用
    if (abortController) return;

    compactBtn.classList.add('loading');
    compactBtn.disabled = true;

    try {
        const response = await fetch(`/chat/sessions/${encodeURIComponent(currentSessionId)}/compact`, {
            method: 'POST'
        });
        const result = await response.json();

        if (result.archivedCount > 0) {
            showCompactToast(`✅ 已压缩 ${result.archivedCount} 条消息\n${result.summaryPreview}`);
            // 压缩成功后刷新消息区域
            messagesContainer.innerHTML = '';
            await loadHistory();
            if (messagesContainer.children.length === 0) {
                showWelcome();
            }
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

// ===== 记忆抽屉交互 =====
const memoryToggle = document.getElementById('memoryToggle');
const memoryDrawer = document.getElementById('memoryDrawer');
const memoryDrawerClose = document.getElementById('memoryDrawerClose');
const memoryOverlay = document.getElementById('memoryOverlay');
const memoryList = document.getElementById('memoryList');
const memoryManageBtn = document.getElementById('memoryManageBtn');

// 记忆类型图标映射
const MEMORY_TYPE_ICONS = {
    'user': '☁️',
    'feedback': '🍃',
    'project': '🌿',
    'reference': '🔗'
};

function openMemoryDrawer() {
    // 关闭左侧会话抽屉（互斥）
    closeDrawer();
    memoryDrawer.classList.add('open');
    memoryOverlay.classList.add('visible');
    loadMemoryList();
}

function closeMemoryDrawer() {
    memoryDrawer.classList.remove('open');
    memoryOverlay.classList.remove('visible');
}

async function loadMemoryList() {
    try {
        const res = await fetch('/api/memories');
        if (!res.ok) return;
        const memories = await res.json();
        renderMemoryList(memories);
    } catch (e) {
        console.warn('记忆列表加载失败:', e);
    }
}

function renderMemoryList(memories) {
    if (!memories || memories.length === 0) {
        memoryList.innerHTML = '<div class="memory-empty">还没有记忆，与 AI 对话后会自动记住你的偏好</div>';
        return;
    }

    // 按 type 分组
    const groups = {};
    memories.forEach(m => {
        const type = m.type || 'other';
        if (!groups[type]) groups[type] = [];
        groups[type].push(m);
    });

    let html = '';
    for (const [type, items] of Object.entries(groups)) {
        const icon = MEMORY_TYPE_ICONS[type] || '📄';
        html += `<div class="memory-group-title">${icon} ${type}</div>`;
        items.forEach(m => {
            const name = escapeHtml(m.name || m.fileName || '');
            const desc = escapeHtml(m.description || '');
            const fileName = escapeHtml(m.fileName || '');
            html += `<div class="memory-item" data-file="${fileName}">
                <div class="memory-item-name">${name}</div>
                <div class="memory-item-desc">${desc}</div>
                <div class="memory-preview"></div>
            </div>`;
        });
    }
    memoryList.innerHTML = html;

    // 点击展开/收起内联预览
    memoryList.querySelectorAll('.memory-item').forEach(el => {
        el.addEventListener('click', async () => {
            const preview = el.querySelector('.memory-preview');
            if (preview.classList.contains('visible')) {
                preview.classList.remove('visible');
                return;
            }
            // 加载详情
            const file = el.dataset.file;
            try {
                const res = await fetch(`/api/memories/detail?file=${encodeURIComponent(file)}`);
                if (res.ok) {
                    const detail = await res.json();
                    const content = (detail.content || '').substring(0, 200);
                    preview.innerHTML = renderMarkdown(content);
                    preview.classList.add('visible');
                }
            } catch (e) {
                console.warn('记忆详情加载失败:', e);
            }
        });
    });
}

memoryToggle.addEventListener('click', () => {
    if (memoryDrawer.classList.contains('open')) {
        closeMemoryDrawer();
    } else {
        openMemoryDrawer();
    }
});

memoryDrawerClose.addEventListener('click', closeMemoryDrawer);
memoryOverlay.addEventListener('click', closeMemoryDrawer);

memoryManageBtn.addEventListener('click', () => {
    window.location.href = '/memories-page';
});

// ===== 工具函数 =====

// 前端 token 估算（与后端 TokenEstimator 保持一致）
function estimateTokensFrontend(text) {
    if (!text) return 0;
    let tokens = 0;
    let inEnglishWord = false;
    let englishWordChars = 0;

    for (let i = 0; i < text.length; i++) {
        const c = text.charCodeAt(i);
        // CJK 统一汉字基本区 (4E00-9FFF) + 扩展 A (3400-4DBF)
        if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) {
            if (inEnglishWord) {
                tokens += Math.max(1, englishWordChars * 0.75);
                inEnglishWord = false;
                englishWordChars = 0;
            }
            tokens += 1.5;
        } else if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122)) {
            // ASCII 字母
            inEnglishWord = true;
            englishWordChars++;
        } else if (c === 32 || c === 9 || c === 10 || c === 13) {
            // 空白
            if (inEnglishWord) {
                tokens += Math.max(1, englishWordChars * 0.75);
                inEnglishWord = false;
                englishWordChars = 0;
            }
        } else {
            if (inEnglishWord) {
                tokens += Math.max(1, englishWordChars * 0.75);
                inEnglishWord = false;
                englishWordChars = 0;
            }
            tokens += 1;
        }
    }
    if (inEnglishWord) {
        tokens += Math.max(1, englishWordChars * 0.75);
    }
    return Math.ceil(tokens);
}

// ===== 上下文进度条 =====
const contextBar = document.getElementById('contextBar');
const contextBarFill = document.getElementById('contextBarFill');
const contextBarText = document.getElementById('contextBarText');

async function refreshContextInfo() {
    try {
        const res = await fetch(`/chat/context-info?sessionId=${encodeURIComponent(currentSessionId)}`);
        if (!res.ok) return;
        const info = await res.json();

        const percent = info.usagePercent || 0;
        const used = info.totalTokens || 0;
        const max = info.maxTokens || 128000;
        const compactions = info.compactionCount || 0;

        // 格式化数字（k 单位）
        const formatK = (n) => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : n;

        // 更新进度条
        contextBarFill.style.width = Math.min(percent, 100) + '%';

        // 颜色级别
        contextBarFill.className = 'context-bar-fill';
        if (percent > 85) {
            contextBarFill.classList.add('danger');
        } else if (percent > 60) {
            contextBarFill.classList.add('warning');
        }

        // 文本
        let text = `${formatK(used)} / ${formatK(max)}（${percent.toFixed(1)}%）`;
        if (compactions > 0) {
            text += ` · 已压缩 ${compactions} 次`;
        }
        contextBarText.textContent = text;
        contextBar.classList.remove('hidden');
    } catch (e) {
        // 静默失败，不影响主流程
    }
}

// Markdown 渲染（marked.parse + DOMPurify 双层防御）
marked.setOptions({ gfm: true, breaks: true });

function renderMarkdown(text) {
    if (!text) return '';
    const rawHtml = marked.parse(text);
    return DOMPurify.sanitize(rawHtml);
}

// rAF 批处理渲染调度（同一帧内多个 token 合并为一次渲染）
let _rafScheduled = false;
let _rafPendingText = '';
let _rafPendingBubble = null;

function scheduleRafRender(bubbleEl, text) {
    _rafPendingBubble = bubbleEl;
    _rafPendingText = text;
    if (!_rafScheduled) {
        _rafScheduled = true;
        requestAnimationFrame(() => {
            _rafScheduled = false;
            if (_rafPendingBubble) {
                _rafPendingBubble.innerHTML = renderMarkdown(_rafPendingText);
                scrollToBottom();
            }
        });
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function formatTime(isoStr) {
    if (!isoStr) return '';
    const d = new Date(isoStr);
    const now = new Date();
    if (d.toDateString() === now.toDateString()) {
        return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    }
    return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
}

// ===== header 天气簇与天气主题 =====
const weatherCluster = document.getElementById('weatherCluster');

/**
 * WWO weatherCode 到主题标识的映射
 */
function mapWeatherTheme(code) {
    const c = parseInt(code, 10);
    if (isNaN(c)) return 'sunny';
    if ([200, 386, 389, 392, 395].includes(c)) return 'storm';
    if ([179, 182, 185, 227, 230, 320, 323, 326, 329, 332, 335, 338, 368, 371, 374, 377].includes(c)) return 'snow';
    if ([176, 263, 266, 281, 286, 293, 298, 302, 305, 308, 311, 314, 317, 350, 353, 356, 359, 362, 365].includes(c)) return 'rain';
    if ([116, 119, 122, 143, 248, 260].includes(c)) return 'cloudy';
    return 'sunny';
}

/**
 * 拉取天气并渲染 header 天气簇 + 设置 header 天气主题（非阻塞，失败静默隐藏）
 */
async function loadWeather() {
    try {
        const res = await fetch('/api/weather');
        const data = await res.json();
        if (data.error) return;
        weatherCluster.innerHTML = `
            <img class="weather-icon" src="${data.weatherIconUrl}" alt="" onerror="this.style.display='none'">
            <span class="weather-temp">${data.tempC}°</span>
            <span class="weather-desc">${data.weatherDesc}</span>
            <span class="weather-humidity">💧 ${data.humidity}%</span>
        `;
        weatherCluster.hidden = false;
        document.querySelector('.chat-header').dataset.weather = mapWeatherTheme(data.weatherCode);
    } catch (e) {
        console.warn('天气加载失败:', e);
    }
}

// ===== 页面初始化 =====
document.addEventListener('DOMContentLoaded', async () => {
    // 加载模型列表（非阻塞，失败不影响主流程）
    loadModels();
    // 加载 header 天气簇与天气主题（非阻塞，失败不影响主流程）
    loadWeather();
    // 先加载历史消息
    await loadHistory();

    // 如果没有历史，显示欢迎信息
    if (messagesContainer.children.length === 0) {
        showWelcome();
    }

    // 聚焦输入框
    chatInput.focus();
});

function showWelcome() {
    const welcome = document.createElement('div');
    welcome.className = 'welcome-message';
    welcome.innerHTML = `
        <div class="icon">🌿</div>
        <h2>Agent Platform</h2>
        <p>像风一样，说出你想说的吧 ~</p>
    `;
    messagesContainer.appendChild(welcome);
}
