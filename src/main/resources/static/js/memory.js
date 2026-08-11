/**
 * 记忆管理页面交互逻辑
 * 左侧记忆列表 + 右侧详情展示 + 删除确认
 */

// ===== DOM 元素 =====
const sidebarList = document.getElementById('memorySidebarList');
const detailContainer = document.getElementById('memoryDetail');

// 记忆类型图标映射
const MEMORY_TYPE_ICONS = {
    'user': '☁️',
    'feedback': '🍃',
    'project': '🌿',
    'reference': '🔗'
};

// 当前选中的文件
let selectedFile = null;

// ===== 工具函数 =====
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text || '';
    return div.innerHTML;
}

// ===== 记忆列表加载 =====
async function loadMemoryList() {
    try {
        const res = await fetch('/api/memories');
        if (!res.ok) return;
        const memories = await res.json();
        renderSidebarList(memories);
    } catch (e) {
        console.warn('记忆列表加载失败:', e);
        sidebarList.innerHTML = '<div class="mem-sidebar-empty">加载失败，请刷新重试</div>';
    }
}

function renderSidebarList(memories) {
    if (!memories || memories.length === 0) {
        sidebarList.innerHTML = '<div class="mem-sidebar-empty">还没有记忆<br>与 AI 对话后会自动记住你的偏好</div>';
        showDetailEmpty();
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
        html += `<div class="mem-sidebar-group">${icon} ${type}</div>`;
        items.forEach(m => {
            const name = escapeHtml(m.name || m.fileName || '');
            const desc = escapeHtml(m.description || '');
            const fileName = escapeHtml(m.fileName || '');
            const active = (selectedFile === m.fileName) ? ' active' : '';
            html += `<div class="mem-sidebar-item${active}" data-file="${fileName}">
                <div class="mem-sidebar-item-title">${name}</div>
                <div class="mem-sidebar-item-desc">${desc}</div>
            </div>`;
        });
    }
    sidebarList.innerHTML = html;

    // 绑定点击事件
    sidebarList.querySelectorAll('.mem-sidebar-item').forEach(el => {
        el.addEventListener('click', () => {
            selectedFile = el.dataset.file;
            // 更新高亮
            sidebarList.querySelectorAll('.mem-sidebar-item').forEach(item =>
                item.classList.remove('active'));
            el.classList.add('active');
            loadMemoryDetail(selectedFile);
        });
    });
}

// ===== 记忆详情加载 =====
async function loadMemoryDetail(fileName) {
    try {
        const res = await fetch(`/api/memories/detail?file=${encodeURIComponent(fileName)}`);
        if (!res.ok) {
            showDetailEmpty('加载失败');
            return;
        }
        const detail = await res.json();
        renderDetail(detail);
    } catch (e) {
        console.warn('记忆详情加载失败:', e);
        showDetailEmpty('加载失败');
    }
}

function renderDetail(detail) {
    const type = detail.type || 'other';
    const icon = MEMORY_TYPE_ICONS[type] || '📄';
    const name = escapeHtml(detail.name || detail.fileName || '');
    const desc = escapeHtml(detail.description || '');
    const content = escapeHtml(detail.content || '');
    const fileName = escapeHtml(detail.fileName || '');

    detailContainer.innerHTML = `
        <div class="mem-detail-card">
            <div class="mem-detail-meta">
                <span class="mem-detail-badge type-${type}">${icon} ${escapeHtml(type)}</span>
                <span class="mem-detail-badge">📄 ${fileName}</span>
            </div>
            <div class="mem-detail-name">${name}</div>
            ${desc ? `<div class="mem-detail-desc">${desc}</div>` : ''}
            <div class="mem-detail-content">${content}</div>
            <div class="mem-detail-actions">
                <button class="mem-delete-btn" id="deleteBtn">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    删除此记忆
                </button>
            </div>
        </div>
    `;

    // 绑定删除按钮
    document.getElementById('deleteBtn').addEventListener('click', () => {
        confirmDelete(fileName, name);
    });
}

function showDetailEmpty(msg) {
    const message = msg || '';
    detailContainer.innerHTML = `
        <div class="mem-detail-empty">
            <div class="icon">📝</div>
            <h2>${message || '选择一条记忆查看详情'}</h2>
            <p>记忆由 AI 在对话中自动创建。你可以在这里查看和管理所有跨会话记忆。</p>
        </div>
    `;
}

// ===== 删除确认 =====
function confirmDelete(fileName, name) {
    let dialog = document.getElementById('deleteMemoryDialog');
    if (!dialog) {
        dialog = document.createElement('div');
        dialog.id = 'deleteMemoryDialog';
        dialog.className = 'confirm-dialog';
        dialog.innerHTML = `
            <h3>🗑️ 删除记忆</h3>
            <p>确定删除记忆 "${escapeHtml(name)}" 吗？<br>此操作不可撤销。</p>
            <div class="dialog-actions">
                <button class="btn-cancel" id="memDelCancel">取消</button>
                <button class="btn-confirm" id="memDelConfirm">删除</button>
            </div>
        `;
        document.body.appendChild(dialog);
    }
    dialog.classList.add('visible');

    const cancelBtn = document.getElementById('memDelCancel');
    const confirmBtn = document.getElementById('memDelConfirm');

    const onCancel = () => {
        dialog.classList.remove('visible');
        cleanup();
    };

    const onConfirm = async () => {
        dialog.classList.remove('visible');
        cleanup();
        await doDelete(fileName);
    };

    const cleanup = () => {
        cancelBtn.removeEventListener('click', onCancel);
        confirmBtn.removeEventListener('click', onConfirm);
    };

    cancelBtn.addEventListener('click', onCancel);
    confirmBtn.addEventListener('click', onConfirm);
}

async function doDelete(fileName) {
    try {
        const res = await fetch(`/api/memories?file=${encodeURIComponent(fileName)}`, {
            method: 'DELETE'
        });
        if (!res.ok) throw new Error('删除失败');

        selectedFile = null;
        showDetailEmpty();
        await loadMemoryList();
    } catch (e) {
        // 复用 chat.css 的 error-toast
        const toast = document.createElement('div');
        toast.className = 'error-toast';
        toast.textContent = '删除记忆失败: ' + e.message;
        document.body.appendChild(toast);
        setTimeout(() => toast.remove(), 5000);
    }
}

// ===== 页面初始化 =====
document.addEventListener('DOMContentLoaded', async () => {
    await loadMemoryList();
    if (!selectedFile) {
        showDetailEmpty();
    }
});
