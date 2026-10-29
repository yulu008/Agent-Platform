/**
 * 策划管线页（add-planner-pipeline tasks 6.1-6.5）：run 列表拉取渲染、进度徽标、
 * 创建向导（载体/风格两维度采集 + 必填校验 + 审查拒答呈现）、视图切换与
 * ?run= / ?create= 深链、run 详情主体（GET /rpg/planner/runs/{runId} 直连渲染
 * 六阶段垂直步骤条——已确认折叠/当前展开/点击切换，跨天恢复真源 manifest；
 * 阶段生成 SSE 渲染（6.4）；L1 编辑门（6.5：S1/S4 Markdown 编辑 · S2 世界观
 * 表单编辑 · 整稿通过 · 回退解锁）；L2 返工门（6.6：S3/S5 候选卡片集逐卡
 * 毙掉/确认/编辑 · 返工窗反馈气泡 v1→vN + 整轮重生成 · 版本树与相邻版本
 * diff · 整稿通过 pending 提示）；S3 批量确认（planner-s3-batch-confirm：
 * 确认全部待定 + 确认并整稿通过组合动作，双入口仅 S3 且 pending>0 渲染）；S4.5 幕级 L2 返工门（story-graph 2.6：
 * 幕卡片单幕返工——反馈 SSE 仅重生本幕不波及他幕，被毙幕返工即救回 ·
 * 已确认幕回退解锁本幕——拼接图失效待重拼）；S6 成稿安装门（6.7：全包只读预览——
 * S1-S5 确认稿懒加载 · 安装物化 + 结果跳转引导世界卡片墙开局）与 run 放弃
 * （spec「run 放弃」非终态可弃）。
 *
 * 数据源 GET /rpg/planner/runs（库表查询 PipelineRun DTO：id/name/sourceMode/
 * styleMode/currentStage/status/createdAt/updatedAt）；503 = 配置开关停用，
 * 渲染停用横幅并隐藏列表区。创建 POST /rpg/planner/runs（422 = 审查拒答
 * {moderation:true,error}，表单内红字呈现；400 = 前后端同语义必填校验）。
 * 详情 GET /rpg/planner/runs/{runId}（stages[]：code/label/gate/status/version/
 * summary/rounds/candidates[]（id/title/status/payload——L2 卡片集渲染源，
 * payload 全量内联零额外请求）+ sourcePreview，spec 跨天恢复渲染）；L1 门
 * 操作 POST /runs/{id}/stages/{code}/draft（{content}→{stage,version}）与
 * /confirm（→{stage,confirmedVersion,nextStage}），回退解锁 POST /unlock；
 * 编辑初始值拉版本快照 GET /versions/{version}（→{content}）；L2 门操作
 * （6.6）逐卡 POST /candidates/{cid}/reject|confirm（→{stage,candidateId,
 * status}）与 /candidates/{cid}（{content}→{version}），返工 POST /rework
 * （{feedback}，SSE 同生成协议——守卫与反馈审查拒答均以 error 事件首包
 * 呈现零副作用）；S4.5 幕级（story-graph 2.6）POST /candidates/{cid}/
 * rework（{feedback}，单幕重生成 SSE 同协议）与 /unlock（→{stage,
 * candidateId,status,graphInvalidated}，拼接图失效待重拼），版本树 GET
 * /versions（→{stage,versions,rounds}）与
 * diff GET /diff?from&to（→{stage,from,to,lines[]}，行级高亮）；S6 安装
 * （6.7）POST /runs/{id}/install（→{worldId,worldName,entryCount}，成功
 * 后 run COMPLETED 与 S6 CONFIRMED，spec planner-world-install 原子物化）。
 * 徽标契约：进度「N/6 阶段名」（currentStage S1..S6 → 序号+中文名）、
 * run 状态徽标（待开始/生成中/待确认/已完成/失败/已放弃，spec 列表徽标场景）。
 *
 * 本文件产出的全局符号：
 *   函数：refreshRuns, renderRunCards, buildRunCard, showPlannerView,
 *         enterRunDetail, backToRunList, formatRunTime,
 *         runStageIndex, runStageLabel, runStatusBadge, runModeTags,
 *         openCreateWizard, closeCreateWizard, submitCreateWizard,
 *         loadRunDetail, renderDetailHead, renderDetailBody,
 *         renderStageSteps, buildStageStep, stageStatusBadge,
 *         stageStatusNote, abandonCurrentRun, startStageGeneration,
 *         renderToolEvent, showStageStreamError, openStageEditor,
 *         closeStageEditor, confirmStageGate, unlockStageGate,
 *         parseWorldSections, serializeWorldSections,
 *         buildCandidateCardSet, buildCandidateCard, rejectCandidateAction,
 *         confirmCandidateAction, openCandidateEditor, saveCandidateEdit,
 *         unlockActAction, toggleActReworkEditor, sendActReworkFeedback,
 *         buildReworkWindow, sendReworkFeedback, toggleStageHistory,
 *         renderStageHistory, showStageVersion, showStageDiff,
 *         toggleFullPackPreview, installPlannerWorld
 *   变量：plannerRunsCache, currentDetailRunId, plannerInstallResult
 * 依赖（加载时）：auth-redirect.js（全局 401 跳登录）、rpg/markdown.js
 * （renderMarkdown：阶段稿/候选卡/版本全文的 Markdown 渲染，rpg-js-split 拆分件）
 */

// ===== 状态 =====
/** 列表 DTO 缓存（深链 ?run= 时从中取 run 渲染详情头；6.3 起详情接口直连） */
let plannerRunsCache = [];

/**
 * 安装结果横幅缓存（tasks 6.7）：跨 loadRunDetail 存活（runId 匹配才渲染），
 * 页面刷新后丢失——用户可从世界卡片墙找到世界，语义可接受。
 */
let plannerInstallResult = null;

/** 详情 stages 缓存（S6 全包预览需读 S1-S5 各阶段版本号，renderDetailBody 时存） */
let currentDetailStages = [];

/** 阶段码 → 中文名（与后端 PipelineStage 枚举同源对齐，前端只做展示映射） */
const RUN_STAGE_NAMES = {
    'S1': 'IP 拆解',
    'S2': '世界观',
    'S3': '人物设定',
    'S4': '剧情结构',
    'S4.5': '故事图骨架',
    'S5': '分支设计',
    'S6': '成稿安装'
};

/** 阶段顺序表（进度徽标「N/7」与进度条宽度；S4.5 为结构层插入阶段，story-graph-authoring 2.1） */
const RUN_STAGE_ORDER = Object.keys(RUN_STAGE_NAMES);

function runStageIndex(stageCode) {
    const idx = RUN_STAGE_ORDER.indexOf(String(stageCode || '')) + 1;
    return idx > 0 ? idx : 1;
}

function runStageLabel(stageCode) {
    return RUN_STAGE_NAMES[stageCode] || '未知阶段';
}

/**
 * 稿件 Markdown 安全渲染（S1/S4 全稿、L2 候选 payload、版本全文与 S6 全包
 * 预览共用）：先转义 < 与 &（稿源含用户编辑，防原始 HTML 标签注入；> 保留
 * 不破坏行首引用语法）再交 renderMarkdown（rpg/markdown.js 简易块级解析）。
 */
function renderDraftMarkdown(text) {
    const escaped = String(text == null ? '' : text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;');
    return renderMarkdown(escaped);
}

/** run 整体状态 → 徽标 {label, cls}（run-badge.status-* 配色见 planner.css） */
function runStatusBadge(status) {
    switch (status) {
        case 'CREATED':   return {label: '待开始', cls: 'status-created'};
        case 'RUNNING':   return {label: '生成中', cls: 'status-running'};
        case 'AWAITING':  return {label: '待确认', cls: 'status-awaiting'};
        case 'COMPLETED': return {label: '已完成', cls: 'status-completed'};
        case 'FAILED':    return {label: '失败', cls: 'status-failed'};
        case 'ABANDONED': return {label: '已放弃', cls: 'status-abandoned'};
        default:          return {label: status || '-', cls: 'status-created'};
    }
}

/** 载体/风格 → 标签行（sourceMode/styleMode 后端码 → 中文短语） */
function runModeTags(run) {
    const tags = [];
    tags.push(run.sourceMode === 'ip_name' ? '🔎 IP 名字' : '📄 贴原文');
    tags.push(run.styleMode === 'remix' ? '自由二创' : '忠实拆解');
    return tags;
}

// ===== 列表拉取与渲染 =====
async function refreshRuns() {
    const disabled = document.getElementById('plannerDisabled');
    const list = document.getElementById('plannerRunList');
    try {
        const res = await fetch('/rpg/planner/runs');
        if (res.status === 503) {
            // 配置开关停用（tasks 2.6 同源语义）：横幅 + 列表区隐藏
            disabled.hidden = false;
            list.hidden = true;
            document.getElementById('plannerCount').hidden = true;
            return;
        }
        if (!res.ok) throw new Error('HTTP ' + res.status);
        disabled.hidden = true;
        list.hidden = false;
        document.getElementById('plannerCount').hidden = false;
        plannerRunsCache = await res.json();
        renderRunCards(plannerRunsCache);
    } catch (e) {
        alert('加载策划列表失败: ' + e.message);
    }
}

function renderRunCards(runs) {
    const list = document.getElementById('plannerRunList');
    list.innerHTML = '';
    if (Array.isArray(runs)) {
        runs.forEach(run => list.appendChild(buildRunCard(run)));
    }
    const count = list.querySelectorAll('.planner-run-card').length;
    document.getElementById('plannerCount').textContent = '共 ' + count + ' 条策划';
    document.getElementById('plannerEmpty').hidden = count > 0;
}

/** 单张 run 卡：名称+状态徽标 / 模式标签 / 进度（N/6 + 细条） / 时间与入口提示 */
function buildRunCard(run) {
    const el = document.createElement('div');
    el.className = 'planner-run-card ' + runStatusBadge(run.status).cls;
    el.dataset.runId = run.id;

    const head = document.createElement('div');
    head.className = 'run-card-head';
    const name = document.createElement('span');
    name.className = 'run-card-name';
    name.textContent = run.name || '未命名策划';
    name.title = name.textContent;
    const badge = document.createElement('span');
    badge.className = 'run-badge ' + runStatusBadge(run.status).cls;
    badge.textContent = runStatusBadge(run.status).label;
    // 硬删除按钮（planner-run-delete tasks 2.1）：与放弃（终态保留可回看）语义互补的彻底清除入口
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'run-card-delete';
    del.title = '彻底删除：清除库表行与全部工作区产物（放弃=保留可回看）';
    del.textContent = '🗑';
    del.addEventListener('click', ev => {
        ev.stopPropagation();   // 防触发卡片点击进入详情
        deleteRunFromCard(run, del);
    });
    head.appendChild(name);
    head.appendChild(badge);
    head.appendChild(del);

    const meta = document.createElement('div');
    meta.className = 'run-card-meta';
    runModeTags(run).forEach(text => {
        const tag = document.createElement('span');
        tag.className = 'run-tag';
        tag.textContent = text;
        meta.appendChild(tag);
    });

    // 进度行：currentStage 序号（S3 → 3/7）；ABANDONED/COMPLETED 保留末位进度语义
    const idx = runStageIndex(run.currentStage);
    const progress = document.createElement('div');
    progress.className = 'run-card-progress';
    const label = document.createElement('span');
    label.className = 'run-progress-label';
    label.textContent = idx + '/' + RUN_STAGE_ORDER.length + ' ' + runStageLabel(run.currentStage);
    const track = document.createElement('div');
    track.className = 'run-progress-track';
    const fill = document.createElement('div');
    fill.className = 'run-progress-fill';
    fill.style.width = Math.round(idx / RUN_STAGE_ORDER.length * 100) + '%';
    track.appendChild(fill);
    progress.appendChild(label);
    progress.appendChild(track);

    const foot = document.createElement('div');
    foot.className = 'run-card-foot';
    const time = document.createElement('span');
    time.textContent = '更新于 ' + formatRunTime(run.updatedAt || run.createdAt);
    const enter = document.createElement('span');
    enter.className = 'run-card-enter';
    enter.textContent = '进入详情 →';
    foot.appendChild(time);
    foot.appendChild(enter);

    el.appendChild(head);
    el.appendChild(meta);
    el.appendChild(progress);
    el.appendChild(foot);
    el.addEventListener('click', () => enterRunDetail(run.id));
    return el;
}

// ===== 视图切换（列表 ↔ 创建向导 ↔ 详情；?create= / ?run= 深链同步，刷新可恢复）=====
function showPlannerView(view) {
    document.getElementById('plannerListView').hidden = view !== 'list';
    document.getElementById('plannerCreateView').hidden = view !== 'create';
    document.getElementById('plannerDetailView').hidden = view !== 'detail';
}

/**
 * 进入 run 详情（列表点击 / ?run= 深链）：列表缓存先行渲染详情头（快速可感），
 * 随后 loadRunDetail 以详情接口为真源重渲染头与步骤条（跨天恢复）。
 * URL 同步 ?run= 便于刷新与分享；缓存未命中（列表未拉/已跨租户删除）回列表。
 */
function enterRunDetail(runId) {
    const run = plannerRunsCache.find(r => r.id === runId);
    if (!run) {
        console.warn('深链 run 不在列表（可能已删除或跨租户）:', runId);
        backToRunList();
        return;
    }
    renderDetailHead(run);
    showPlannerView('detail');
    const url = new URL(location.href);
    // 清 create 参数：initPlanner 中 create 分支优先于 run，残留会让深链刷新错进向导
    url.searchParams.delete('create');
    url.searchParams.set('run', runId);
    history.replaceState(null, '', url);
    loadRunDetail(runId);
}

/** 详情头（名称/状态徽标/模式与进度标签）——列表 DTO 与详情 DTO 字段同构，单点渲染 */
function renderDetailHead(run) {
    document.getElementById('plannerDetailName').textContent = run.name || '未命名策划';
    const badge = document.getElementById('plannerDetailBadge');
    badge.className = 'run-badge ' + runStatusBadge(run.status).cls;
    badge.textContent = runStatusBadge(run.status).label;
    const meta = document.getElementById('plannerDetailMeta');
    meta.innerHTML = '';
    runModeTags(run).forEach(text => {
        const tag = document.createElement('span');
        tag.className = 'run-tag';
        tag.textContent = text;
        meta.appendChild(tag);
    });
    const stageTag = document.createElement('span');
    stageTag.className = 'run-tag';
    stageTag.textContent = '进度 ' + runStageIndex(run.currentStage) + '/' + RUN_STAGE_ORDER.length + ' '
            + runStageLabel(run.currentStage);
    meta.appendChild(stageTag);
    const timeTag = document.createElement('span');
    timeTag.className = 'run-tag';
    timeTag.textContent = '创建于 ' + formatRunTime(run.createdAt);
    meta.appendChild(timeTag);
}

// ===== run 详情主体（tasks 6.3）：详情接口直连 → 源预览卡 + 六阶段步骤条 =====

/** 当前详情 runId（放弃操作/loadRunDetail 重渲染定位） */
let currentDetailRunId = null;

/**
 * 详情拉取（GET /rpg/planner/runs/{runId}）：跨天恢复真源——manifest 各阶段
 * 状态/版本/摘要/返工轮次。404 跨租户红字提示；网络异常红字不弹窗打断。
 */
async function loadRunDetail(runId) {
    currentDetailRunId = runId;
    const err = document.getElementById('plannerDetailError');
    err.hidden = true;
    document.getElementById('plannerStageList').innerHTML = '';
    document.getElementById('plannerSourceCard').hidden = true;
    document.getElementById('plannerAbandonBtn').hidden = true;
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId));
        if (res.status === 404) {
            err.textContent = 'run 不存在或不属于当前租户（可能已被删除）';
            err.hidden = false;
            return;
        }
        if (!res.ok) throw new Error('HTTP ' + res.status);
        renderDetailBody(await res.json());
    } catch (e) {
        err.textContent = '加载 run 详情失败: ' + e.message
                + '（409 = 工作区 manifest 损坏，可联系管理员检查工作区目录）';
        err.hidden = false;
    }
}

/**
 * 详情主体渲染：头重渲染（详情接口为真源，放弃等状态变化即时反映）→
 * 源内容预览卡 → 放弃按钮显隐（非终态可弃）→ 六阶段步骤条。
 */
function renderDetailBody(detail) {
    currentDetailStages = Array.isArray(detail.stages) ? detail.stages : [];
    renderDetailHead(detail);
    const card = document.getElementById('plannerSourceCard');
    const pages = Array.isArray(detail.sourcePages) ? detail.sourcePages : [];
    const urls = Array.isArray(detail.sourceUrls) ? detail.sourceUrls : [];
    if (detail.sourcePreview || pages.length || urls.length) {
        document.getElementById('plannerSourceHead').textContent =
                detail.sourceMode === 'ip_name' ? '🔎 IP 名' : '📄 创作原文';
        if (detail.sourcePreview) {
            document.getElementById('plannerSourcePreview').textContent = detail.sourcePreview;
        }
        card.hidden = false;
    }
    // 资料页优先（planner-html-source D5「资料页 N 份」）；无则回落 sourceUrls
    //（旧 run「扫描网页」回显不破坏，planner-html-source D8 兼容读）
    const urlsEl = document.getElementById('plannerSourceUrls');
    if (pages.length) {
        urlsEl.textContent = '资料页：' + pages.length + ' 份（' + pages.join('、') + '）';
        urlsEl.hidden = false;
    } else {
        urlsEl.textContent = urls.length ? '扫描网页：\n' + urls.join('\n') : '';
        urlsEl.hidden = !urls.length;
    }
    const terminal = detail.status === 'COMPLETED' || detail.status === 'ABANDONED';
    document.getElementById('plannerAbandonBtn').hidden = terminal;
    renderStageSteps(detail);
}

/** 阶段状态 → 步骤条徽标 {label, cls}（stage-st-* 配色见 planner.css） */
function stageStatusBadge(status) {
    switch (status) {
        case 'NOT_STARTED':      return {label: '未开始', cls: 'stage-st-notstarted'};
        case 'RUNNING':          return {label: '生成中', cls: 'stage-st-running'};
        case 'AWAITING_CONFIRM': return {label: '待确认', cls: 'stage-st-awaiting'};
        case 'CONFIRMED':        return {label: '已确认', cls: 'stage-st-confirmed'};
        case 'FAILED':           return {label: '失败', cls: 'stage-st-failed'};
        default:                 return {label: status || '-', cls: 'stage-st-notstarted'};
    }
}

/** 阶段状态的人话说明（骨架态：只述状态，门操作按钮 6.4+ 注入 body） */
function stageStatusNote(stage, isCurrent) {
    switch (stage.status) {
        case 'CONFIRMED':        return '整稿已确认锁定，产物进入后续阶段上下文。';
        case 'RUNNING':          return '生成进行中，请勿关闭页面（跨标签页并发会被 409 拒绝）。';
        case 'AWAITING_CONFIRM': return '草稿就绪（v' + (stage.version || 1) + '），等待你过目确认。';
        case 'FAILED':           return '上一轮生成失败，可重试（已落盘的中间产物会被复用）。';
        case 'NOT_STARTED':
        default:                 return isCurrent ? '等待开始：触发生成后进入草稿流程。' : '尚未解锁。';
    }
}

/**
 * 六阶段垂直步骤条（spec 跨天恢复渲染）：已确认阶段折叠（版本/返工轮次在
 * 头部可见）、当前与待确认/生成中/失败阶段展开、点击头部随时切换。
 * 生成按钮（tasks 6.4）仅当前阶段且未开始/失败态显示（run 终态除外；
 * S6 不经生成端点）。runStatus 用于终态判定。
 */
function renderStageSteps(detail) {
    const list = document.getElementById('plannerStageList');
    list.innerHTML = '';
    detail.stages.forEach(stage => {
        list.appendChild(buildStageStep(stage, detail.currentStage, detail.status));
    });
}

function buildStageStep(stage, currentStage, runStatus) {
    const isCurrent = stage.code === currentStage;
    const st = stageStatusBadge(stage.status);
    const step = document.createElement('div');
    step.className = 'stage-step ' + st.cls + (isCurrent ? ' stage-current' : '');
    step.dataset.stageCode = stage.code;

    // 头部：序号圆 + 名称（L2 门附标签） + 状态徽标/版本/返工轮次/折叠箭头
    const head = document.createElement('div');
    head.className = 'stage-step-head';
    const no = document.createElement('span');
    no.className = 'stage-no';
    no.textContent = String(runStageIndex(stage.code));
    const name = document.createElement('span');
    name.className = 'stage-name';
    name.textContent = stage.label;
    if (stage.gate === 'L2') {
        const gateTag = document.createElement('span');
        gateTag.className = 'stage-gate-tag';
        gateTag.textContent = 'L2 · 逐卡确认';
        name.appendChild(gateTag);
    }
    head.appendChild(no);
    head.appendChild(name);

    const meta = document.createElement('span');
    meta.className = 'stage-head-meta';
    const badge = document.createElement('span');
    badge.className = 'stage-badge';
    badge.textContent = st.label;
    meta.appendChild(badge);
    if (stage.version > 0) {
        const vTag = document.createElement('span');
        vTag.className = 'stage-version-tag';
        vTag.textContent = 'v' + stage.version;
        meta.appendChild(vTag);
    }
    const rounds = Array.isArray(stage.rounds) ? stage.rounds.length : 0;
    if (rounds > 0) {
        const rTag = document.createElement('span');
        rTag.className = 'stage-rounds-tag';
        rTag.textContent = '返工 ' + rounds + ' 轮';
        meta.appendChild(rTag);
    }
    const arrow = document.createElement('span');
    arrow.className = 'stage-arrow';
    arrow.textContent = '▾';
    meta.appendChild(arrow);
    head.appendChild(meta);
    step.appendChild(head);

    // 主体（骨架态）：确认稿/草稿摘要 + 状态说明；门操作 6.4+ 注入
    const body = document.createElement('div');
    body.className = 'stage-step-body';
    if (stage.summary) {
        const summary = document.createElement('p');
        summary.className = 'stage-summary';
        summary.textContent = stage.summary;
        body.appendChild(summary);
    }
    const note = document.createElement('p');
    note.className = 'stage-note';
    note.textContent = stageStatusNote(stage, isCurrent);
    body.appendChild(note);

    // 生成按钮（tasks 6.4）：当前阶段且未开始/失败可触发（重试复用残留产物）；
    // run 终态（已完成/已放弃）与 S6（不经生成端点）不显示
    const terminalRun = runStatus === 'COMPLETED' || runStatus === 'ABANDONED';
    if (isCurrent && !terminalRun && stage.code !== 'S6'
            && (stage.status === 'NOT_STARTED' || stage.status === 'FAILED')) {
        const genBtn = document.createElement('button');
        genBtn.type = 'button';
        genBtn.className = 'stage-generate-btn';
        genBtn.textContent = stage.status === 'FAILED' ? '🔁 重试生成' : '⚡ 生成草稿';
        genBtn.addEventListener('click', () => {
            const runId = currentDetailRunId;
            if (runId) startStageGeneration(runId, stage.code, step);
        });
        body.appendChild(genBtn);
    }

    // L1 门操作（tasks 6.5）：AWAITING_CONFIRM → 编辑草稿 + 整稿通过；
    // CONFIRMED → 回退解锁（spec「锁定后修改须显式回退解锁」，跨级/下游已确认
    // 时后端 409 提示兜底）；S6 只读预览（安装按钮 6.7 注入）；run 终态不提供门操作
    const l1Gate = stage.gate === 'L1' && stage.code !== 'S6';
    if (l1Gate && !terminalRun) {
        if (isCurrent && stage.status === 'AWAITING_CONFIRM') {
            const editBtn = document.createElement('button');
            editBtn.type = 'button';
            editBtn.className = 'stage-gate-btn stage-edit-btn';
            editBtn.textContent = '✏️ 编辑草稿';
            editBtn.addEventListener('click', () => {
                const runId = currentDetailRunId;
                if (runId) openStageEditor(runId, stage, step);
            });
            body.appendChild(editBtn);
            const passBtn = document.createElement('button');
            passBtn.type = 'button';
            passBtn.className = 'stage-gate-btn stage-confirm-btn';
            passBtn.textContent = '✅ 整稿通过';
            passBtn.addEventListener('click', () => {
                const runId = currentDetailRunId;
                if (runId) confirmStageGate(runId, stage);
            });
            body.appendChild(passBtn);
            // S1 链式返工窗（planner-s1-chained-refinement tasks 6.3，spec
            // 「S1 链式返工仅重生汇总轮」）：chained 派生标记（详情 3.3）判断，
            // 复用 L2 返工窗骨架与阶段返工端点——块笔记保留复用仅重生汇总
            // 轮；非链式 run 零增量（不出现返工窗，L1 既有行为不变）
            if (stage.chained) {
                body.appendChild(buildReworkWindow(stage, step, false, {
                    title: '返工窗（仅重生汇总轮——块笔记保留复用，反馈注入汇总成稿产出 v N+1）',
                    placeholder: '不满意拆解稿哪里？写反馈（如：主角动机改写为复仇驱动）；对块笔记内容不满请走「编辑草稿」直接改最终稿'
                }));
            }
        } else if (stage.status === 'CONFIRMED') {
            const unlockBtn = document.createElement('button');
            unlockBtn.type = 'button';
            unlockBtn.className = 'stage-gate-btn stage-unlock-btn';
            unlockBtn.textContent = '🔓 回退解锁';
            unlockBtn.addEventListener('click', () => {
                const runId = currentDetailRunId;
                if (runId) unlockStageGate(runId, stage);
            });
            body.appendChild(unlockBtn);
        }
    }

    // L2 返工门（tasks 6.6）：AWAITING_CONFIRM → 候选卡片集（逐卡毙掉/确认/
    // 编辑）+ 返工窗（反馈气泡 + 整轮重生成）+ 整稿通过（pending 提示禁用，
    // 后端 pending 校验 409 兜底）；CONFIRMED → 只读卡片集 + rounds 反馈
    // 气泡回看 + 回退解锁（非终态；spec「历史可回看」含终态与锁定态）
    if (stage.gate === 'L2') {
        const pending = (Array.isArray(stage.candidates) ? stage.candidates : [])
                .filter(c => c.status === 'PENDING').length;
        if (!terminalRun && isCurrent && stage.status === 'AWAITING_CONFIRM') {
            const cardSet = buildCandidateCardSet(stage, step, false);
            if (cardSet) body.appendChild(cardSet);
            // S3 名册补批（planner-s3-batch-generate tasks 5.1）：rosterRemaining
            // 由详情接口派生（null = 无名册/非 S3，0 = 名册覆盖完备）——补一批
            // （下一批增量）与全量生成（剩余全部跑完）同走 append 语义：新卡
            // id 顺延、既有卡状态不变、版本 +1、不记返工轮（与返工整轮替换
            // 正交；锁定态/剩余空由后端守卫 SSE error 兑底，前端不渲染入口）
            const rosterRemaining = typeof stage.rosterRemaining === 'number'
                    ? stage.rosterRemaining : null;
            const batchCapacity = typeof stage.batchCapacity === 'number'
                    ? stage.batchCapacity : 20;
            if (stage.code === 'S3' && rosterRemaining !== null && rosterRemaining > 0) {
                const appendBtn = document.createElement('button');
                appendBtn.type = 'button';
                appendBtn.className = 'stage-gate-btn stage-append-btn';
                appendBtn.textContent = '＋ 补一批（剩 ' + rosterRemaining + ' 人）';
                appendBtn.addEventListener('click', () => {
                    const runId = currentDetailRunId;
                    if (!runId) return;
                    if (!confirm('补一批：名册剩余 ' + rosterRemaining + ' 人，本批生成约 '
                            + Math.min(batchCapacity, rosterRemaining) + ' 人（批容量 '
                            + batchCapacity + '）。新卡追加到候选集（id 顺延，既有卡状态'
                            + '含已确认/已毙不变），阶段版本 +1，不记返工轮。')) {
                        return;
                    }
                    startStageGeneration(runId, stage.code, step, {
                        url: '/rpg/planner/runs/' + encodeURIComponent(runId)
                                + '/stages/' + encodeURIComponent(stage.code) + '/append-batch',
                        btn: appendBtn,
                        busyText: '补批生成中…',
                        restoreText: '＋ 补一批（剩 ' + rosterRemaining + ' 人）'
                    });
                });
                body.appendChild(appendBtn);
                const fullGenBtn = document.createElement('button');
                fullGenBtn.type = 'button';
                fullGenBtn.className = 'stage-gate-btn stage-fullgen-btn';
                fullGenBtn.textContent = '⚡ 全量生成（剩 ' + rosterRemaining + ' 人）';
                fullGenBtn.addEventListener('click', () => {
                    const runId = currentDetailRunId;
                    if (!runId) return;
                    const estBatches = Math.ceil(rosterRemaining / batchCapacity);
                    if (!confirm('全量生成：名册剩余 ' + rosterRemaining + ' 人，预估 '
                            + estBatches + ' 批（批容量 ' + batchCapacity + '，批间并行）'
                            + '一次跑完。新卡全部追加到候选集（既有卡状态不变），阶段版本 +1。')) {
                        return;
                    }
                    startStageGeneration(runId, stage.code, step, {
                        url: '/rpg/planner/runs/' + encodeURIComponent(runId)
                                + '/stages/' + encodeURIComponent(stage.code) + '/generate-full',
                        btn: fullGenBtn,
                        busyText: '全量生成中…',
                        restoreText: '⚡ 全量生成（剩 ' + rosterRemaining + ' 人）'
                    });
                });
                body.appendChild(fullGenBtn);
            }
            body.appendChild(buildReworkWindow(stage, step, false));
            // S3 批量确认双入口（planner-s3-batch-confirm，spec「S3 批量确认
            // 界面」）：pending>0 时呈现「确认全部待定」与「确认并整稿通过」
            // （批量确认 + 整稿通过原子推进，仅 S3 开放——后端 S4.5/S5 守卫
            // 409 兑底）；pending 清零后零渲染，回落既有「整稿通过」
            if (stage.code === 'S3' && pending > 0) {
                const rejectedCount = (Array.isArray(stage.candidates) ? stage.candidates : [])
                        .filter(c => c.status === 'REJECTED').length;
                const batchBtn = document.createElement('button');
                batchBtn.type = 'button';
                batchBtn.className = 'stage-gate-btn stage-batch-confirm-btn';
                batchBtn.textContent = '✔ 确认全部待定（' + pending + ' 张）';
                batchBtn.addEventListener('click', () => {
                    const runId = currentDetailRunId;
                    if (runId) confirmAllCandidatesAction(runId, stage, pending, rejectedCount, batchBtn);
                });
                body.appendChild(batchBtn);
                const passAllBtn = document.createElement('button');
                passAllBtn.type = 'button';
                passAllBtn.className = 'stage-gate-btn stage-confirm-btn';
                passAllBtn.textContent = '🚀 确认并整稿通过';
                passAllBtn.addEventListener('click', () => {
                    const runId = currentDetailRunId;
                    if (runId) confirmRemainingAndPassAction(runId, stage, pending, passAllBtn);
                });
                body.appendChild(passAllBtn);
            }
            if (pending > 0) {
                const tip = document.createElement('p');
                tip.className = 'stage-note pending-tip';
                tip.textContent = '还有 ' + pending + ' 张待定候选（逐卡确认或毙掉后方可整稿通过）。';
                body.appendChild(tip);
            }
            const passBtn = document.createElement('button');
            passBtn.type = 'button';
            passBtn.className = 'stage-gate-btn stage-confirm-btn';
            passBtn.textContent = '✅ 整稿通过';
            passBtn.disabled = pending > 0;
            passBtn.addEventListener('click', () => {
                const runId = currentDetailRunId;
                if (runId) confirmStageGate(runId, stage);
            });
            body.appendChild(passBtn);
        } else if (stage.status === 'CONFIRMED') {
            // 只读回看态（锁定后或 run 终态）：卡片集/返工历史无门操作；
            // 非终态附回退解锁（锁定态修改唯一路径）
            const cardSet = buildCandidateCardSet(stage, step, true);
            if (cardSet) body.appendChild(cardSet);
            body.appendChild(buildReworkWindow(stage, step, true));
            if (!terminalRun) {
                const unlockBtn = document.createElement('button');
                unlockBtn.type = 'button';
                unlockBtn.className = 'stage-gate-btn stage-unlock-btn';
                unlockBtn.textContent = '🔓 回退解锁';
                unlockBtn.addEventListener('click', () => {
                    const runId = currentDetailRunId;
                    if (runId) unlockStageGate(runId, stage);
                });
                body.appendChild(unlockBtn);
            }
        }
    }

    // 上游已变重算（后端派生 upstreamChanged：上游 CONFIRMED 后版本高于本阶段
    // 生成基线）：一键按上游最新确认稿重新生成本阶段——整轮覆盖当前稿，L2
    // 候选集/逐卡编辑状态重置（与返工互斥：重算响应上游变化，返工带反馈改稿）
    if (!terminalRun && isCurrent && stage.status === 'AWAITING_CONFIRM'
            && stage.upstreamChanged) {
        const regenBtn = document.createElement('button');
        regenBtn.type = 'button';
        regenBtn.className = 'stage-gate-btn stage-regen-btn';
        regenBtn.textContent = '🔄 上游已更新，重算本阶段';
        regenBtn.addEventListener('click', () => {
            const upstreamName = RUN_STAGE_NAMES[stage.upstreamCode] || stage.upstreamCode;
            if (!confirm('上游「' + upstreamName + '」的确认稿已更新。重算将丢弃本阶段当前稿'
                    + '（L2 候选集与逐卡编辑状态整轮重置），按上游最新确认稿重新生成'
                    + '（版本 v' + (stage.version + 1) + '）。确认重算？')) {
                return;
            }
            const runId = currentDetailRunId;
            if (!runId) return;
            startStageGeneration(runId, stage.code, step, {
                url: '/rpg/planner/runs/' + encodeURIComponent(runId)
                        + '/stages/' + encodeURIComponent(stage.code) + '/regenerate',
                busyText: '重算生成中…',
                restoreText: '🔄 重算本阶段'
            });
        });
        body.appendChild(regenBtn);
    }

    // 版本树入口（任意门级，version>0 即渲染含终态）：L2 spec「历史可回看」
    // 全版本可查；L1 spec「原稿可经版本历史回看」——编辑覆盖工作区后旧版
    // 仍可在 UI 核对（数据层 /versions 本就门级无关）
    if (stage.version > 0) {
        const historyBtn = document.createElement('button');
        historyBtn.type = 'button';
        historyBtn.className = 'stage-gate-btn stage-unlock-btn';
        historyBtn.textContent = '📜 版本树与 diff';
        historyBtn.addEventListener('click', () => toggleStageHistory(stage, step));
        body.appendChild(historyBtn);
    }

    // S6 成稿安装（tasks 6.7）：全包只读预览（S1-S5 确认稿懒加载，spec
    // 「S6 只读预览」——确认按钮之外无就地编辑入口，修改须回退解锁上游）
    // + 安装按钮（前置全确认/终态守卫由后端 409 兜底）；安装结果横幅跨
    // loadRunDetail 存活（模块级缓存，runId 匹配才渲染），跳转引导卡片墙开局
    if (stage.code === 'S6') {
        if (plannerInstallResult && plannerInstallResult.runId === currentDetailRunId) {
            const banner = document.createElement('div');
            banner.className = 'install-result-banner';
            const info = document.createElement('span');
            info.textContent = '🎉 安装成功：' + plannerInstallResult.worldName
                    + '（世界书 ' + plannerInstallResult.entryCount + ' 条已物化）';
            const link = document.createElement('a');
            link.href = '/rpg';
            link.className = 'install-result-link';
            link.textContent = '▶ 去世界卡片墙开新局';
            banner.appendChild(info);
            banner.appendChild(link);
            body.appendChild(banner);
        }
        const previewBtn = document.createElement('button');
        previewBtn.type = 'button';
        previewBtn.className = 'stage-gate-btn stage-edit-btn';
        previewBtn.textContent = '📦 全包预览';
        previewBtn.addEventListener('click', () => toggleFullPackPreview(step));
        body.appendChild(previewBtn);
        if (isCurrent && !terminalRun) {
            const installBtn = document.createElement('button');
            installBtn.type = 'button';
            installBtn.className = 'stage-gate-btn stage-install-btn';
            installBtn.textContent = '🚀 安装为世界';
            installBtn.addEventListener('click', () => {
                const runId = currentDetailRunId;
                if (runId) installPlannerWorld(runId, installBtn);
            });
            body.appendChild(installBtn);
        }
    }
    step.appendChild(body);

    // 折叠规则：待确认/生成中/失败/当前阶段默认展开，其余折叠；点击头部切换
    const expanded = isCurrent || stage.status === 'AWAITING_CONFIRM'
            || stage.status === 'FAILED' || stage.status === 'RUNNING';
    step.classList.toggle('expanded', expanded);
    head.addEventListener('click', () => step.classList.toggle('expanded'));

    return step;
}

// ===== 阶段生成 SSE 渲染（tasks 6.4，复用 RPG 流式惯例 game.js 同协议）=====

/**
 * 触发阶段生成流（POST /runs/{runId}/stages/{stageCode}/generate，SSE）：
 * fetch + getReader 手动解析 data: 行（同 game.js 回合流惯例）。事件协议：
 * stage_start（序言，展开阶段即可见）→ content token 逐个追加（pre-wrap）→
 * 工具事件（S1 ip_name 搜索过程可观感，与主聊天/RPG 同协议）→
 * S3 分批事件 batch_start/batch_done（planner-s3-batch-generate 5.1，
 * 补批/全量流的批粒度进度行；并行批 token 交错合流，##切卡不受到达序影响）→
 * draft_ready（重拉详情：AWAITING_CONFIRM/版本/摘要）或 error（红字保留 +
 * 按钮恢复可重试——快速失败零副作用，重拉无意义）→ [DONE]。
 * 生成期间按钮禁用防并发（双开标签页由后端单阶段 RUNNING 守卫 409 语义兜底）。
 * opts（tasks 6.6 返工复用同骨架）：url/body 覆盖（/rework + {feedback}）、
 * btn（发送按钮 DOM）、busyText/restoreText（禁用态与恢复文案）。
 */
async function startStageGeneration(runId, stageCode, step, opts) {
    const o = opts || {};
    // 返工复用：URL/请求体可覆盖（默认走 generate 端点无请求体）
    const url = o.url || ('/rpg/planner/runs/' + encodeURIComponent(runId)
            + '/stages/' + encodeURIComponent(stageCode) + '/generate');
    const init = {method: 'POST'};
    if (o.body) {
        init.headers = {'Content-Type': 'application/json'};
        init.body = JSON.stringify(o.body);
    }
    const btn = o.btn || step.querySelector('.stage-generate-btn');
    const busyText = o.busyText || '生成中…';
    const restoreText = o.restoreText || '🔁 重试生成';
    // 生成中门按钮互锁：发起后禁用本页全部阶段门按钮（跨标签页并发由后端
    // 单阶段 RUNNING 守卫 409 兑底；页面内并点会触发守卫 SSE error 且新流的
    // stream.innerHTML='' 会清掉正在看的输出——run 5ffd6d5b 整轮返工生成中
    // 点全量生成被拒的生产教训）。draft_ready 后 loadRunDetail 重建 DOM
    // 自然解锁；error/中断走 restoreBtn 显式解锁
    const lockedGateBtns = [];
    document.querySelectorAll('.stage-gate-btn').forEach(b => {
        if (b !== btn && !b.disabled) {
            b.disabled = true;
            lockedGateBtns.push(b);
        }
    });
    if (btn) {
        btn.disabled = true;
        btn.textContent = busyText;
    }

    // 流式区（幂等重建；draft_ready 后 loadRunDetail 整体重建步骤条，自然回收）
    let stream = step.querySelector('.stage-stream');
    if (!stream) {
        stream = document.createElement('div');
        stream.className = 'stage-stream';
        step.querySelector('.stage-step-body').appendChild(stream);
    }
    stream.innerHTML = '';
    // 草稿流 div（Markdown 渲染整体重写，对齐 GM 气泡流式惯例；pre 不支持 md 结构）
    const draftBox = document.createElement('div');
    draftBox.className = 'stage-draft-stream';
    stream.appendChild(draftBox);
    step.classList.add('expanded');

    const restoreBtn = text => {
        if (btn) {
            btn.disabled = false;
            btn.textContent = text;
        }
        lockedGateBtns.forEach(b => { b.disabled = false; });
    };

    try {
        const res = await fetch(url, init);
        if (!res.ok) {
            // HTTP 层意外失败（generate/rework 的守卫与审查拒答均走 SSE error
            // 事件，此处仅网络代理等异常兜底）：红字 + 恢复按钮 + 重拉对齐
            const payload = await res.json().catch(() => ({}));
            showStageStreamError(stream, payload && payload.error
                    ? payload.error : 'HTTP ' + res.status);
            restoreBtn(restoreText);
            await loadRunDetail(runId);
            return;
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let draftText = '';   // 草稿累积（md 渲染整体重写，对齐 GM 气泡流式惯例）
        while (true) {
            const {done, value} = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, {stream: true});
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';
            for (const line of lines) {
                if (!line.startsWith('data:')) continue;
                const data = line.slice(5).trim();
                if (data === '[DONE]') continue;
                let parsed;
                try {
                    parsed = JSON.parse(data);
                } catch (e) {
                    continue;
                }
                if (parsed.content) {
                    draftText += parsed.content;
                    draftBox.innerHTML = renderDraftMarkdown(draftText);
                    draftBox.scrollTop = draftBox.scrollHeight;
                } else if (parsed.tool) {
                    renderToolEvent(stream, parsed);
                } else if (parsed.type === 'batch_start') {
                    renderBatchEvent(stream, parsed, true);
                } else if (parsed.type === 'batch_done') {
                    renderBatchEvent(stream, parsed, false);
                } else if (parsed.type === 'draft_ready') {
                    // 成功尾包：重拉详情（AWAITING_CONFIRM/版本/摘要刷新；L2 阶段
                    // 候选数进后绕步骤条 note，逐卡渲染 6.6）
                    await loadRunDetail(runId);
                    return;
                } else if (parsed.error) {
                    // 失败：红字保留可读（配额/守卫/前置快速失败零状态副作用），
                    // 按钮恢复可重试（FAILED 态重试复用残留产物；返工恢复发送）
                    showStageStreamError(stream, parsed.error);
                    restoreBtn(restoreText);
                    return;
                }
            }
        }
        // 流自然结束但未见尾包（网络中断等）：重拉对齐真实状态并恢复按钮
        await loadRunDetail(runId);
    } catch (e) {
        showStageStreamError(stream, '生成连接中断: ' + e.message);
        restoreBtn(restoreText);
        await loadRunDetail(runId);
    }
}

/**
 * 工具事件渲染（S1 ip_name 搜索过程可观感）：start 增状态行（icon+名称+描述），
 * end 收尾同名末行（ok ✓ / error ⚠）。乱序/未知事件静默忽略（事件仅展示用）。
 */
function renderToolEvent(stream, ev) {
    if (ev.tool === 'start') {
        const line = document.createElement('div');
        line.className = 'stage-tool-status running';
        line.dataset.name = ev.name || '';
        line.textContent = (ev.icon || '🔧') + ' ' + (ev.name || '工具')
                + (ev.description ? '：' + ev.description : '');
        stream.appendChild(line);
        return;
    }
    if (ev.tool === 'end') {
        const running = stream.querySelectorAll('.stage-tool-status.running');
        for (let i = running.length - 1; i >= 0; i--) {
            if ((running[i].dataset.name || '') === (ev.name || '')) {
                running[i].classList.remove('running');
                running[i].classList.add(ev.status === 'ok' ? 'done' : 'error');
                running[i].textContent += ev.status === 'ok' ? ' ✓' : ' ⚠';
                return;
            }
        }
    }
}

/**
 * S3 分批事件渲染（planner-s3-batch-generate tasks 5.1）：batch_start 增批
 * 进度行（第 N/M 批 · 批内人数），batch_done 收尾同名末行（done ✓ 已追加 /
 * error ⚠ 失败冻结）。并行批各占一行独立收尾（batch 索引对齐），乱序/未知
 * 序号静默忽略（事件仅展示用，同 renderToolEvent 惯例）。
 */
function renderBatchEvent(stream, ev, isStart) {
    if (isStart) {
        const line = document.createElement('div');
        line.className = 'stage-batch-status running';
        line.dataset.batch = String(ev.index == null ? '' : ev.index);
        line.textContent = '📦 第 ' + (ev.index == null ? '?' : ev.index)
                + '/' + (ev.total == null ? '?' : ev.total)
                + ' 批（' + (ev.count == null ? '?' : ev.count) + ' 人）生成中';
        stream.appendChild(line);
        return;
    }
    const lines = stream.querySelectorAll('.stage-batch-status.running');
    for (let i = lines.length - 1; i >= 0; i--) {
        if (String(lines[i].dataset.batch) === String(ev.index)) {
            lines[i].classList.remove('running');
            lines[i].classList.add(ev.status === 'done' ? 'done' : 'error');
            lines[i].textContent += ev.status === 'done' ? ' ✓ 已追加' : ' ⚠ 失败冻结';
            return;
        }
    }
}

/** 流式区红字（保留可读——重拉详情会重建 DOM，快速失败时用户需看到原因） */
function showStageStreamError(stream, message) {
    const el = document.createElement('p');
    el.className = 'stage-stream-error';
    el.textContent = '⚠️ ' + message;
    stream.appendChild(el);
}

// ===== L1 编辑门（tasks 6.5）：S1/S4 Markdown 编辑 · S2 世界观表单编辑 · 整稿通过 · 回退解锁 =====

/** S2 世界观表单字段集（小节标题是 S6 物化解析键，序列化硬编码不可改） */
const WORLD_SECTIONS = [
    {key: 'name',    title: '名称',       label: '世界名称（卡片墙引用）',            type: 'input'},
    {key: 'era',     title: '时代',       label: '时代背景（纪年锚点与地理格局）',      type: 'textarea'},
    {key: 'mood',    title: '氛围',       label: '基调氛围（供 GM 定叙事口吻）',       type: 'textarea'},
    {key: 'rules',   title: '规则',       label: '世界硬规则（每行一条）',            type: 'textarea'},
    {key: 'desc',    title: '世界描述',   label: '世界正文（供 GM 开局与续写参考）',   type: 'textarea'},
    {key: 'opening', title: '开场白模板', label: '开局旁白（含 {{player_name}} 与 {{constellation}}）', type: 'textarea'}
];

/** 02-world.md 小节名 → 字段键（未识别小节/顶部导语归 extra 原文保留，格式漂移不丢内容） */
const WORLD_SECTION_KEYS = {
    '名称': 'name', '时代': 'era', '氛围': 'mood', '规则': 'rules',
    '世界描述': 'desc', '开场白模板': 'opening', '世界书条目': 'worldbook'
};

/**
 * 解析 02-world.md → 表单字段值（S2 表单编辑的解码器）：按 ## 小节切分，
 * 已知小节归字段，未知小节与顶部导语归 extra（序列化时原样回写，不丢内容）。
 */
function parseWorldSections(content) {
    const fields = {name: '', era: '', mood: '', rules: '', desc: '', opening: '', worldbook: '', extra: ''};
    let target = 'extra';
    for (const line of String(content || '').split('\n')) {
        const m = line.match(/^##\s+(.+?)\s*$/);
        if (m) {
            const key = WORLD_SECTION_KEYS[m[1]];
            if (key) {
                target = key;
                continue;           // 已知小节标题行不进内容
            }
            target = 'extra';       // 未知小节：标题行与后续内容都进 extra
        }
        fields[target] += (fields[target] ? '\n' : '') + line;
    }
    Object.keys(fields).forEach(k => { fields[k] = fields[k].trim(); });
    return fields;
}

/** 表单字段 → 02-world.md（小节标题硬编码；extra 非空时尾部原文回写） */
function serializeWorldSections(fields) {
    let out = '';
    WORLD_SECTIONS.forEach(sec => {
        out += '## ' + sec.title + '\n' + String(fields[sec.key] || '').trim() + '\n\n';
    });
    out += '## 世界书条目\n' + String(fields.worldbook || '').trim() + '\n\n';
    const extra = String(fields.extra || '').trim();
    if (extra) {
        out += extra + '\n';
    }
    return out.trim() + '\n';
}

/**
 * 「✏️ 编辑草稿」：拉最新版本快照全文（编辑初始值）→ 渲染编辑器 →
 * 保存 POST /draft（版本 v(N+1)，原稿可经版本历史回看）或取消。
 * 锁定态 409 正常流程不可达（按钮显隐控制），并发双开标签页可触发（红字提示）。
 */
async function openStageEditor(runId, stage, step) {
    closeStageEditor(step);   // 幂等：已有编辑器先回收
    step.classList.add('stage-editing');   // 隐藏门操作按钮（CSS 控制互斥）
    const body = step.querySelector('.stage-step-body');
    const loading = document.createElement('p');
    loading.className = 'stage-note';
    loading.textContent = '拉取草稿全文…';
    body.appendChild(loading);
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/versions/' + encodeURIComponent(String(stage.version || 1)));
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        loading.remove();
        renderStageEditor(runId, stage, step, data.content || '');
    } catch (e) {
        loading.remove();
        showStageGateError(step, '拉取草稿全文失败: ' + e.message
                + '（版本快照缺失时可在版本历史确认后重试）');
        step.classList.remove('stage-editing');
    }
}

/**
 * 渲染编辑器：S2 世界观表单字段集（解析 → 字段 → 序列化），S1/S4 单
 * textarea Markdown 全稿编辑。卡内保存/取消（不动其他阶段 DOM）；保存成功
 * loadRunDetail 重建步骤条（编辑器自然回收，版本/摘要刷新）。
 */
function renderStageEditor(runId, stage, step, content) {
    const body = step.querySelector('.stage-step-body');
    const editor = document.createElement('div');
    editor.className = 'stage-editor';

    let collect;
    if (stage.code === 'S2') {
        collect = renderWorldForm(editor, parseWorldSections(content));
    } else {
        const label = document.createElement('label');
        label.className = 'world-form-label';
        label.textContent = stage.code === 'S1' ? 'IP 拆解稿（Markdown 全稿）' : '剧情结构稿（Markdown 全稿）';
        editor.appendChild(label);
        const ta = document.createElement('textarea');
        ta.className = 'stage-edit-textarea';
        ta.rows = 14;
        ta.value = content;
        editor.appendChild(ta);
        collect = () => ta.value;
    }

    const errLine = document.createElement('p');
    errLine.className = 'stage-edit-error';
    errLine.hidden = true;
    editor.appendChild(errLine);

    const actions = document.createElement('div');
    actions.className = 'stage-edit-actions';
    const saveBtn = document.createElement('button');
    saveBtn.type = 'button';
    saveBtn.className = 'stage-gate-btn stage-confirm-btn';
    saveBtn.textContent = '💾 保存草稿';
    saveBtn.addEventListener('click', () => saveStageDraft(runId, stage, step, collect, saveBtn, errLine));
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'stage-gate-btn stage-unlock-btn';
    cancelBtn.textContent = '取消';
    cancelBtn.addEventListener('click', () => closeStageEditor(step));
    actions.appendChild(saveBtn);
    actions.appendChild(cancelBtn);
    editor.appendChild(actions);

    body.appendChild(editor);
    step.classList.add('expanded');
}

/** S2 世界观表单字段集渲染；返回收集器（字段值 → serializeWorldSections） */
function renderWorldForm(editor, fields) {
    const inputs = {};
    WORLD_SECTIONS.forEach(sec => {
        const label = document.createElement('label');
        label.className = 'world-form-label';
        label.textContent = sec.label;
        let field;
        if (sec.type === 'input') {
            field = document.createElement('input');
            field.type = 'text';
            field.className = 'stage-edit-input';
        } else {
            field = document.createElement('textarea');
            field.className = 'stage-edit-textarea';
            field.rows = sec.key === 'desc' ? 6 : 3;
        }
        field.value = fields[sec.key] || '';
        inputs[sec.key] = field;
        editor.appendChild(label);
        editor.appendChild(field);
    });
    const wbLabel = document.createElement('label');
    wbLabel.className = 'world-form-label';
    wbLabel.textContent = '世界书条目（### 条目标题 + 关键词 + 正文，Markdown 原文）';
    editor.appendChild(wbLabel);
    const wb = document.createElement('textarea');
    wb.className = 'stage-edit-textarea';
    wb.rows = 8;
    wb.value = fields.worldbook || '';
    editor.appendChild(wb);
    // 未识别小节原文保留（格式漂移防御：仅非空时显示，序列化原样回写）
    let extra = null;
    if (fields.extra) {
        const extraLabel = document.createElement('label');
        extraLabel.className = 'world-form-label';
        extraLabel.textContent = '附加内容（未识别小节原文，保存时原样保留）';
        editor.appendChild(extraLabel);
        extra = document.createElement('textarea');
        extra.className = 'stage-edit-textarea';
        extra.rows = 4;
        extra.value = fields.extra;
        editor.appendChild(extra);
    }
    return () => serializeWorldSections({
        name: inputs.name.value, era: inputs.era.value, mood: inputs.mood.value,
        rules: inputs.rules.value, desc: inputs.desc.value, opening: inputs.opening.value,
        worldbook: wb.value, extra: extra ? extra.value : ''
    });
}

/** 保存草稿（POST /draft）：空白拒绝前端同语义先拦；409 锁定态/守卫红字（重拉对齐） */
async function saveStageDraft(runId, stage, step, collect, saveBtn, errLine) {
    const content = collect();
    if (!content || !content.trim()) {
        errLine.textContent = '⚠️ content 不能为空';
        errLine.hidden = false;
        return;
    }
    saveBtn.disabled = true;
    saveBtn.textContent = '保存中…';
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code) + '/draft', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({content: content})
        });
        if (res.ok) {
            await loadRunDetail(runId);   // 重建步骤条：版本 v(N+1)，编辑器回收
            return;
        }
        const payload = await res.json().catch(() => ({}));
        errLine.textContent = '⚠️ ' + (payload && payload.error ? payload.error : '保存失败 (HTTP ' + res.status + ')');
        errLine.hidden = false;
        if (res.status === 409) {
            await loadRunDetail(runId);   // 锁定/并发态：重拉对齐真实状态
            return;
        }
    } catch (e) {
        errLine.textContent = '⚠️ 保存失败: ' + e.message;
        errLine.hidden = false;
    } finally {
        saveBtn.disabled = false;
        saveBtn.textContent = '💾 保存草稿';
    }
}

/** 移除卡片内编辑器并恢复门操作按钮（取消 / 拉取失败回退） */
function closeStageEditor(step) {
    const editor = step.querySelector('.stage-editor');
    if (editor) editor.remove();
    step.classList.remove('stage-editing');
}

/** 「✅ 整稿通过」（POST /confirm）：锁定为确认稿成为下游唯一输入，currentStage 推进 */
async function confirmStageGate(runId, stage) {
    if (!confirm('整稿通过后本阶段锁定为确认稿（成为下游阶段唯一输入），修改须回退解锁。确认通过？')) return;
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code) + '/confirm', {method: 'POST'});
        if (res.ok) {
            await loadRunDetail(runId);   // 本阶段 CONFIRMED 折叠 + 下一阶段展开
            return;
        }
        const payload = await res.json().catch(() => ({}));
        alert(payload && payload.error ? payload.error : '整稿通过失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);       // 守卫/锁定态 409：重拉对齐
    } catch (e) {
        alert('整稿通过失败: ' + e.message);
    }
}

/** 「🔓 回退解锁」（POST /unlock）：撤回确认回 AWAITING_CONFIRM，原确认版本存档可回看 */
async function unlockStageGate(runId, stage) {
    if (!confirm('回退解锁将撤回本阶段确认状态（currentStage 回退，原确认版本保留存档可回看）。继续？')) return;
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code) + '/unlock', {method: 'POST'});
        if (res.ok) {
            await loadRunDetail(runId);
            return;
        }
        const payload = await res.json().catch(() => ({}));
        // 跨级解锁（下游已确认）409：提示须先回退解锁下游阶段
        alert(payload && payload.error ? payload.error : '回退解锁失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);
    } catch (e) {
        alert('回退解锁失败: ' + e.message);
    }
}

/** 卡内门操作红字（拉取/保存失败；弹窗打断仅意外网络错误时用） */
function showStageGateError(step, message) {
    const el = document.createElement('p');
    el.className = 'stage-stream-error';
    el.textContent = '⚠️ ' + message;
    step.querySelector('.stage-step-body').appendChild(el);
}

// ===== L2 返工门（tasks 6.6）：候选卡片集逐卡操作 · 返工窗 · 版本树与 diff =====

/** 候选状态 → 徽标 {label, cls}（cand-st-* 配色见 planner.css） */
function candidateStatusBadge(status) {
    switch (status) {
        case 'PENDING':   return {label: '待定', cls: 'cand-st-pending'};
        case 'CONFIRMED': return {label: '已确认', cls: 'cand-st-confirmed'};
        case 'REJECTED':  return {label: '已毙', cls: 'cand-st-rejected'};
        default:          return {label: status || '-', cls: 'cand-st-pending'};
    }
}

/**
 * 候选卡片集容器（spec「草稿卡片集」）：标题行（N 张 · M 待定）+ 逐卡渲染。
 * readonly = CONFIRMED 锁定态只读回看（无操作行）；无候选返回 null。
 */
function buildCandidateCardSet(stage, step, readonly) {
    const candidates = Array.isArray(stage.candidates) ? stage.candidates : [];
    if (!candidates.length) return null;
    const pending = candidates.filter(c => c.status === 'PENDING').length;
    const wrap = document.createElement('div');
    wrap.className = 'candidate-card-set';
    const head = document.createElement('p');
    head.className = 'candidate-set-head';
    head.textContent = '候选卡片集（' + candidates.length + ' 张'
            + (pending > 0 ? ' · ' + pending + ' 张待定' : '') + '）'
            + (readonly ? ' · 已锁定只读' : '');
    wrap.appendChild(head);
    candidates.forEach(candidate => wrap.appendChild(
            buildCandidateCard(stage, step, candidate, readonly)));
    return wrap;
}

/**
 * 单张候选卡：标题 + 状态徽标 + payload 预览（点击头部折叠/展开，默认折叠）
 * + 操作行（毙掉/确认/编辑；REJECTED 终态无操作；readonly 锁定态无操作行）。
 * S4.5 幕卡追加幕级操作（story-graph 2.6）：PENDING/REJECTED 幕「返工本幕」
 * （单幕重生成，被毙幕返工即救回）；CONFIRMED 幕「回退解锁本幕」（拼接图
 * 失效待重拼）。payload 全文已随详情内联（零额外请求）；文件缺失（null）
 * 只渲染状态行。
 */
function buildCandidateCard(stage, step, candidate, readonly) {
    const card = document.createElement('div');
    card.className = 'candidate-card ' + candidateStatusBadge(candidate.status).cls;
    card.dataset.candidateId = candidate.id || '';

    const head = document.createElement('div');
    head.className = 'candidate-card-head';
    const title = document.createElement('span');
    title.className = 'candidate-title';
    title.textContent = candidate.title || candidate.id || '候选';
    const badge = document.createElement('span');
    badge.className = 'candidate-status-badge';
    badge.textContent = candidateStatusBadge(candidate.status).label;
    head.appendChild(title);
    head.appendChild(badge);
    card.appendChild(head);

    if (candidate.payload != null) {
        const preview = document.createElement('div');
        preview.className = 'candidate-payload md-body';
        preview.innerHTML = renderDraftMarkdown(candidate.payload);
        preview.hidden = true;   // 默认折叠（卡多不淹没步骤条）
        head.addEventListener('click', () => { preview.hidden = !preview.hidden; });
        card.appendChild(preview);
    } else {
        const miss = document.createElement('p');
        miss.className = 'candidate-payload-miss';
        miss.textContent = '候选文件缺失（返工整轮覆盖窗口），状态行仍有效。';
        card.appendChild(miss);
    }

    // S4.5 幕级返工门（story-graph 2.6）：REJECTED 幕经单幕返工救回（D5
    // 救回通道的单幕具体化）——幕卡片保留返工操作；S3/S5 语义不变（终态无操作）
    const isActGate = stage.code === 'S4.5';
    if (!readonly && (candidate.status !== 'REJECTED' || isActGate)) {
        const actions = document.createElement('div');
        actions.className = 'candidate-actions';
        if (candidate.status !== 'REJECTED') {
            // PENDING 可确认；CONFIRMED 确认后可改毙（spec「候选独立状态」）
            if (candidate.status === 'PENDING') {
                const okBtn = document.createElement('button');
                okBtn.type = 'button';
                okBtn.className = 'stage-gate-btn stage-confirm-btn';
                okBtn.textContent = '✔ 确认';
                okBtn.addEventListener('click', () => {
                    const runId = currentDetailRunId;
                    if (runId) confirmCandidateAction(runId, stage, candidate);
                });
                actions.appendChild(okBtn);
            }
            const killBtn = document.createElement('button');
            killBtn.type = 'button';
            killBtn.className = 'stage-gate-btn stage-reject-btn';
            killBtn.textContent = '✖ 毙掉';
            killBtn.addEventListener('click', () => {
                const runId = currentDetailRunId;
                if (runId) rejectCandidateAction(runId, stage, candidate);
            });
            actions.appendChild(killBtn);
            if (candidate.payload != null) {
                const editBtn = document.createElement('button');
                editBtn.type = 'button';
                editBtn.className = 'stage-gate-btn stage-edit-btn';
                editBtn.textContent = '✏️ 编辑';
                editBtn.addEventListener('click', () => openCandidateEditor(stage, step, candidate, card));
                actions.appendChild(editBtn);
            }
        }
        if (isActGate) {
            // 幕级操作（spec「幕级 L2 返工门」）：已确认幕解锁（拼接图待重拼）；
            // 待定/被毙幕单幕返工（仅重生本幕，不波及其他已确认幕）
            if (candidate.status === 'CONFIRMED') {
                const actUnlockBtn = document.createElement('button');
                actUnlockBtn.type = 'button';
                actUnlockBtn.className = 'stage-gate-btn stage-unlock-btn';
                actUnlockBtn.textContent = '🔓 解锁本幕';
                actUnlockBtn.addEventListener('click', () => {
                    const runId = currentDetailRunId;
                    if (runId) unlockActAction(runId, stage, candidate);
                });
                actions.appendChild(actUnlockBtn);
            } else {
                const actReworkBtn = document.createElement('button');
                actReworkBtn.type = 'button';
                actReworkBtn.className = 'stage-gate-btn stage-rework-btn';
                actReworkBtn.textContent = '🔁 返工本幕';
                actReworkBtn.addEventListener('click', () =>
                        toggleActReworkEditor(stage, step, candidate, card));
                actions.appendChild(actReworkBtn);
            }
        }
        card.appendChild(actions);
    }
    return card;
}

/** 逐卡毙掉（POST /candidates/{cid}/reject）：REJECTED 终态确认弹窗（返工重生成是唯一救回路径；S4.5 幕卡另有单幕返工救回） */
async function rejectCandidateAction(runId, stage, candidate) {
    if (!confirm('毙掉后该候选不再进入本阶段语义稿与版本快照（返工重生成是唯一救回路径）。确定毙掉「'
            + (candidate.title || candidate.id) + '」？')) return;
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/candidates/' + encodeURIComponent(candidate.id) + '/reject',
                {method: 'POST'});
        if (res.ok) {
            await loadRunDetail(runId);   // 卡片状态徽标刷新 + pending 计数联动
            return;
        }
        const payload = await res.json().catch(() => ({}));
        alert(payload && payload.error ? payload.error : '毙掉失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);
    } catch (e) {
        alert('毙掉失败: ' + e.message);
    }
}

/** 逐卡确认（POST /candidates/{cid}/confirm）：PENDING→CONFIRMED 幂等，无弹窗 */
async function confirmCandidateAction(runId, stage, candidate) {
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/candidates/' + encodeURIComponent(candidate.id) + '/confirm',
                {method: 'POST'});
        if (res.ok) {
            await loadRunDetail(runId);
            return;
        }
        const payload = await res.json().catch(() => ({}));
        alert(payload && payload.error ? payload.error : '确认失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);
    } catch (e) {
        alert('确认失败: ' + e.message);
    }
}

/**
 * 批量门操作互锁（planner-s3-batch-confirm，对齐 startStageGeneration 互锁
 * 惯例）：批量请求窗口短（非流式），但同页并点仍须防——禁用本页其余门按钮，
 * 返回被锁列表供 finally 恢复（成功路径 loadRunDetail 重建 DOM 自然回收）。
 */
function lockGateButtons(except) {
    const locked = [];
    document.querySelectorAll('.stage-gate-btn').forEach(b => {
        if (b !== except && !b.disabled) {
            b.disabled = true;
            locked.push(b);
        }
    });
    return locked;
}

/** 批量门操作互锁释放（lockGateButtons 的逆操作，仅恢复本次锁定的按钮） */
function unlockGateButtons(locked) {
    locked.forEach(b => { b.disabled = false; });
}

/**
 * 批量确认（POST /candidates/confirm-all，planner-s3-batch-confirm，spec
 * 「L2 门批量确认」）：全部 PENDING→CONFIRMED（REJECTED 终态不动、幂等）。
 * 数字摘要弹窗（带已毙张数）+ 请求期间门按钮互锁；成功 loadRunDetail
 * 刷新卡片徽标与 pending 计数；失败 alert 守卫文案 + 重拉对齐（409 文案
 * 直接可读，对齐逐卡确认错误处理惯例）。
 */
async function confirmAllCandidatesAction(runId, stage, pending, rejected, btn) {
    if (!confirm('将确认 ' + pending + ' 张待定候选（已毙 ' + rejected
            + ' 张不受影响，确认后仍可改毙）。继续？')) return;
    const lockedBtns = lockGateButtons(btn);
    if (btn) {
        btn.disabled = true;
        btn.textContent = '确认中…';
    }
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code) + '/candidates/confirm-all',
                {method: 'POST'});
        if (res.ok) {
            await loadRunDetail(runId);   // 卡片徽标全 CONFIRMED + pending 提示消失
            return;
        }
        const payload = await res.json().catch(() => ({}));
        alert(payload && payload.error ? payload.error : '批量确认失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);
    } catch (e) {
        alert('批量确认失败: ' + e.message);
    } finally {
        unlockGateButtons(lockedBtns);
        if (btn) {
            btn.disabled = false;
            btn.textContent = '✔ 确认全部待定（' + pending + ' 张）';
        }
    }
}

/**
 * S3 确认并整稿通过（POST /confirm-remaining，planner-s3-batch-confirm，
 * spec「S3 确认并整稿通过」组合动作）：批量确认 + 整稿通过原子化——阶段
 * 直达 CONFIRMED + currentStage 前移，弹窗文案合并锁定语义（对齐既有整稿
 * 通过确认弹窗）；成功 loadRunDetail 走既有 CONFIRMED 折叠 + 下一阶段展开。
 */
async function confirmRemainingAndPassAction(runId, stage, pending, btn) {
    if (!confirm('将确认剩余 ' + pending + ' 张待定候选并整稿通过（本阶段锁定为确认稿，'
            + '成为下游阶段唯一输入，修改须回退解锁）。确认通过？')) return;
    const lockedBtns = lockGateButtons(btn);
    if (btn) {
        btn.disabled = true;
        btn.textContent = '确认中…';
    }
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code) + '/confirm-remaining',
                {method: 'POST'});
        if (res.ok) {
            await loadRunDetail(runId);   // 本阶段 CONFIRMED 折叠 + 下一阶段展开
            return;
        }
        const payload = await res.json().catch(() => ({}));
        alert(payload && payload.error ? payload.error : '确认并整稿通过失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);
    } catch (e) {
        alert('确认并整稿通过失败: ' + e.message);
    } finally {
        unlockGateButtons(lockedBtns);
        if (btn) {
            btn.disabled = false;
            btn.textContent = '🚀 确认并整稿通过';
        }
    }
}

/**
 * 逐卡编辑器（POST /candidates/{cid} body {content}）：初始值即内联 payload
 * （零额外请求）；单卡覆盖写 + 版本递增 + 语义稿快照（后端聚合非毙候选）。
 * 保存成功 loadRunDetail 重建（卡片集刷新 payload/版本）；REJECTED 不可编辑
 * （按钮不渲染，后端 409 兜底）。
 */
function openCandidateEditor(stage, step, candidate, card) {
    closeCandidateEditor(card, step);   // 幂等：同卡已有编辑器先回收
    step.classList.add('stage-editing');   // 隐藏门操作按钮（CSS 互斥，同 6.5）
    const editor = document.createElement('div');
    editor.className = 'stage-editor candidate-editor';
    const label = document.createElement('label');
    label.className = 'world-form-label';
    label.textContent = (candidate.title || candidate.id) + '（Markdown 全文，覆盖保存）';
    editor.appendChild(label);
    const ta = document.createElement('textarea');
    ta.className = 'stage-edit-textarea';
    ta.rows = 12;
    ta.value = candidate.payload || '';
    editor.appendChild(ta);
    const errLine = document.createElement('p');
    errLine.className = 'stage-edit-error';
    errLine.hidden = true;
    editor.appendChild(errLine);
    const actions = document.createElement('div');
    actions.className = 'stage-edit-actions';
    const saveBtn = document.createElement('button');
    saveBtn.type = 'button';
    saveBtn.className = 'stage-gate-btn stage-confirm-btn';
    saveBtn.textContent = '💾 保存候选';
    saveBtn.addEventListener('click', () =>
            saveCandidateEdit(stage, candidate, ta, saveBtn, errLine));
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'stage-gate-btn stage-unlock-btn';
    cancelBtn.textContent = '取消';
    cancelBtn.addEventListener('click', () => closeCandidateEditor(card, step));
    actions.appendChild(saveBtn);
    actions.appendChild(cancelBtn);
    editor.appendChild(actions);
    card.appendChild(editor);
    ta.focus();
}

/** 保存候选编辑：空白前端先拦；成功/409 均重拉（卡片集与版本刷新） */
async function saveCandidateEdit(stage, candidate, ta, saveBtn, errLine) {
    const runId = currentDetailRunId;
    if (!runId) return;
    const content = ta.value;
    if (!content || !content.trim()) {
        errLine.textContent = '⚠️ content 不能为空';
        errLine.hidden = false;
        return;
    }
    saveBtn.disabled = true;
    saveBtn.textContent = '保存中…';
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/candidates/' + encodeURIComponent(candidate.id), {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({content: content})
        });
        if (res.ok) {
            await loadRunDetail(runId);
            return;
        }
        const payload = await res.json().catch(() => ({}));
        errLine.textContent = '⚠️ ' + (payload && payload.error
                ? payload.error : '保存失败 (HTTP ' + res.status + ')');
        errLine.hidden = false;
        if (res.status === 409) {
            await loadRunDetail(runId);   // 锁定/REJECTED 并发态：重拉对齐
            return;
        }
    } catch (e) {
        errLine.textContent = '⚠️ 保存失败: ' + e.message;
        errLine.hidden = false;
    } finally {
        saveBtn.disabled = false;
        saveBtn.textContent = '💾 保存候选';
    }
}

/** 移除卡内候选编辑器并恢复门操作按钮（取消 / 保存后重拉自然回收） */
function closeCandidateEditor(card, step) {
    const editor = card.querySelector('.candidate-editor');
    if (editor) editor.remove();
    if (step) step.classList.remove('stage-editing');
}

/**
 * 幕级回退解锁（story-graph 2.6，spec「已确认幕显式回退解锁」：POST
 * /candidates/{cid}/unlock）：CONFIRMED 幕复位 PENDING 可编辑/返工，拼接图
 * 删除（下游衔接跨幕边待重拼——重新整稿通过时确定性重拼），其他幕不动。
 */
async function unlockActAction(runId, stage, candidate) {
    if (!confirm('回退解锁「' + (candidate.title || candidate.id)
            + '」？拼接图将标记待重拼（重新整稿通过时重拼），其他幕状态不变。')) return;
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/candidates/' + encodeURIComponent(candidate.id) + '/unlock',
                {method: 'POST'});
        if (res.ok) {
            await loadRunDetail(runId);   // 幕卡片状态复位 PENDING 刷新
            return;
        }
        const payload = await res.json().catch(() => ({}));
        alert(payload && payload.error ? payload.error : '幕级解锁失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);
    } catch (e) {
        alert('幕级解锁失败: ' + e.message);
    }
}

/**
 * 幕级返工反馈编辑器（story-graph 2.6，spec「单幕返工不波及他幕」：POST
 * /candidates/{cid}/rework SSE）：卡片内内联反馈输入（复用候选编辑器互斥形
 * 态——stage-editing 隐藏门操作按钮）；发送仅重生本幕（非目标幕跳过，其他
 * 已确认幕状态不动）；被毙幕经此通道救回。再点返工按钮收起（幂等）。
 */
function toggleActReworkEditor(stage, step, candidate, card) {
    if (card.querySelector('.candidate-editor')) {
        closeCandidateEditor(card, step);   // 再点收起（含同卡编辑器互斥）
        return;
    }
    step.classList.add('stage-editing');   // 隐藏门操作按钮（CSS 互斥，同 6.5）
    const editor = document.createElement('div');
    editor.className = 'stage-editor candidate-editor';
    const label = document.createElement('label');
    label.className = 'world-form-label';
    label.textContent = '返工「' + (candidate.title || candidate.id)
            + '」（仅重生本幕，其他幕不受影响）';
    editor.appendChild(label);
    const ta = document.createElement('textarea');
    ta.className = 'stage-edit-textarea';
    ta.rows = 3;
    ta.placeholder = '本幕哪里不满意？（如：锚点拍子与主线不符，重设锚点）';
    editor.appendChild(ta);
    const errLine = document.createElement('p');
    errLine.className = 'stage-edit-error';
    errLine.hidden = true;
    editor.appendChild(errLine);
    const actions = document.createElement('div');
    actions.className = 'stage-edit-actions';
    const sendBtn = document.createElement('button');
    sendBtn.type = 'button';
    sendBtn.className = 'stage-gate-btn stage-rework-btn';
    sendBtn.textContent = '📨 发送本幕反馈';
    sendBtn.addEventListener('click', () =>
            sendActReworkFeedback(stage, step, candidate, ta, sendBtn, errLine));
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'stage-gate-btn stage-unlock-btn';
    cancelBtn.textContent = '取消';
    cancelBtn.addEventListener('click', () => closeCandidateEditor(card, step));
    actions.appendChild(sendBtn);
    actions.appendChild(cancelBtn);
    editor.appendChild(actions);
    card.appendChild(editor);
    ta.focus();
}

/** 发送幕级返工反馈：POST /candidates/{cid}/rework {feedback} SSE（复用生成骨架，幕级事件带 act 归属） */
async function sendActReworkFeedback(stage, step, candidate, ta, sendBtn, errLine) {
    const runId = currentDetailRunId;
    if (!runId) return;
    const feedback = ta.value.trim();
    if (!feedback) {
        errLine.textContent = '⚠️ 反馈不能为空';
        errLine.hidden = false;
        return;
    }
    errLine.hidden = true;
    await startStageGeneration(runId, stage.code, step, {
        url: '/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/candidates/' + encodeURIComponent(candidate.id) + '/rework',
        body: {feedback: feedback},
        btn: sendBtn,
        busyText: '本幕生成中…',
        restoreText: '📨 发送本幕反馈'
    });
}

/**
 * 返工窗（spec「返工窗」）：rounds 反馈气泡（v1→vN 历史，跨天恢复真源
 * manifest）+ 反馈 textarea + 发送。发送走 /rework SSE（记账 + 整轮重生成，
 * 流式骨架与生成共用——反馈审查拒答/守卫失败以 error 事件首包呈现零副作用）。
 * readonly（CONFIRMED/终态回看）：只渲染 rounds 气泡，无输入区与发送。
 * opts（planner-s1-chained-refinement tasks 6.3）：S1 链式返工窗复用时定制
 * title/placeholder 文案（「仅重生汇总轮」语义），缺省走 L2 通用文案。
 */
function buildReworkWindow(stage, step, readonly, opts) {
    const o = opts || {};
    const win = document.createElement('div');
    win.className = 'rework-window';
    const head = document.createElement('p');
    head.className = 'candidate-set-head';
    head.textContent = readonly
            ? '返工历史（反馈气泡 v1→vN，只读回看）'
            : (o.title || '返工窗（发送反馈整轮重新生成候选，旧候选文件被覆盖）');
    win.appendChild(head);
    const rounds = Array.isArray(stage.rounds) ? stage.rounds : [];
    rounds.forEach(r => {
        const bubble = document.createElement('div');
        bubble.className = 'rework-bubble';
        const meta = document.createElement('span');
        meta.className = 'rework-bubble-meta';
        meta.textContent = 'v' + (r.round || '?') + ' · ' + formatRunTime(r.at);
        const text = document.createElement('span');
        text.textContent = r.feedback || '';
        bubble.appendChild(meta);
        bubble.appendChild(text);
        win.appendChild(bubble);
    });
    if (readonly) return win;
    const ta = document.createElement('textarea');
    ta.className = 'stage-edit-textarea';
    ta.rows = 3;
    ta.placeholder = o.placeholder || '不满意哪里？写反馈（如：人物一动机不足，换一个孤城老守卫的设定）';
    win.appendChild(ta);
    const errLine = document.createElement('p');
    errLine.className = 'stage-edit-error';
    errLine.hidden = true;
    win.appendChild(errLine);
    const actions = document.createElement('div');
    actions.className = 'stage-edit-actions';
    const sendBtn = document.createElement('button');
    sendBtn.type = 'button';
    sendBtn.className = 'stage-gate-btn stage-rework-btn';
    sendBtn.textContent = '📨 发送反馈';
    sendBtn.addEventListener('click', () =>
            sendReworkFeedback(stage, step, ta, sendBtn, errLine));
    actions.appendChild(sendBtn);
    win.appendChild(actions);
    return win;
}

/** 发送返工反馈：POST /rework {feedback} SSE（复用 startStageGeneration 骨架） */
async function sendReworkFeedback(stage, step, ta, sendBtn, errLine) {
    const runId = currentDetailRunId;
    if (!runId) return;
    const feedback = ta.value.trim();
    if (!feedback) {
        errLine.textContent = '⚠️ 反馈不能为空';
        errLine.hidden = false;
        return;
    }
    errLine.hidden = true;
    // 反馈保留在 textarea（error 时可改后重发；draft_ready 后重拉自然回收）
    await startStageGeneration(runId, stage.code, step, {
        url: '/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code) + '/rework',
        body: {feedback: feedback},
        btn: sendBtn,
        busyText: '返工生成中…',
        restoreText: '📨 发送反馈'
    });
}

/**
 * 版本树面板开关（📜 按钮）：GET /versions 拉全部版本号与返工轮历史，
 * 展示版本 chip 行 + 相邻版本 diff（缺省最新两版）+ 版本全文回看。
 * 只读端点任意阶段状态可看（spec「历史可回看」锁定态不例外）；再点收起。
 */
function toggleStageHistory(stage, step) {
    let panel = step.querySelector('.stage-history');
    if (panel) {
        panel.remove();
        return;
    }
    panel = document.createElement('div');
    panel.className = 'stage-history';
    step.querySelector('.stage-step-body').appendChild(panel);
    renderStageHistory(stage, panel);
}

async function renderStageHistory(stage, panel) {
    const runId = currentDetailRunId;
    if (!runId) return;
    const loading = document.createElement('p');
    loading.className = 'stage-note';
    loading.textContent = '拉取版本历史…';
    panel.appendChild(loading);
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code) + '/versions');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();   // {stage, versions, rounds}
        loading.remove();
        const versions = Array.isArray(data.versions) ? data.versions : [];
        if (!versions.length) {
            const empty = document.createElement('p');
            empty.className = 'stage-note';
            empty.textContent = '暂无版本（本阶段尚未生成草稿）。';
            panel.appendChild(empty);
            return;
        }
        const chips = document.createElement('div');
        chips.className = 'history-version-chips';
        versions.forEach(v => {
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'history-chip' + (v === versions[versions.length - 1] ? ' latest' : '');
            chip.textContent = 'v' + v;
            chip.addEventListener('click', () => {
                // 选中态随点击切换（latest 只是「最新版」徽标，不是当前查看项——
                // 否则点 v1/v2 高亮恒在最新版，误以为回看失效）
                markHistoryActive(panel, chip);
                showStageVersion(stage, panel, v);
            });
            chips.appendChild(chip);
        });
        // 最新两版 diff 快捷入口（与缺省展示同对）
        if (versions.length >= 2) {
            const diffBtn = document.createElement('button');
            diffBtn.type = 'button';
            diffBtn.className = 'history-diff-btn';
            diffBtn.textContent = '🔍 最新两版 diff';
            diffBtn.addEventListener('click', () => {
                markHistoryActive(panel, diffBtn);
                showStageDiff(stage, panel,
                        versions[versions.length - 2], versions[versions.length - 1]);
            });
            chips.appendChild(diffBtn);
            // 缺省 diff 即当前视图：diff 入口初始选中
            diffBtn.classList.add('active');
        }
        panel.appendChild(chips);
        // 缺省 diff 最新两版（不足两版只提供版本全文回看）
        if (versions.length >= 2) {
            await showStageDiff(stage, panel,
                    versions[versions.length - 2], versions[versions.length - 1]);
        }
    } catch (e) {
        loading.remove();
        const err = document.createElement('p');
        err.className = 'stage-stream-error';
        err.textContent = '⚠️ 版本历史加载失败: ' + e.message;
        panel.appendChild(err);
    }
}

/** 版本全文回看（GET /versions/{v} → {content}，替换面板内容区） */
async function showStageVersion(stage, panel, version) {
    const runId = currentDetailRunId;
    if (!runId) return;
    clearHistoryContent(panel);
    // 版本全文（Markdown 渲染：稿件是 md 结构，标题/列表/引用成型可读；
    // 加载/失败态仍是纯文本提示，不进 md 管线）
    const content = document.createElement('div');
    content.className = 'history-content md-body';
    content.textContent = '加载 v' + version + ' 全文…';
    panel.appendChild(content);
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/versions/' + encodeURIComponent(String(version)));
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        content.innerHTML = renderDraftMarkdown(data.content || '（空内容）');
    } catch (e) {
        content.textContent = '加载失败: ' + e.message;
    }
}

/** 相邻版本 diff（GET /diff?from&to → 行级 removed/added 高亮，spec「diff 核对」） */
async function showStageDiff(stage, panel, from, to) {
    const runId = currentDetailRunId;
    if (!runId) return;
    clearHistoryContent(panel);
    const head = document.createElement('p');
    head.className = 'history-diff-head';
    head.textContent = 'diff：v' + from + ' → v' + to;
    panel.appendChild(head);
    const box = document.createElement('div');
    box.className = 'history-diff';
    box.textContent = '计算 diff…';
    panel.appendChild(box);
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                + '/stages/' + encodeURIComponent(stage.code)
                + '/diff?from=' + encodeURIComponent(from) + '&to=' + encodeURIComponent(to));
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();   // {stage, from, to, lines: [{type, text}]}
        box.textContent = '';
        const lines = Array.isArray(data.lines) ? data.lines : [];
        if (!lines.length) {
            box.textContent = '（无差异行或空版本）';
            return;
        }
        lines.forEach(l => {
            const line = document.createElement('div');
            line.className = 'diff-line diff-' + (l.type || 'context');
            line.textContent = (l.type === 'removed' ? '− ' : l.type === 'added' ? '+ ' : '  ')
                    + (l.text || '');
            box.appendChild(line);
        });
    } catch (e) {
        box.textContent = 'diff 加载失败: ' + e.message;
    }
}

/** 切换版本面板选中态（chips 与 diff 入口互斥单选；latest 徽标不受影响） */
function markHistoryActive(panel, el) {
    panel.querySelectorAll('.history-chip, .history-diff-btn')
            .forEach(b => b.classList.toggle('active', b === el));
}

/** 清空版本面板内容区（chips 保留，供切换版本全文/diff 视图） */
function clearHistoryContent(panel) {
    panel.querySelectorAll('.history-content, .history-diff-head, .history-diff')
            .forEach(el => el.remove());
}

// ===== S6 成稿安装（tasks 6.7，spec planner-confirm-gates「S6 只读预览」 + planner-world-install） =====

/**
 * 全包只读预览开关（📦 按钮）：并行拉 S1-S5 当前版本快照（CONFIRMED 后
 * version 不再变，当前版本即确认稿）只读呈现；再点收起。S6 无自身产物。
 */
async function toggleFullPackPreview(step) {
    const runId = currentDetailRunId;
    if (!runId) return;
    let panel = step.querySelector('.stage-preview');
    if (panel) {
        panel.remove();
        return;
    }
    panel = document.createElement('div');
    panel.className = 'stage-preview';
    step.querySelector('.stage-step-body').appendChild(panel);
    renderFullPackPreview(runId, panel);
}

async function renderFullPackPreview(runId, panel) {
    const head = document.createElement('p');
    head.className = 'candidate-set-head';
    head.textContent = '全包预览（S1-S5 确认稿只读，修改须回退解锁对应阶段）';
    panel.appendChild(head);
    const sections = (currentDetailStages || [])
            .filter(s => s.code !== 'S6' && s.version > 0);
    if (!sections.length) {
        const empty = document.createElement('p');
        empty.className = 'stage-note';
        empty.textContent = '暂无可预览的确认稿（前置阶段尚未生成草稿）。';
        panel.appendChild(empty);
        return;
    }
    const loading = document.createElement('p');
    loading.className = 'stage-note';
    loading.textContent = '拉取 S1-S5 确认稿全文…';
    panel.appendChild(loading);
    try {
        const contents = await Promise.all(sections.map(s =>
                fetch('/rpg/planner/runs/' + encodeURIComponent(runId)
                        + '/stages/' + encodeURIComponent(s.code)
                        + '/versions/' + encodeURIComponent(String(s.version)))
                        .then(r => r.ok ? r.json()
                                : Promise.reject(new Error('HTTP ' + r.status)))));
        loading.remove();
        sections.forEach((s, i) => {
            const label = document.createElement('p');
            label.className = 'preview-label';
            label.textContent = s.label + '（v' + s.version + '）';
            const content = document.createElement('div');
            content.className = 'preview-content md-body';
            content.innerHTML = renderDraftMarkdown(
                    (contents[i] && contents[i].content) || '（空内容）');
            panel.appendChild(label);
            panel.appendChild(content);
        });
    } catch (e) {
        loading.remove();
        const err = document.createElement('p');
        err.className = 'stage-stream-error';
        err.textContent = '⚠️ 全包预览加载失败: ' + e.message;
        panel.appendChild(err);
    }
}

/**
 * 「🚀 安装为世界」（POST /install）：S1-S5 确认稿物化为新世界（spec
 * planner-world-install「安装物化」：每次安装独立 world_id 不覆盖；原子
 * 成功才置 run COMPLETED，失败零残留可重试）。成功后结果横幅经模块缓存
 * 跨 loadRunDetail 存活，引导世界卡片墙开局。
 */
async function installPlannerWorld(runId, installBtn) {
    if (!confirm('安装将把 S1-S5 确认稿物化为新世界（每次安装产出独立 world_id，不覆盖既有世界；安装后本管线完成）。确认安装？')) return;
    installBtn.disabled = true;
    installBtn.textContent = '安装中…';
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId) + '/install',
                {method: 'POST'});
        if (res.ok) {
            const result = await res.json();   // {worldId, worldName, entryCount}
            plannerInstallResult = {
                runId: runId,
                worldId: result.worldId,
                worldName: result.worldName,
                entryCount: result.entryCount
            };
            await loadRunDetail(runId);   // run COMPLETED + S6 CONFIRMED，横幅经缓存渲染
            return;
        }
        const payload = await res.json().catch(() => ({}));
        // 409：前置未全确认/终态不可装/产物不完整（守卫文案直接可读）
        alert(payload && payload.error ? payload.error : '安装失败 (HTTP ' + res.status + ')');
        await loadRunDetail(runId);
    } catch (e) {
        alert('安装失败: ' + e.message);
    } finally {
        installBtn.disabled = false;
        installBtn.textContent = '🚀 安装为世界';
    }
}

// ===== run 放弃（spec「run 放弃」：非终态可弃，工作区文件树保留可回看） =====

async function abandonCurrentRun() {
    const runId = currentDetailRunId;
    if (!runId) return;
    if (!confirm('确定放弃此管线？工作区产物将保留可回看，但管线不可再推进。')) return;
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(runId) + '/abandon',
                {method: 'POST'});
        if (res.status === 204) {
            await loadRunDetail(runId);   // 头/徽标/按钮即时更新（已放弃态）
            return;
        }
        if (res.status === 409) {
            alert('该 run 已处于终态，无需放弃');
            await loadRunDetail(runId);
            return;
        }
        throw new Error('HTTP ' + res.status);
    } catch (e) {
        alert('放弃失败: ' + e.message);
    }
}

document.getElementById('plannerAbandonBtn').addEventListener('click', abandonCurrentRun);

// ===== run 硬删除（planner-run-delete spec「run 硬删除」：库表行 + 工作区全清，与放弃互补） =====

/** 列表卡片🗑：confirm 二次确认（文案显式对比放弃语义）→ DELETE → 刷新列表与计数。 */
async function deleteRunFromCard(run, btn) {
    if (!run || !run.id) return;
    const label = run.name || '未命名策划';
    if (!confirm('彻底删除「' + label + '」？\n' +
            '库表行与全部工作区产物（拆解稿、版本快照、搜索资料）将一并清除，不可恢复。\n' +
            '（放弃 = 保留可回看；删除 = 彻底清除）')) return;
    if (btn) btn.disabled = true;
    try {
        const res = await fetch('/rpg/planner/runs/' + encodeURIComponent(run.id),
                {method: 'DELETE'});
        if (res.status === 204) {
            await refreshRuns();   // 列表与计数即时刷新
            return;
        }
        // 409 生成中 / 404 已不存在（他端并发删过）：提示后刷新对齐现状
        if (res.status === 409 || res.status === 404) {
            const payload = await res.json().catch(() => ({}));
            alert(payload && payload.error ? payload.error : '删除失败 (HTTP ' + res.status + ')');
            await refreshRuns();
            return;
        }
        throw new Error('HTTP ' + res.status);
    } catch (e) {
        alert('删除失败: ' + e.message);
    } finally {
        if (btn) btn.disabled = false;
    }
}

/** 返回列表：清 ?run= 参数并刷新（放弃等状态变化经列表接口回读） */
function backToRunList() {
    showPlannerView('list');
    const url = new URL(location.href);
    url.searchParams.delete('run');
    history.replaceState(null, '', url);
    refreshRuns();
}

document.getElementById('plannerBackBtn').addEventListener('click', backToRunList);

// ===== 创建向导（tasks 6.2）：载体/风格两维度 → POST /rpg/planner/runs =====
/** 载体 radio 切换：text 显示原文 textarea，ip_name 显示 IP 名输入框 */
document.querySelectorAll('input[name="plannerSourceMode"]').forEach(radio => {
    radio.addEventListener('change', () => syncSourceModeInputs());
});
function syncSourceModeInputs() {
    const mode = document.querySelector('input[name="plannerSourceMode"]:checked').value;
    document.getElementById('plannerTextInput').hidden = mode !== 'text';
    document.getElementById('plannerIpNameInput').hidden = mode !== 'ip_name';
    // 切换载体即切换必填语义：旧模式的校验红字随之过期，清除防残留误导
    hideCreateError();
}

function openCreateWizard() {
    hideCreateError();
    showPlannerView('create');
    const url = new URL(location.href);
    url.searchParams.set('create', '1');
    history.replaceState(null, '', url);
}

/** 返回列表：清 ?create= 并刷新（创建成功/取消后状态可见） */
function closeCreateWizard() {
    showPlannerView('list');
    const url = new URL(location.href);
    url.searchParams.delete('create');
    history.replaceState(null, '', url);
    refreshRuns();
}

document.getElementById('plannerNewBtn').addEventListener('click', openCreateWizard);
document.getElementById('plannerCreateBackBtn').addEventListener('click', closeCreateWizard);
document.getElementById('plannerCreateCancelBtn').addEventListener('click', closeCreateWizard);

document.getElementById('plannerCreateSubmitBtn').addEventListener('click', submitCreateWizard);

// ===== .md 文件读入与字数提示（planner-s1-chained-refinement tasks 6.1/6.2）=====
/** S1 链式阈值与创建硬上限（与后端 RpgPlannerProperties chainThresholdChars/
 *  maxSourceChars 默认值同源对齐，前端仅轻提示，后端 400 单点兜底） */
const S1_CHAIN_THRESHOLD_CHARS = 10000;
const S1_MAX_SOURCE_CHARS = 500000;

/** 字数提示（tasks 6.2）：空→隐藏；超硬上限→红字 + over-limit（拦截预告）；
 *  超链式阈值→预告多轮链式提炼；否则纯字数（与后端分叉判定同语义） */
function updateTextCountHint() {
    const hint = document.getElementById('plannerTextCountHint');
    const len = document.getElementById('plannerText').value.length;
    if (!len) {
        hint.hidden = true;
        hint.classList.remove('over-limit');
        return;
    }
    if (len > S1_MAX_SOURCE_CHARS) {
        hint.hidden = false;
        hint.classList.add('over-limit');
        hint.textContent = '⚠️ ' + len + ' 字超出 50 万字硬上限，提交将被拒绝——请拆分为多个 run 分别创建';
        return;
    }
    hint.classList.remove('over-limit');
    hint.hidden = false;
    hint.textContent = len > S1_CHAIN_THRESHOLD_CHARS
            ? len + ' 字：将进入多轮链式提炼（分块 → 逐块笔记 → 汇总成稿）'
            : len + ' 字';
}
document.getElementById('plannerText').addEventListener('input', updateTextCountHint);

/** 读入 .md 文件（tasks 6.1）：FileReader 以 utf-8 读文本灌入原文 textarea——
 *  与粘贴共用校验链（客户端读入无服务端上传面），文件名回显 + 字数提示刷新 */
document.getElementById('plannerMdFileBtn').addEventListener('click', () => {
    document.getElementById('plannerMdFile').click();
});
document.getElementById('plannerMdFile').addEventListener('change', () => {
    const fileInput = document.getElementById('plannerMdFile');
    const file = fileInput.files && fileInput.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
        document.getElementById('plannerText').value = String(reader.result || '');
        const nameEl = document.getElementById('plannerMdFileName');
        nameEl.textContent = '已读入：' + file.name;
        nameEl.hidden = false;
        updateTextCountHint();
    };
    reader.onerror = () => showCreateError('读取 .md 文件失败：' + file.name);
    reader.readAsText(file, 'utf-8');
    fileInput.value = '';   // 清空以使同名文件重选仍触发 change
});

// ===== HTML 资料页选择与 chip 管理（planner-html-source tasks 6.1/6.2）=====
/** 资料页前端校验常量（与后端 RpgPlannerProperties maxSourcePages/
 *  maxPageBytes 默认值同源对齐，前端轻拦截省一次上传往返，后端单点兜底） */
const PLANNER_MAX_SOURCE_PAGES = 5;
const PLANNER_MAX_PAGE_BYTES = 2 * 1024 * 1024;   // 2MB

/** 已选资料页（File 对象数组，ip_name 模式提交面）；切换载体不清空——切回仍在 */
let plannerPageFiles = [];

/** 文件大小人话（chip 内「N KB / N MB」） */
function formatPageSize(bytes) {
    if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return Math.max(1, Math.round(bytes / 1024)) + ' KB';
}

/** 重渲染 chip 列表（名称 + 大小 + ✕ 移除）与计数徽标 */
function renderPageChips() {
    const wrap = document.getElementById('plannerPageChips');
    wrap.innerHTML = '';
    plannerPageFiles.forEach((file, idx) => {
        const chip = document.createElement('span');
        chip.className = 'wizard-page-chip';
        const name = document.createElement('span');
        name.className = 'wizard-page-chip-name';
        name.textContent = file.name;
        const size = document.createElement('span');
        size.className = 'wizard-page-chip-size';
        size.textContent = formatPageSize(file.size);
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'wizard-page-chip-remove';
        remove.textContent = '✕';
        remove.title = '移除 ' + file.name;
        remove.addEventListener('click', () => {
            plannerPageFiles.splice(idx, 1);
            renderPageChips();
        });
        chip.append(name, size, remove);
        wrap.appendChild(chip);
    });
    const count = document.getElementById('plannerPageCount');
    count.textContent = plannerPageFiles.length
            ? '已选 ' + plannerPageFiles.length + '/' + PLANNER_MAX_SOURCE_PAGES + ' 份'
            : '';
}

document.getElementById('plannerPageFilesBtn').addEventListener('click', () => {
    document.getElementById('plannerPageFiles').click();
});
document.getElementById('plannerPageFiles').addEventListener('change', () => {
    const fileInput = document.getElementById('plannerPageFiles');
    if (!fileInput.files || !fileInput.files.length) return;
    const invalid = [];
    const oversize = [];
    for (const file of fileInput.files) {
        // 扩展名白名单（与后端 400 同语义；accept 已限但代码路径兜底）
        if (!/\.(html|htm)$/i.test(file.name)) {
            invalid.push(file.name);
            continue;
        }
        if (file.size > PLANNER_MAX_PAGE_BYTES) {
            oversize.push(file.name);
            continue;
        }
        // 同名同大小去重（重选覆盖）+ 总数上限：超出的入 invalid 提示口径
        const exists = plannerPageFiles.findIndex(f => f.name === file.name && f.size === file.size);
        if (exists >= 0) {
            plannerPageFiles[exists] = file;
        } else if (plannerPageFiles.length >= PLANNER_MAX_SOURCE_PAGES) {
            showCreateError('HTML 资料页最多 ' + PLANNER_MAX_SOURCE_PAGES
                    + ' 份（已满，请先移除再选）');
            break;
        } else {
            plannerPageFiles.push(file);
        }
    }
    if (invalid.length) {
        showCreateError('资料页须为 .html/.htm 文件（浏览器另存 HTML）: ' + invalid.join('、'));
    } else if (oversize.length) {
        showCreateError('HTML 资料页单份超过大小上限（2MB）: ' + oversize.join('、'));
    } else {
        // 本批全部合格：清除上次提交遗留的红字（如「资料页不能为空」）防误导
        hideCreateError();
    }
    renderPageChips();
    fileInput.value = '';   // 清空以使同名文件重选仍触发 change
});

/** 表单内红字提示（必填校验/400/413/422 审查拒答；弹窗打断仅在意外网络错误时） */
function showCreateError(msg) {
    const el = document.getElementById('plannerCreateError');
    el.textContent = msg;
    el.hidden = false;
}
function hideCreateError() {
    const el = document.getElementById('plannerCreateError');
    el.textContent = '';
    el.hidden = true;
}

/**
 * 提交创建（planner-html-source D4 multipart）：前端必填与资料页校验（与后端
 * 同语义，spec 必填校验场景：text 模式原文必填 / ip_name 模式 IP 名 + 资料页
 * ≥1 份、≤5 份、单份 ≤2MB）→ FormData（表单字段 + pages 文件数组）→ 422 审查
 * 拒答红字呈现（spec 创建输入过内容审查场景：拒答且零副作用不创建 run）→
 * 400/413 红字 → 成功刷列表并进详情占位。
 */
async function submitCreateWizard() {
    hideCreateError();
    const sourceMode = document.querySelector('input[name="plannerSourceMode"]:checked').value;
    const styleMode = document.querySelector('input[name="plannerStyleMode"]:checked').value;
    const text = document.getElementById('plannerText').value.trim();
    const ipName = document.getElementById('plannerIpName').value.trim();
    const name = document.getElementById('plannerName').value.trim();

    if (sourceMode === 'text' && !text) {
        showCreateError('贴原文模式下原文不能为空');
        document.getElementById('plannerText').focus();
        return;
    }
    // 硬上限前端拦截（tasks 6.2，与后端 maxSourceChars 400 同语义）：省一次
    // 网络往返与审查调用；字数提示行已红字预告此处文案
    if (sourceMode === 'text' && text.length > S1_MAX_SOURCE_CHARS) {
        showCreateError('原文 ' + text.length + ' 字超出 50 万字硬上限（配置 rpg.planner.max-source-chars），请拆分为多个 run 分别创建');
        document.getElementById('plannerText').focus();
        return;
    }
    if (sourceMode === 'ip_name' && !ipName) {
        showCreateError('IP 名字模式下 IP 名不能为空');
        document.getElementById('plannerIpName').focus();
        return;
    }
    // 资料页前端校验（与后端 400/413 同语义；选择时已逐文件拦截，此处总量与空校验兜底）
    if (sourceMode === 'ip_name') {
        if (!plannerPageFiles.length) {
            showCreateError('ip_name 模式下 HTML 资料页不能为空（至少 1 份，请上传浏览器另存的 HTML）');
            document.getElementById('plannerPageFilesBtn').focus();
            return;
        }
        if (plannerPageFiles.length > PLANNER_MAX_SOURCE_PAGES) {
            showCreateError('HTML 资料页最多 ' + PLANNER_MAX_SOURCE_PAGES + ' 份（当前 '
                    + plannerPageFiles.length + ' 份，配置 rpg.planner.max-source-pages）');
            return;
        }
        const oversize = plannerPageFiles.find(f => f.size > PLANNER_MAX_PAGE_BYTES);
        if (oversize) {
            showCreateError('HTML 资料页单份超过大小上限（rpg.planner.max-page-bytes）: ' + oversize.name);
            return;
        }
    }

    // multipart 表单（创建端点统一 multipart，text 模式字段同构无文件面）：
    // 不设 Content-Type，boundary 由浏览器自动生成
    const form = new FormData();
    form.append('sourceMode', sourceMode);
    form.append('styleMode', styleMode);
    if (sourceMode === 'text') {
        form.append('text', text);
    } else {
        form.append('ipName', ipName);
        plannerPageFiles.forEach(file => form.append('pages', file, file.name));
    }
    if (name) form.append('name', name);

    const btn = document.getElementById('plannerCreateSubmitBtn');
    btn.disabled = true;
    btn.textContent = '创建中…';
    try {
        const res = await fetch('/rpg/planner/runs', {method: 'POST', body: form});
        if (res.ok) {
            const run = await res.json();
            // 刷新列表缓存后进详情（深链 ?run= 同口径；详情主体 6.3 填充）
            await refreshRuns();
            enterRunDetail(run.id);
            return;
        }
        const body = await res.json().catch(() => ({}));
        // 审查拒答（422 + {moderation:true,error}）：话术红字呈现，输入保留可改后重提
        if (body && body.moderation && body.error) {
            showCreateError('⚠️ ' + body.error);
            return;
        }
        // 后端同语义必填校验（400）与其余错误：红字呈现
        showCreateError(body && body.error ? body.error : '创建失败 (HTTP ' + res.status + ')');
    } catch (e) {
        alert('创建失败: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = '创建管线 →';
    }
}

/** 相对时间（跨天恢复场景「N 天前」语义；同 conversations.js formatSaveTime 惯例） */
function formatRunTime(iso) {
    if (!iso) return '-';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '-';
    const min = Math.floor((Date.now() - d.getTime()) / 60000);
    if (min < 1) return '刚刚';
    if (min < 60) return min + ' 分钟前';
    const hour = Math.floor(min / 60);
    if (hour < 24) return hour + ' 小时前';
    const day = Math.floor(hour / 24);
    if (day < 30) return day + ' 天前';
    return d.toLocaleDateString('zh-CN');
}

// ===== 页面初始化：默认落列表；?create= 进向导、?run= 进详情（spec 跨天恢复入口）=====
(async function initPlanner() {
    await refreshRuns();
    const params = new URLSearchParams(location.search);
    if (params.get('create')) {
        openCreateWizard();
        return;
    }
    const runId = params.get('run');
    if (runId) enterRunDetail(runId);
})();
