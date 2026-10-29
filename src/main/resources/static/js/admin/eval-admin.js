// 评估中心交互（content-eval-admin-ui tasks 2.x / 3.x / 4.x / 5.x）
// 纯前端消费 /rpg/eval/** 既有端点（批跑触发 / run 跟踪 / 复核终裁），零后端改动；
// 鉴权同 admin.js：AdminAccessInterceptor 保证仅 platform_admin 可达，401 由
// auth-redirect.js 统一跳登录。scope 枚举复用工坊/存档列表端点（design D3）。
(function () {
    'use strict';

    const API = '/rpg/eval';
    const POLL_MS = 15000;      // run 跟踪轮询间隔（design D4）
    const RUNS_LIMIT = 50;
    const QUEUE_LIMIT = 200;

    const $ = (id) => document.getElementById(id);

    // 页签/跟踪状态（evalTabActive 由本文件自挂的 admin-tab listener 维护，
    // 与 admin.js 的 tab 切换 listener 多播共存——admin.js 只管 hidden）
    let evalTabActive = false;
    let loaded = false;         // 懒加载标记（首次进入 eval 页签才拉数据）
    let currentRunId = null;    // 当前选中跟踪的 run
    let lastStatus = null;      // 最近一次详情状态（轮询启停依据）
    let pollTimer = null;
    let worldsLoaded = false;
    let savesLoaded = false;

    // ---------- 通用（对齐 admin.js 惯例） ----------
    function toast(msg, type) {
        const el = $('toast');
        el.textContent = msg;
        el.className = 'toast' + (type ? ' ' + type : '');
        el.hidden = false;
        clearTimeout(toast._t);
        toast._t = setTimeout(() => { el.hidden = true; }, 2600);
    }

    async function api(path, options) {
        const res = await fetch(API + path, options);
        if (!res.ok) {
            let msg = 'HTTP ' + res.status;
            try { const j = await res.json(); if (j && j.error) msg = j.error; } catch (_) { /* 非 JSON */ }
            const err = new Error(msg);
            err.status = res.status;
            throw err;
        }
        return res.json();
    }

    const fmtInt = (v) => (v == null ? '-' : Number(v).toLocaleString('en-US'));
    // 批跑成本以 micro（1e-6 元）落库：转元四位小数（对齐 admin.js fmtYuan 精度惯例）
    const fmtMicro = (v) => (v == null ? '-' : (Number(v) / 1000000).toFixed(4));
    const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g,
        (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const fmtTime = (v) => {
        if (v == null) return '-';
        const d = new Date(v);
        return isNaN(d.getTime()) ? String(v) : d.toLocaleString('zh-CN', { hour12: false });
    };
    // 长短码截断展示（UUID / 指纹 / 分组键）：头 8 + … + 尾 6
    const shortCode = (s) => (s == null ? '-' : (s.length > 20 ? s.slice(0, 8) + '…' + s.slice(-6) : s));
    const cut = (s, n) => (s == null || s === '' ? '-' : (s.length > n ? s.slice(0, n) + '…' : s));

    // ---------- 通用渲染（2.2：run 详情与复核队列共用） ----------
    function verdictBadge(v) {
        if (v === 'pass' || v === 'borderline' || v === 'fail') {
            return '<span class="eval-verdict eval-verdict-' + v + '">' + v + '</span>';
        }
        return '<span class="eval-verdict eval-verdict-invalid">invalid</span>';
    }

    function statusBadge(s) {
        const known = s === 'running' || s === 'done' || s === 'failed';
        return '<span class="eval-status ' + (known ? 'eval-status-' + s : 'eval-verdict-invalid') + '">'
            + esc(s == null ? '-' : s) + '</span>';
    }

    const layerBadge = (l) => '<span class="eval-layer">' + (l === 'static' ? '静态' : l === 'dynamic' ? '动态' : esc(l)) + '</span>';

    // 终裁态：confirmed（人工确认机器判分）/ override（人工推翻，终裁见 final 徽标）
    function reviewBadge(rec) {
        const st = rec.reviewStatus;
        if (st === 'confirmed') {
            return '<span class="eval-review-tag eval-review-confirmed">confirmed</span>';
        }
        if (st === 'override') {
            return '<span class="eval-review-tag eval-review-override">override · ' + esc(rec.reviewFinalVerdict) + '</span>';
        }
        return '<span class="eval-review-tag eval-review-pending">pending</span>';
    }

    // 分歧组短码（人工跨 run 对照锚点；NULL = 无分歧标记）
    const divergeMark = (rec) => (rec.divergenceGroup
        ? '<span class="eval-diverge-code" title="分歧组 ' + esc(rec.divergenceGroup) + '">'
        + esc(rec.divergenceGroup.slice(0, 8)) + '</span>'
        : '<span class="hint">-</span>');

    // record 行展开：判分依据完整字段（缺失跳过不占位）
    function recordExpandHtml(rec) {
        const kv = [];
        if (rec.rationale) kv.push(['judge 理由', esc(rec.rationale)]);
        const ev = parseJson(rec.evidenceJson);
        if (ev != null) kv.push(['证据 span', evidenceHtml(ev)]);
        const unit = parseJson(rec.unitJson);
        if (unit != null) kv.push(['评估单元', '<code>' + esc(JSON.stringify(unit)) + '</code>']);
        if (rec.judgeFingerprint) kv.push(['judge 指纹', '<code>' + esc(rec.judgeFingerprint) + '</code>']);
        if (rec.targetFingerprint) kv.push(['被评产物指纹', '<code>' + esc(rec.targetFingerprint) + '</code>']);
        if (rec.invalidReason) kv.push(['作废原因', esc(rec.invalidReason)]);
        if (rec.reviewStatus && rec.reviewStatus !== 'pending') {
            kv.push(['终裁', esc(rec.reviewStatus) + (rec.reviewFinalVerdict ? ' → ' + esc(rec.reviewFinalVerdict) : '')]);
        }
        if (rec.isRegression) kv.push(['回归集', '已沉淀（后续批跑必含）']);
        if (!kv.length) return '';
        return kv.map((p) => '<div class="kv"><span class="k">' + p[0] + '</span><span class="v">' + p[1] + '</span></div>').join('');
    }

    function parseJson(s) {
        if (s == null || s === '') return null;
        try { return JSON.parse(s); } catch (_) { return null; }
    }

    function evidenceHtml(ev) {
        if (!Array.isArray(ev)) return '<code>' + esc(JSON.stringify(ev)) + '</code>';
        return ev.map((e, i) => '<div>' + (i + 1) + '. ' + esc(e && e.span != null ? e.span : JSON.stringify(e))
            + (e && e.turn != null ? ' <span class="hint">（turn ' + esc(e.turn) + '）</span>' : '') + '</div>').join('');
    }

    // 主行 + 隐藏展开行（点击行尾按钮 toggle）；分歧行加高亮 class。
    // 展开行 id 带表域前缀（detail/queue）：同一 record 可能同时出现在两个表，
    // 裸 recId 做 id 会让两表的展开按钮互串
    function recordRows(rec, mainHtml, colspan, scope) {
        const cls = rec.divergenceGroup ? ' class="eval-diverge"' : '';
        const expand = recordExpandHtml(rec);
        const expandId = scope + '-ex-' + rec.id;
        const btn = expand ? '<button class="btn small ghost" data-expand="' + esc(expandId) + '">依据</button>' : '-';
        return '<tr' + cls + ' data-rec="' + esc(rec.id) + '">' + mainHtml + '<td>' + btn + '</td></tr>'
            + (expand ? '<tr class="eval-record-expand" id="' + esc(expandId) + '" hidden>'
            + '<td colspan="' + colspan + '"><div class="eval-kv">' + expand + '</div></td></tr>' : '');
    }

    // ---------- 批跑触发表单（3.x） ----------
    async function loadWorlds() {
        try {
            const res = await fetch('/rpg/workshop/worlds');
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const worlds = await res.json();
            const sel = $('evalWorldSelect');
            sel.innerHTML = '<option value="">（选择世界）</option>'
                + worlds.map((w) => '<option value="' + esc(w.id) + '">' + esc(w.name || w.id) + '</option>').join('');
            worldsLoaded = true;
        } catch (e) {
            $('evalWorldSelect').innerHTML = '<option value="">（加载失败，点击刷新重试）</option>';
            worldsLoaded = false;
            $('evalTriggerHint').textContent = '世界列表加载失败：' + e.message;
        }
    }

    async function loadSaves() {
        try {
            const res = await fetch('/rpg/game/saves');
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const saves = await res.json();
            const sel = $('evalSaveSelect');
            // SaveCard 字段是 gameStateId / lastPlayedAt（无 id / updatedAt）
            sel.innerHTML = '<option value="">（选择存档）</option>'
                + saves.map((s) => {
                    const label = (s.worldName || '-') + ' · ' + (s.playerCharName || '-')
                        + (s.lastPlayedAt ? ' · ' + cut(String(s.lastPlayedAt).replace('T', ' '), 16) : '');
                    return '<option value="' + esc(s.gameStateId) + '">' + esc(label) + '</option>';
                }).join('');
            savesLoaded = true;
        } catch (e) {
            $('evalSaveSelect').innerHTML = '<option value="">（加载失败，点击刷新重试）</option>';
            savesLoaded = false;
            $('evalTriggerHint').textContent = '存档列表加载失败：' + e.message;
        }
    }

    function onLayerChange() {
        const layer = $('evalLayer').value;
        $('evalWorldRow').hidden = layer !== 'static';
        $('evalSaveRow').hidden = layer !== 'dynamic';
        $('evalTurnsRow').hidden = layer !== 'dynamic';
        if (layer === 'static' && !worldsLoaded) loadWorlds();
        if (layer === 'dynamic' && !savesLoaded) loadSaves();
        updateTriggerState();
    }

    function updateTriggerState() {
        const layer = $('evalLayer').value;
        const scopeOk = layer === 'static' ? $('evalWorldSelect').value !== '' : $('evalSaveSelect').value !== '';
        $('evalRunBtn').disabled = !scopeOk;
        $('evalTriggerHint').textContent = scopeOk ? '' : '请先选择' + (layer === 'static' ? '世界' : '存档');
    }

    async function startRun() {
        const layer = $('evalLayer').value;
        const body = { layer: layer };
        if (layer === 'static') {
            body.worldId = $('evalWorldSelect').value;
        } else {
            body.gameStateId = $('evalSaveSelect').value;
            const turns = Number($('evalWindowTurns').value);
            if ($('evalWindowTurns').value.trim() !== '' && !isNaN(turns) && turns > 0) {
                body.windowTurns = turns;   // 空值不随请求发送（后端缺省窗）
            }
        }
        $('evalRunBtn').disabled = true;
        $('evalRunBtn').textContent = '触发中…';
        try {
            const d = await api('/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            $('evalConfigAlert').hidden = true;
            toast('批跑已受理（run ' + shortCode(d.runId) + '），开始跟踪', 'ok');
            await loadRuns();
            selectRun(d.runId);
        } catch (e) {
            if (e.status === 503) {
                // judge 未配置：常驻提示条透出配置指引原文（design D7，非 toast）；
                // 后端 error 文案自带「内容评估未启用：」前缀，前端不重复叠加
                $('evalConfigAlert').textContent = e.message;
                $('evalConfigAlert').hidden = false;
            } else {
                toast('触发失败：' + e.message, 'err');
            }
        } finally {
            $('evalRunBtn').textContent = '触发批跑';
            updateTriggerState();
        }
    }

    // ---------- run 列表（4.1） ----------
    function scopeText(run) {
        // 列表端直出 EvalRun：scopeJson 为字符串，防御解析（失败原文截短）
        const obj = parseJson(run.scopeJson);
        if (obj && typeof obj === 'object') {
            const parts = Object.keys(obj).map((k) => k + '=' + shortCode(String(obj[k])));
            return parts.length ? parts.join(' ') : '-';
        }
        return cut(run.scopeJson, 24);
    }

    async function loadRuns() {
        $('evalRunsBody').innerHTML = '<tr><td colspan="7" class="empty">加载中…</td></tr>';
        try {
            const runs = await api('/runs?limit=' + RUNS_LIMIT);
            if (!runs.length) {
                $('evalRunsBody').innerHTML = '<tr><td colspan="7" class="empty">暂无批跑 run</td></tr>';
                $('evalRunsMeta').textContent = '0 条';
                return;
            }
            $('evalRunsMeta').textContent = runs.length + ' 条（最近 ' + RUNS_LIMIT + '）';
            $('evalRunsBody').innerHTML = runs.map((r) => {
                const sel = r.id === currentRunId ? ' class="eval-selected"' : '';
                return '<tr' + sel + ' data-run="' + esc(r.id) + '">'
                    + '<td>' + esc(fmtTime(r.createdAt)) + '</td>'
                    + '<td>' + layerBadge(r.layer) + '</td>'
                    + '<td><code>' + esc(scopeText(r)) + '</code></td>'
                    + '<td>' + statusBadge(r.status) + '</td>'
                    + '<td>' + esc(r.judgeModel || '-') + '</td>'
                    + '<td class="num">' + fmtInt(r.costTokens) + '</td>'
                    + '<td class="num">' + fmtMicro(r.costAmountMicro) + '</td>'
                    + '</tr>';
            }).join('');
            $('evalRunsBody').querySelectorAll('tr[data-run]').forEach((tr) => {
                tr.addEventListener('click', () => selectRun(tr.dataset.run));
            });
        } catch (e) {
            $('evalRunsBody').innerHTML = '<tr><td colspan="7" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
        }
    }

    // ---------- run 详情（4.2） ----------
    function selectRun(runId) {
        currentRunId = runId;
        $('evalRunDetail').hidden = false;
        // 折叠态复位（点列表行选中时内容区总是展开的）
        $('evalRunBody').hidden = false;
        $('evalRunClose').textContent = '收起';
        $('evalRunClose').setAttribute('aria-expanded', 'true');
        $('evalRunTitle').textContent = 'run 详情 · ' + shortCode(runId);
        $('evalRunDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
        // 列表选中态同步（不重新拉列表）
        $('evalRunsBody').querySelectorAll('tr[data-run]').forEach((tr) => {
            tr.classList.toggle('eval-selected', tr.dataset.run === runId);
        });
        loadRunDetail();
    }

    async function loadRunDetail() {
        if (!currentRunId) return;
        let qs = '?';
        const verdict = $('evalVerdictFilter').value;
        const subject = $('evalSubjectFilter').value.trim();
        if (verdict) qs += 'verdict=' + encodeURIComponent(verdict) + '&';
        if (subject) qs += 'subject=' + encodeURIComponent(subject);
        qs = qs === '?' ? '' : qs.replace(/&$/, '');
        $('evalRecordsBody').innerHTML = '<tr><td colspan="6" class="empty">加载中…</td></tr>';
        try {
            const d = await api('/runs/' + encodeURIComponent(currentRunId) + qs);
            const prevStatus = lastStatus;
            lastStatus = d.status;
            renderRunMeta(d);
            renderSummary(d.summary);
            renderRecordList(d.records || [], $('evalRecordsBody'), 6, false, 'detail');
            // 轮询启停：running 且页签激活才转（D4）；离开 running/页签切走即停；
            // 轮询刚收尾（上轮 running 本轮终态）时顺带刷新 run 列表，状态列同步离开 running
            if (d.status === 'running' && evalTabActive) {
                ensurePolling();
            } else {
                stopPolling();
                if (prevStatus === 'running' && evalTabActive) loadRuns();
            }
        } catch (e) {
            $('evalRecordsBody').innerHTML = '<tr><td colspan="6" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
            stopPolling();
        }
    }

    function renderRunMeta(d) {
        const scope = (d.scope && typeof d.scope === 'object')
            ? Object.keys(d.scope).map((k) => '<span><b>' + esc(k) + '</b> <code>' + esc(shortCode(String(d.scope[k]))) + '</code></span>').join('')
            : '';
        $('evalRunMeta').innerHTML =
            '<span>层 <b>' + (d.layer === 'static' ? '静态' : '动态') + '</b></span>'
            + '<span>状态 <b>' + statusBadge(d.status) + '</b></span>'
            + '<span>judge <b>' + esc(d.judgeModel || '-') + '</b></span>'
            + '<span>rubric <code>' + esc(shortCode(d.rubricVersion)) + '</code></span>'
            + scope
            + '<span>token <b>' + fmtInt(d.costTokens) + '</b></span>'
            + '<span>金额 <b>' + fmtMicro(d.costAmountMicro) + '</b> 元</span>'
            + '<span>创建 <b>' + esc(fmtTime(d.createdAt)) + '</b></span>'
            + '<span>完成 <b>' + esc(fmtTime(d.finishedAt)) + '</b></span>';
    }

    // summary：分 sub-dimension 的 {pass,borderline,fail} 分布 + 顶层 total/invalid/invalidRatePct
    function renderSummary(summary) {
        const el = $('evalSummary');
        if (!summary || typeof summary !== 'object') {
            el.innerHTML = '<div class="eval-summary-row"><span class="hint">run 进行中或无汇总数据</span></div>';
            return;
        }
        const rows = [];
        Object.keys(summary).forEach((dim) => {
            if (dim === 'total' || dim === 'invalid' || dim === 'invalidRatePct') return;
            const s = (summary[dim] && typeof summary[dim] === 'object') ? summary[dim] : {};
            rows.push('<div class="eval-summary-row">'
                + '<span class="dim">' + esc(dim) + '</span>'
                + '<span class="cnt">pass <b>' + fmtInt(s.pass) + '</b></span>'
                + '<span class="cnt">borderline <b>' + fmtInt(s.borderline) + '</b></span>'
                + '<span class="cnt">fail <b>' + fmtInt(s.fail) + '</b></span>'
                + '</div>');
        });
        if (summary.total != null || summary.invalid != null) {
            rows.push('<div class="eval-summary-row">'
                + '<span class="dim">合计</span>'
                + '<span class="cnt">有效 <b>' + fmtInt(summary.total) + '</b></span>'
                + '<span class="cnt">作废 <b>' + fmtInt(summary.invalid) + '</b>'
                + '（' + (summary.invalidRatePct == null ? '-' : summary.invalidRatePct) + '%）</span>'
                + '</div>');
        }
        el.innerHTML = rows.length ? rows.join('') : '<div class="eval-summary-row"><span class="hint">无汇总数据</span></div>';
    }

    // ---------- 轮询（4.3） ----------
    function ensurePolling() {
        stopPolling();
        if (currentRunId && evalTabActive && lastStatus === 'running') {
            pollTimer = setInterval(loadRunDetail, POLL_MS);
        }
    }

    function stopPolling() {
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    }

    // ---------- 复核工作台（5.x） ----------
    async function loadQueue() {
        $('evalQueueBody').innerHTML = '<tr><td colspan="7" class="empty">加载中…</td></tr>';
        try {
            const items = await api('/review-queue?limit=' + QUEUE_LIMIT);
            $('evalQueueMeta').textContent = items.length + ' 条待复核（borderline + 分歧行）';
            renderRecordList(items, $('evalQueueBody'), 7, true, 'queue');
        } catch (e) {
            $('evalQueueBody').innerHTML = '<tr><td colspan="7" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
        }
    }

    /**
     * record 表渲染（run 详情与复核队列共用，差异由 withReviewControls 区分）：
     * 主行轻列 + 隐藏展开行（依据）；队列行附终裁控件（三档单选 + fail 沉淀勾选）。
     * scope 为表域前缀（detail/queue），供展开行 id 隔离。
     */
    function renderRecordList(records, tbody, colspan, withReviewControls, scope) {
        if (!records.length) {
            tbody.innerHTML = '<tr><td colspan="' + colspan + '" class="empty">无记录</td></tr>';
            return;
        }
        tbody.innerHTML = records.map((rec) => {
            const main = '<td>' + esc(rec.subjectLabel || rec.subject || '-') + '</td>'
                + '<td>' + esc(rec.subDimension || '-') + '</td>'
                + '<td>' + (rec.verdict ? verdictBadge(rec.verdict)
                    : '<span class="eval-verdict eval-verdict-invalid" title="' + esc(rec.invalidReason || '') + '">invalid</span>') + '</td>'
                + '<td title="' + esc(rec.rationale || '') + '">' + esc(cut(rec.rationale, 40)) + '</td>'
                + (withReviewControls
                    ? '<td>' + divergeMark(rec) + '</td><td>' + reviewFormHtml(rec) + '</td>'
                    : '<td>' + reviewBadge(rec) + '</td>');
            return recordRows(rec, main, colspan, scope);
        }).join('');
        // 展开按钮 toggle（data-expand 已带表域前缀的完整展开行 id）
        tbody.querySelectorAll('button[data-expand]').forEach((btn) => {
            btn.addEventListener('click', () => {
                const tr = document.getElementById(btn.dataset.expand);
                if (tr) tr.hidden = !tr.hidden;
            });
        });
        // 终裁控件：radio 切换联动沉淀勾选（仅 fail 可勾）；提交
        if (withReviewControls) {
            tbody.querySelectorAll('.eval-review-form').forEach((form) => {
                const regress = form.querySelector('.eval-regress');
                form.querySelectorAll('input[type="radio"]').forEach((radio) => {
                    radio.addEventListener('change', () => { regress.disabled = radio.value !== 'fail' || !radio.checked; });
                });
                const submitBtn = form.querySelector('button[data-review]');
                submitBtn.addEventListener('click', () => submitReview(submitBtn.dataset.review));
            });
        }
    }

    // 行内终裁表单：机器 verdict 档预选（确认最常见）；regression 仅 fail 可勾（后端 400 兜底）
    function reviewFormHtml(rec) {
        const name = 'evalFinal-' + rec.id;
        const options = ['pass', 'borderline', 'fail'].map((v) => {
            const checked = v === rec.verdict ? ' checked' : '';
            return '<label><input type="radio" name="' + esc(name) + '" value="' + v + '"' + checked + '/> ' + v + '</label>';
        }).join('');
        return '<form class="eval-review-form">'
            + options
            + '<label><input type="checkbox" class="eval-regress" disabled/> 沉淀回归集</label>'
            + '<button type="button" class="btn small" data-review="' + esc(rec.id) + '">提交终裁</button>'
            + '</form>';
    }

    async function submitReview(recordId) {
        const form = document.querySelector('.eval-review-form button[data-review="' + recordId + '"]')
            ? document.querySelector('.eval-review-form button[data-review="' + recordId + '"]').closest('form') : null;
        if (!form) return;
        const checked = form.querySelector('input[type="radio"]:checked');
        if (!checked) { toast('请先选择终裁判定档位', 'err'); return; }
        const body = { finalVerdict: checked.value };
        const regress = form.querySelector('.eval-regress');
        if (checked.value === 'fail' && regress && regress.checked) body.regression = true;
        const btn = form.querySelector('button[data-review]');
        btn.disabled = true;
        try {
            const updated = await api('/records/' + encodeURIComponent(recordId) + '/review', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            // 行内先落终裁态（confirmed/override 徽标替换控件），短暂呈现后整队刷新出队
            const td = form.closest('td');
            td.innerHTML = reviewBadge(updated);
            toast('终裁已收口（' + updated.reviewStatus + (updated.isRegression ? '，已沉淀回归集' : '') + '）', 'ok');
            setTimeout(loadQueue, 1200);
        } catch (e) {
            btn.disabled = false;
            toast('终裁失败：' + e.message, 'err');
        }
    }

    // ---------- 页签懒加载与轮询联动（D4：切走暂停 / 切回恢复） ----------
    document.querySelectorAll('.admin-tab').forEach((btn) => {
        btn.addEventListener('click', () => {
            evalTabActive = btn.dataset.tab === 'eval';
            if (evalTabActive) {
                if (!loaded) {
                    loaded = true;
                    loadRuns();
                    loadQueue();
                    onLayerChange();
                } else {
                    if (currentRunId) loadRunDetail(); else ensurePolling();
                }
            } else {
                stopPolling();
            }
        });
    });

    // ---------- 事件绑定 ----------
    $('evalLayer').addEventListener('change', onLayerChange);
    $('evalWorldSelect').addEventListener('change', updateTriggerState);
    $('evalSaveSelect').addEventListener('change', updateTriggerState);
    $('evalRunBtn').addEventListener('click', startRun);
    $('evalRunsRefresh').addEventListener('click', loadRuns);
    $('evalQueueRefresh').addEventListener('click', loadQueue);
    $('evalRunClose').addEventListener('click', () => {
        // 折叠而非关闭（同 admin.js 租户详情修复）：头部常驻、「展开」入口始终可见；
        // currentRunId 与轮询保留（running run 折叠时仍跟踪，展开即最新），
        // 换 run 点列表行即可
        const collapse = !$('evalRunBody').hidden;
        $('evalRunBody').hidden = collapse;
        $('evalRunClose').textContent = collapse ? '展开' : '收起';
        $('evalRunClose').setAttribute('aria-expanded', String(!collapse));
    });
    $('evalApplyFilter').addEventListener('click', loadRunDetail);
    $('evalSubjectFilter').addEventListener('keydown', (e) => { if (e.key === 'Enter') loadRunDetail(); });
})();
