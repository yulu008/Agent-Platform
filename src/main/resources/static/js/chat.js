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
function appendMessage(role, content) {
    // 如果存在欢迎信息，移除它
    const welcome = messagesContainer.querySelector('.welcome-message');
    if (welcome) welcome.remove();

    // 如果存在 typing indicator，移除它
    removeTypingIndicator();

    const messageEl = document.createElement('div');
    messageEl.className = `message ${role}`;

    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'message-bubble';
    bubbleEl.textContent = content;

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

// ===== 发送消息 - SSE 流式 =====
async function sendMessage(message) {
    if (!message.trim()) return;

    // 显示用户消息
    appendMessage('user', message);

    // 进入流式状态（按钮变为停止）
    enterStreamingState();

    // 显示加载动画
    showTypingIndicator();

    // 创建 AI 消息气泡（稍后填充内容）
    let aiBubbleEl = null;

    try {
        const response = await fetch('/chat/stream', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                message: message,
                sessionId: currentSessionId
            }),
            signal: abortController.signal
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        // 移除加载动画，创建空的 AI 气泡
        removeTypingIndicator();
        aiBubbleEl = appendMessage('assistant', '');

        // 使用 ReadableStream 读取 SSE
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let fullResponse = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop(); // 保留不完整的行

            for (const line of lines) {
                if (line.startsWith('data:')) {
                    const data = line.slice(5).trim();
                    if (data === '[DONE]') continue;

                    try {
                        const parsed = JSON.parse(data);
                        if (parsed.error) {
                            showError(parsed.error);
                            continue;
                        }
                        const token = parsed.content || parsed.delta || '';
                        if (token) {
                            fullResponse += token;
                            // 更新 AI 气泡内容
                            aiBubbleEl.querySelector('.message-bubble').textContent = fullResponse;
                            scrollToBottom();
                        }
                    } catch (e) {
                        // 非 JSON 格式的 data，直接作为文本处理
                        if (data && data !== '[DONE]') {
                            fullResponse += data;
                            aiBubbleEl.querySelector('.message-bubble').textContent = fullResponse;
                            scrollToBottom();
                        }
                    }
                }
            }
        }

        // 如果流式未产生任何内容，显示提示
        if (!fullResponse) {
            aiBubbleEl.querySelector('.message-bubble').textContent = '(空回复)';
        }

    } catch (error) {
        if (error.name === 'AbortError') {
            // 用户主动中止，保留已有内容，不弹错误
            removeTypingIndicator();
            if (aiBubbleEl) {
                const bubble = aiBubbleEl.querySelector('.message-bubble');
                if (!bubble.textContent.trim()) {
                    bubble.textContent = '(已中止)';
                }
            }
        } else {
            console.error('SSE 流式请求失败:', error);
            removeTypingIndicator();
            if (!aiBubbleEl) {
                appendMessage('assistant', `连接失败: ${error.message}`);
            } else {
                aiBubbleEl.querySelector('.message-bubble').textContent = `连接失败: ${error.message}`;
            }
            showError(`请求失败: ${error.message}`);
        }
    } finally {
        exitStreamingState();
        chatInput.focus();
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
            messages.forEach(msg => {
                appendMessage(msg.role, msg.content);
            });
        }
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
                    preview.textContent = content;
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

// ===== 页面初始化 =====
document.addEventListener('DOMContentLoaded', async () => {
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
