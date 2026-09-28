/**
 * Markdown 渲染与 state_delta 过滤（rpg-js-split 拆分自 rpg.js）
 *
 * 本文件产出的全局符号：
 *   常量：GENERATING_HTML
 *   函数：inlineMarkdown, renderMarkdown, filterStateDelta,
 *         showGenerating, settlePlaceholder, gmToolLabel, showToolStatus
 */

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

// state_delta 过滤（客户端二次过滤，服务端流式已过滤一次）
function filterStateDelta(text) {
    const s = String(text == null ? '' : text);
    // 1) 完整闭合块（容忍标签内空白变体，如 < state_delta >）
    let out = s.replace(/<\s*state_delta\s*>[\s\S]*?<\s*\/\s*state_delta\s*>/gi, '');
    // 2) 未闭合的尾部块：GM 漏写闭合标签 / 流式尚未收到闭合标签时，
    //    从开标签起整体截断，避免 JSON 状态数据闪现或残留
    //    （浏览器会把开标签当未知 HTML 标签隐藏，但块内 JSON 仍会渲染）
    out = out.replace(/<\s*state_delta\s*>[\s\S]*$/i, '');
    return out;
}

// ===== 【生成中】占位（纯前端生命周期，不动后端 SSE 协议）=====
const GENERATING_HTML = '<span class="rpg-generating">【生成中】</span>';

/**
 * 流式开始前写入占位，避免等待期出现空白气泡。
 * 首个内容 chunk 到达时，既有的 `innerHTML = renderMarkdown(...)` 赋值会天然覆盖它。
 */
function showGenerating(el) {
    if (el) el.innerHTML = GENERATING_HTML;
}

/**
 * 流终止后的兜底：一个内容 chunk 都没收到时（narrationBuffer 仍为空），
 * 占位必须换成终态文案，否则页面永远停在「生成中」。
 * 占位已被内容或上一次终态文案替换时本函数是 no-op，故可在 catch 与正常结束处重复调用。
 */
function settlePlaceholder(el, text, isError = false) {
    if (!el || narrationBuffer) return;
    if (!el.querySelector('.rpg-generating')) return;
    el.innerHTML = '';
    const span = document.createElement('span');
    span.className = isError ? 'rpg-error' : 'rpg-settled';
    span.textContent = text;
    el.appendChild(span);
}

// ===== GM 工具调用进度提示（SSE {"tool":"start","name":…} 事件驱动） =====

/** 工具名 → 可读标签：记忆类 / 查询类 / 兜底 */
function gmToolLabel(name) {
    if (!name) return '⚙️ 处理中…';
    if (name.startsWith('GmMemory')) return '📝 整理记忆中…';
    if (name.startsWith('get_')) return '🔍 查询世界状态…';
    return '⚙️ ' + name + '…';
}

/**
 * 在 GM 气泡显示工具调用状态。
 * - 尚无叙述内容：直接替换【生成中】占位（首个内容 chunk 到达时整体覆盖）；
 * - 已有叙述：在气泡尾部追加/更新状态行 —— 叙述后的静默期（如模型连续写
 *   记忆文件）正是本提示要解决的“输出完了还在执行”体感问题；
 * - 状态行随内容事件对 innerHTML 的整体重写自然消失，回合结束时由
 *   sendTurn 兜底清除，不会残留到终态气泡。
 */
function showToolStatus(el, name) {
    if (!el) return;
    if (!narrationBuffer) {
        el.innerHTML = '';
        const span = document.createElement('span');
        span.className = 'rpg-generating rpg-tool-status';
        span.textContent = gmToolLabel(name);
        el.appendChild(span);
        return;
    }
    let status = el.querySelector('.rpg-tool-status');
    if (!status) {
        status = document.createElement('span');
        status.className = 'rpg-generating rpg-tool-status';
        el.appendChild(status);
    }
    status.textContent = gmToolLabel(name);
}
