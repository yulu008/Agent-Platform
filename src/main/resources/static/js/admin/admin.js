// 管理端交互（tenant-token-metering / tasks 8.5）
// 用量看板 + 租户分解/预算 + 档位单价维护。所有接口在 /api/admin/metering 下，
// 由 AdminAccessInterceptor 保证仅 platform_admin 可达；401 由 auth-redirect.js 统一跳登录。
(function () {
    'use strict';

    const API = '/api/admin/metering';

    const $ = (id) => document.getElementById(id);
    let currentTenant = null;   // 当前展开详情的 tenantId
    let currentPage = 0;        // 明细分页
    const PAGE_SIZE = 20;

    // ---------- 通用 ----------
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
            throw new Error(msg);
        }
        return res.json();
    }

    const fmtInt = (v) => (v == null ? '-' : Number(v).toLocaleString('en-US'));
    const fmtYuan = (v) => (v == null ? '-' : Number(v).toFixed(4));
    const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g,
        (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    // ---------- Tab 切换 ----------
    document.querySelectorAll('.admin-tab').forEach((btn) => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.admin-tab').forEach((b) => b.classList.remove('active'));
            btn.classList.add('active');
            const tab = btn.dataset.tab;
            $('panel-usage').hidden = tab !== 'usage';
            $('panel-pricing').hidden = tab !== 'pricing';
            if (tab === 'pricing') loadPricing();
        });
    });

    // ---------- 用量看板 ----------
    async function loadUsage() {
        const days = $('windowDays').value;
        $('usageBody').innerHTML = '<tr><td colspan="7" class="empty">加载中…</td></tr>';
        try {
            const data = await api('/usage?windowDays=' + days);
            $('windowMeta').textContent = '窗口 ' + data.windowDays + ' 天 · 起 ' + data.fromDay;
            $('quotaHint').textContent = data.quotaEnabled
                ? '配额硬拒：已开启（默认预算 ' + data.defaultBudgetYuan + ' 元）'
                : '配额硬拒：未开启（仅计量）· 默认预算 ' + data.defaultBudgetYuan + ' 元';
            const rows = data.tenants || [];
            if (!rows.length) {
                $('usageBody').innerHTML = '<tr><td colspan="7" class="empty">窗口内暂无用量</td></tr>';
                return;
            }
            $('usageBody').innerHTML = rows.map((r) => '<tr>'
                + '<td>' + esc(r.displayName || '-') + '</td>'
                + '<td><code>' + esc(r.tenantId) + '</code></td>'
                + '<td class="num">' + fmtInt(r.promptTokens) + '</td>'
                + '<td class="num">' + fmtInt(r.completionTokens) + '</td>'
                + '<td class="num">' + fmtInt(r.cachedTokens) + '</td>'
                + '<td class="num">' + fmtYuan(r.amountYuan) + '</td>'
                + '<td><button class="btn small" data-tid="' + esc(r.tenantId) + '">查看</button></td>'
                + '</tr>').join('');
            $('usageBody').querySelectorAll('button[data-tid]').forEach((b) => {
                b.addEventListener('click', () => openTenant(b.dataset.tid));
            });
        } catch (e) {
            $('usageBody').innerHTML = '<tr><td colspan="7" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
        }
    }

    // ---------- 租户详情 ----------
    async function openTenant(tenantId) {
        currentTenant = tenantId;
        currentPage = 0;
        $('tenantDetail').hidden = false;
        $('tenantDetailTitle').textContent = '租户详情 · ' + tenantId;
        $('tenantDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
        await Promise.all([loadTenantDetail(), loadLogs()]);
    }

    async function loadTenantDetail() {
        const days = $('windowDays').value;
        try {
            const d = await api('/usage/' + encodeURIComponent(currentTenant) + '?windowDays=' + days);
            $('usedYuan').textContent = fmtYuan(d.usedYuan);
            $('budgetYuan').textContent = fmtYuan(d.budgetYuan);
            $('overrideTag').hidden = d.budgetOverrideYuan == null;
            $('budgetInput').value = d.budgetOverrideYuan == null ? '' : Number(d.budgetOverrideYuan);

            $('byModelBody').innerHTML = (d.byModel || []).map((r) => '<tr>'
                + '<td>' + esc(r.model) + '</td>'
                + '<td class="num">' + fmtInt(r.promptTokens) + '</td>'
                + '<td class="num">' + fmtInt(r.completionTokens) + '</td>'
                + '<td class="num">' + fmtInt(r.cachedTokens) + '</td>'
                + '<td class="num">' + fmtYuan(r.amountYuan) + '</td>'
                + '</tr>').join('') || '<tr><td colspan="5" class="empty">无</td></tr>';

            $('byCallTypeBody').innerHTML = (d.byCallType || []).map((r) => '<tr>'
                + '<td>' + esc(r.callType) + '</td>'
                + '<td class="num">' + fmtInt(r.calls) + '</td>'
                + '<td class="num">' + fmtYuan(r.amountYuan) + '</td>'
                + '</tr>').join('') || '<tr><td colspan="3" class="empty">无</td></tr>';
        } catch (e) {
            toast('加载租户详情失败：' + e.message, 'err');
        }
    }

    async function loadLogs() {
        try {
            const d = await api('/usage/' + encodeURIComponent(currentTenant)
                + '/logs?page=' + currentPage + '&size=' + PAGE_SIZE);
            const totalPages = Math.max(1, Math.ceil(d.total / PAGE_SIZE));
            $('pageInfo').textContent = '第 ' + (currentPage + 1) + ' / ' + totalPages + ' 页 · 共 ' + d.total + ' 条';
            $('prevPage').disabled = currentPage <= 0;
            $('nextPage').disabled = currentPage + 1 >= totalPages;
            $('logsBody').innerHTML = (d.items || []).map((r) => '<tr>'
                + '<td>' + esc(r.createdAt) + '</td>'
                + '<td>' + esc(r.model) + '</td>'
                + '<td>' + esc(r.callType) + '</td>'
                + '<td><code>' + esc(r.sessionId || '-') + '</code></td>'
                + '<td class="num">' + fmtInt(r.promptTokens) + '</td>'
                + '<td class="num">' + fmtInt(r.completionTokens) + '</td>'
                + '<td class="num">' + fmtInt(r.cachedTokens) + '</td>'
                + '<td class="num">' + fmtYuan(r.amountYuan) + '</td>'
                + '</tr>').join('') || '<tr><td colspan="8" class="empty">无明细</td></tr>';
        } catch (e) {
            toast('加载明细失败：' + e.message, 'err');
        }
    }

    // ---------- 预算维护 ----------
    async function saveBudget() {
        if (!currentTenant) return;
        const raw = $('budgetInput').value.trim();
        if (raw === '') { toast('请输入预算金额，或点击「清除覆盖」', 'err'); return; }
        const yuan = Number(raw);
        if (isNaN(yuan) || yuan < 0) { toast('预算须为非负数', 'err'); return; }
        try {
            await api('/budget/' + encodeURIComponent(currentTenant), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ budgetYuan: yuan })
            });
            toast('预算已保存（即时生效）', 'ok');
            await Promise.all([loadTenantDetail(), loadUsage()]);
        } catch (e) { toast('保存预算失败：' + e.message, 'err'); }
    }

    async function clearBudget() {
        if (!currentTenant) return;
        try {
            await api('/budget/' + encodeURIComponent(currentTenant), { method: 'DELETE' });
            toast('已清除覆盖，回退平台默认预算', 'ok');
            $('budgetInput').value = '';
            await Promise.all([loadTenantDetail(), loadUsage()]);
        } catch (e) { toast('清除失败：' + e.message, 'err'); }
    }

    // ---------- 档位单价 ----------
    async function loadPricing() {
        $('pricingBody').innerHTML = '<tr><td colspan="6" class="empty">加载中…</td></tr>';
        try {
            const d = await api('/pricing');
            $('pricingBody').innerHTML = (d.tiers || []).map((t) => '<tr data-tier="' + esc(t.tier) + '">'
                + '<td>' + esc(t.tier) + '</td>'
                + '<td class="num"><input type="number" min="0" step="1" class="pin" data-k="input" value="' + esc(t.input) + '"/></td>'
                + '<td class="num"><input type="number" min="0" step="1" class="pout" data-k="output" value="' + esc(t.output) + '"/></td>'
                + '<td class="num"><input type="number" min="0" step="1" class="pcache" data-k="cache" value="' + esc(t.cache) + '"/></td>'
                + '<td>' + (t.source === 'db' ? '<span class="tag">DB 覆盖</span>' : '<span class="hint">config 默认</span>') + '</td>'
                + '<td><button class="btn small" data-save="' + esc(t.tier) + '">保存</button></td>'
                + '</tr>').join('');
            $('pricingBody').querySelectorAll('button[data-save]').forEach((b) => {
                b.addEventListener('click', () => savePricing(b.dataset.save));
            });
        } catch (e) {
            $('pricingBody').innerHTML = '<tr><td colspan="6" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
        }
    }

    async function savePricing(tier) {
        const row = document.querySelector('#pricingBody tr[data-tier="' + tier + '"]');
        if (!row) return;
        const input = Number(row.querySelector('.pin').value);
        const output = Number(row.querySelector('.pout').value);
        const cache = Number(row.querySelector('.pcache').value);
        if ([input, output, cache].some((v) => isNaN(v) || v < 0)) {
            toast('单价须为非负整数', 'err'); return;
        }
        try {
            await api('/pricing/' + encodeURIComponent(tier), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    inputYuanPerMillion: input,
                    outputYuanPerMillion: output,
                    cacheYuanPerMillion: cache
                })
            });
            toast('单价已保存（即时生效）', 'ok');
            await loadPricing();
        } catch (e) { toast('保存单价失败：' + e.message, 'err'); }
    }

    // ---------- 事件绑定 ----------
    $('refreshUsage').addEventListener('click', loadUsage);
    $('windowDays').addEventListener('change', () => {
        loadUsage();
        if (currentTenant) loadTenantDetail();
    });
    $('closeDetail').addEventListener('click', () => {
        $('tenantDetail').hidden = true; currentTenant = null;
    });
    $('saveBudget').addEventListener('click', saveBudget);
    $('clearBudget').addEventListener('click', clearBudget);
    $('prevPage').addEventListener('click', () => { if (currentPage > 0) { currentPage--; loadLogs(); } });
    $('nextPage').addEventListener('click', () => { currentPage++; loadLogs(); });

    // 初始加载
    loadUsage();
})();
