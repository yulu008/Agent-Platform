/**
 * 存档记忆管理弹窗（当前存档的 GM 笔记本，可增删改查）
 * （rpg-js-split 拆分自 rpg.js）
 *
 * 与 /memory 页面的全局记忆库互不相干：这里操作的是 ~/.agent/rpg-saves/<gameStateId>/ 下的文件。
 *
 * 本文件产出的全局符号：
 *   变量：memoryList, memorySelectedFile, memoryEditing
 *   常量：MEMORY_TYPES
 *   函数：saveMemoryApi, closeMemoryDialog, showMemoryHint, loadSaveMemoryList,
 *         renderMemoryGroups, memoryEmptyNode, appendMemoryGroup, showMemoryDetail,
 *         renderMemoryMeta, memoryBadge, openMemoryForm, suggestMemoryFileName,
 *         showMemoryFormForEdit, saveMemoryFromForm, confirmDeleteMemory, doDeleteMemory
 *   依赖（运行时）：state.js（currentGameStateId）；game.js（showCompactToast）
 */

const MEMORY_TYPES = [
    {type: 'npc_memory', icon: '👤', label: 'NPC 记忆'},
    {type: 'foreshadow', icon: '🪝', label: '伏笔'},
    {type: 'world_lore', icon: '🗺️', label: '世界细节'},
    {type: 'player_style', icon: '🎭', label: '玩家偏好'}
];
let memoryList = [];
let memorySelectedFile = null;
let memoryEditing = false;

function saveMemoryApi() {
    return `/rpg/saves/${encodeURIComponent(currentGameStateId)}/memories`;
}

document.getElementById('memoryMgrBtn').addEventListener('click', async () => {
    if (!currentGameStateId) { showCompactToast('请先开始冒险或读取存档'); return; }
    document.getElementById('memoryScopeHint').textContent =
        `存档 ${currentGameStateId.substring(0, 8)} · ~/.agent/rpg-saves/${currentGameStateId}`;
    document.getElementById('memoryOverlay').hidden = false;
    showMemoryHint();
    await loadSaveMemoryList();
});

document.getElementById('closeMemoryBtn').addEventListener('click', closeMemoryDialog);
document.getElementById('refreshMemoryBtn').addEventListener('click', () => loadSaveMemoryList());
document.getElementById('newMemoryBtn').addEventListener('click', () => openMemoryForm(null));
document.getElementById('cancelMemoryBtn').addEventListener('click', () => {
    // 从表单退回：有选中项回详情，否则回提示态
    if (memorySelectedFile) showMemoryDetail(memorySelectedFile);
    else showMemoryHint();
});
document.getElementById('editMemoryBtn').addEventListener('click', () => showMemoryFormForEdit());
document.getElementById('deleteMemoryBtn').addEventListener('click', () => confirmDeleteMemory());
document.getElementById('memoryConfirmNo').addEventListener('click', () => {
    document.getElementById('memoryConfirmOverlay').hidden = true;
});
document.getElementById('memoryConfirmYes').addEventListener('click', () => doDeleteMemory());
document.getElementById('saveMemoryBtn').addEventListener('click', () => saveMemoryFromForm());

function closeMemoryDialog() {
    document.getElementById('memoryOverlay').hidden = true;
    document.getElementById('memoryConfirmOverlay').hidden = true;
}

/** 右侧回到初始提示态（同时清选中与编辑标记） */
function showMemoryHint() {
    memorySelectedFile = null;
    memoryEditing = false;
    document.getElementById('memoryHint').hidden = false;
    document.getElementById('memoryDetail').hidden = true;
    document.getElementById('memoryForm').hidden = true;
}

async function loadSaveMemoryList() {
    try {
        const res = await fetch(saveMemoryApi());
        if (!res.ok) throw new Error('HTTP ' + res.status);
        memoryList = await res.json();
    } catch (e) {
        memoryList = [];
        renderMemoryGroups(`记忆列表加载失败：${e.message}`);
        return;
    }
    renderMemoryGroups();
}

/** 按四类型分桶渲染（未知类型归入「其他」）；列表为空时渲染提示行 */
function renderMemoryGroups(emptyMessage) {
    const box = document.getElementById('memoryGroups');
    box.innerHTML = '';
    if (memoryList.length === 0) {
        box.appendChild(memoryEmptyNode(
            emptyMessage || '这个存档还没有记忆文件。点「＋ 新建」写第一条。'));
        return;
    }
    const buckets = new Map(MEMORY_TYPES.map(t => [t.type, []]));
    const others = [];
    memoryList.forEach(m => {
        const bucket = buckets.get(m.type);
        if (bucket) bucket.push(m); else others.push(m);
    });
    MEMORY_TYPES.forEach(t => appendMemoryGroup(box, `${t.icon} ${t.label}`, buckets.get(t.type)));
    appendMemoryGroup(box, '📄 其他', others);
}

function memoryEmptyNode(text) {
    const div = document.createElement('div');
    div.className = 'memory-empty';
    div.textContent = text;
    return div;
}

function appendMemoryGroup(box, title, entries) {
    if (!entries || entries.length === 0) return;
    const head = document.createElement('div');
    head.className = 'memory-group-title';
    head.textContent = `${title}（${entries.length}）`;
    box.appendChild(head);
    entries.forEach(entry => {
        const item = document.createElement('div');
        item.className = 'memory-item';
        if (entry.fileName === memorySelectedFile) item.classList.add('active');
        const name = document.createElement('div');
        name.textContent = entry.name || entry.fileName;
        const file = document.createElement('div');
        file.className = 'memory-item-file';
        file.textContent = entry.fileName;
        item.appendChild(name);
        item.appendChild(file);
        item.addEventListener('click', () => showMemoryDetail(entry.fileName));
        box.appendChild(item);
    });
}

async function showMemoryDetail(fileName) {
    try {
        const res = await fetch(`${saveMemoryApi()}/detail?file=${encodeURIComponent(fileName)}`);
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            throw new Error((body && body.error) || ('HTTP ' + res.status));
        }
        const detail = await res.json();
        memorySelectedFile = fileName;
        renderMemoryMeta(detail);
        document.getElementById('memoryContent').textContent = detail.content || '（空）';
        document.getElementById('memoryHint').hidden = true;
        document.getElementById('memoryDetail').hidden = false;
        document.getElementById('memoryForm').hidden = true;
        renderMemoryGroups();   // 同步左侧选中高亮
    } catch (e) {
        showCompactToast(`❌ 读取记忆失败：${e.message}`);
    }
}

function renderMemoryMeta(detail) {
    const meta = document.getElementById('memoryMeta');
    meta.innerHTML = '';
    const typeInfo = MEMORY_TYPES.find(t => t.type === detail.type);
    meta.appendChild(memoryBadge(`${typeInfo ? typeInfo.icon + ' ' : ''}${detail.type || '未分类'}`, true));
    if (detail.name) meta.appendChild(memoryBadge(detail.name, false));
    if (detail.description) meta.appendChild(memoryBadge(detail.description, false));
    meta.appendChild(memoryBadge(detail.fileName, false));
}

function memoryBadge(text, isType) {
    const span = document.createElement('span');
    span.className = isType ? 'memory-badge type' : 'memory-badge';
    span.textContent = text;
    return span;
}

/** entry 为 null 时是新建态；否则为编辑态（文件名锁定，改名等于删除+新建） */
function openMemoryForm(entry) {
    memoryEditing = !!entry;
    const fileInput = document.getElementById('memFileInput');
    fileInput.value = entry ? entry.fileName : suggestMemoryFileName();
    fileInput.readOnly = !!entry;
    document.getElementById('memNameInput').value = entry ? (entry.name || '') : '';
    document.getElementById('memDescInput').value = entry ? (entry.description || '') : '';
    document.getElementById('memTypeInput').value =
        entry && MEMORY_TYPES.some(t => t.type === entry.type) ? entry.type : 'npc_memory';
    document.getElementById('memContentInput').value = entry ? (entry.content || '') : '';
    document.getElementById('memoryHint').hidden = true;
    document.getElementById('memoryDetail').hidden = true;
    document.getElementById('memoryForm').hidden = false;
    document.getElementById('memNameInput').focus();
}

function suggestMemoryFileName() {
    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
    return `note_${stamp}.md`;
}

async function showMemoryFormForEdit() {
    if (!memorySelectedFile) return;
    try {
        const res = await fetch(`${saveMemoryApi()}/detail?file=${encodeURIComponent(memorySelectedFile)}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        openMemoryForm(await res.json());
    } catch (e) {
        showCompactToast(`❌ 读取记忆失败：${e.message}`);
    }
}

async function saveMemoryFromForm() {
    const file = document.getElementById('memFileInput').value.trim();
    const name = document.getElementById('memNameInput').value.trim();
    if (!file) { showCompactToast('❌ 文件名不能为空'); return; }
    if (!name) { showCompactToast('❌ 名称不能为空'); return; }
    const payload = {
        file,
        name,
        description: document.getElementById('memDescInput').value.trim(),
        type: document.getElementById('memTypeInput').value,
        content: document.getElementById('memContentInput').value
    };
    const btn = document.getElementById('saveMemoryBtn');
    btn.disabled = true;
    try {
        const res = await fetch(saveMemoryApi(), {
            method: memoryEditing ? 'PUT' : 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const body = await res.json().catch(() => null);
        if (!res.ok) throw new Error((body && body.error) || ('HTTP ' + res.status));
        memorySelectedFile = file;
        showCompactToast(memoryEditing ? '✅ 记忆已更新' : '✅ 记忆已创建');
        await loadSaveMemoryList();
        await showMemoryDetail(file);   // 保留选中态
    } catch (e) {
        showCompactToast(`❌ 保存失败：${e.message}`);
    } finally {
        btn.disabled = false;
    }
}

function confirmDeleteMemory() {
    if (!memorySelectedFile) return;
    document.getElementById('memoryConfirmText').textContent =
        `确认删除「${memorySelectedFile}」？此操作不可撤销。`;
    document.getElementById('memoryConfirmOverlay').hidden = false;
}

async function doDeleteMemory() {
    document.getElementById('memoryConfirmOverlay').hidden = true;
    const file = memorySelectedFile;
    if (!file) return;
    try {
        const res = await fetch(`${saveMemoryApi()}?file=${encodeURIComponent(file)}`, {method: 'DELETE'});
        if (!res.ok) {
            const body = await res.json().catch(() => null);
            throw new Error((body && body.error) || ('HTTP ' + res.status));
        }
        showCompactToast('🗑️ 记忆已删除');
        memorySelectedFile = null;
        await loadSaveMemoryList();
        showMemoryHint();
    } catch (e) {
        showCompactToast(`❌ 删除失败：${e.message}`);
    }
}
