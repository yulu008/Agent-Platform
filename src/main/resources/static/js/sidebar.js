/* 全局侧边栏交互（global-sidebar-nav）：
   - 桌面折叠切换（.app-shell.folded → 56px 图标轨）+ localStorage 跨页跨会话记忆（sidebar.folded，默认展开）；
   - 移动端 ≤768px ☰ 抽屉开合：☰/遮罩/入口选择三路径关闭，Esc 亦关闭；
   - chat 页互斥（D5 单向）：全局抽屉打开时强制关闭 session-drawer，反之不联动。
   rpg/game.js 依赖的 navRpgLink 同页拦截与 active 高亮逻辑在本文件之外，互不干扰。 */
(function () {
    'use strict';

    var SHELL = document.querySelector('.app-shell');
    var SIDEBAR = document.getElementById('globalSidebar');
    var FOLD_BTN = document.getElementById('sidebarFoldBtn');
    var BURGER = document.getElementById('sidebarBurger');
    var MASK = document.getElementById('sidebarMask');
    var STORAGE_KEY = 'sidebar.folded';

    /* ===== 桌面折叠：加载恢复 + 切换持久化 ===== */
    if (SHELL && FOLD_BTN) {
        try {
            if (localStorage.getItem(STORAGE_KEY) === '1') {
                SHELL.classList.add('folded');
            }
        } catch (e) { /* localStorage 不可用（隐私模式等）时静默降级为不记忆 */ }

        FOLD_BTN.addEventListener('click', function () {
            var folded = SHELL.classList.toggle('folded');
            try { localStorage.setItem(STORAGE_KEY, folded ? '1' : '0'); } catch (e) { /* 同上 */ }
            FOLD_BTN.title = folded ? '展开侧边栏' : '收起侧边栏';
            FOLD_BTN.setAttribute('aria-label', folded ? '展开侧边栏' : '收起侧边栏');
            FOLD_BTN.setAttribute('aria-expanded', folded ? 'false' : 'true');
        });
    }

    /* ===== 移动端抽屉：开/关 ===== */
    function openDrawer() {
        if (!SIDEBAR) return;
        /* 互斥（D5 单向）：chat 页 session-drawer 展开时先关闭，避免两个左侧抽屉叠层 */
        var sessionDrawer = document.querySelector('.session-drawer.open');
        if (sessionDrawer) {
            sessionDrawer.classList.remove('open');
        }
        SIDEBAR.classList.add('open');
        if (MASK) MASK.classList.add('show');
        if (BURGER) BURGER.setAttribute('aria-expanded', 'true');
    }

    function closeDrawer() {
        if (!SIDEBAR) return;
        SIDEBAR.classList.remove('open');
        if (MASK) MASK.classList.remove('show');
        if (BURGER) BURGER.setAttribute('aria-expanded', 'false');
    }

    if (BURGER) {
        BURGER.addEventListener('click', function () {
            if (SIDEBAR && SIDEBAR.classList.contains('open')) {
                closeDrawer();
            } else {
                openDrawer();
            }
        });
    }

    if (MASK) {
        MASK.addEventListener('click', closeDrawer);
    }

    if (SIDEBAR) {
        /* 入口选择即关（跳转前的即时收抽屉反馈；跨页跳转由整页导航自然接管） */
        SIDEBAR.querySelectorAll('.sidebar-item').forEach(function (item) {
            item.addEventListener('click', closeDrawer);
        });

        /* Esc 关抽屉（键盘可达性） */
        document.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape' && SIDEBAR.classList.contains('open')) {
                closeDrawer();
            }
        });
    }

    /* ===== 账号区（account-switch-sidebar）：已知账号列表渲染 / 点击切换 / × 移除 / 退出登录 =====
       已知账号邮箱存 localStorage agent.knownAccounts（仅邮箱明文，无凭据），
       与 login.js 的记忆写入方共享同一 key；条目 DOM 一律 createElement + textContent，
       邮箱永不经 innerHTML 拼接。 */
    var ACCOUNT_KEY = 'agent.knownAccounts';
    var ACCOUNT_BOX = document.getElementById('sidebarAccount');
    var ACCOUNT_LIST = document.getElementById('sidebarAccountList');
    var LOGOUT_BTN = document.getElementById('sidebarLogoutBtn');

    function readKnownAccounts() {
        try {
            var raw = localStorage.getItem(ACCOUNT_KEY);
            var list = raw ? JSON.parse(raw) : [];
            return Array.isArray(list)
                ? list.filter(function (e) { return typeof e === 'string' && e; })
                : [];
        } catch (e) { /* 数据损坏/隐私模式时按空列表降级 */ }
        return [];
    }

    function writeKnownAccounts(list) {
        try { localStorage.setItem(ACCOUNT_KEY, JSON.stringify(list)); } catch (e) { /* 同上 */ }
    }

    function renderAccountList() {
        if (!ACCOUNT_LIST) return;
        var current = (ACCOUNT_BOX && ACCOUNT_BOX.getAttribute('data-email')) || '';
        ACCOUNT_LIST.textContent = '';
        readKnownAccounts().forEach(function (email) {
            if (email === current) return; /* 当前登录账号不在切换列表中重复出现 */
            var item = document.createElement('button');
            item.type = 'button';
            item.className = 'sidebar-account-item';
            item.setAttribute('data-email', email);
            item.title = '切换到 ' + email;
            item.setAttribute('aria-label', '切换账号：' + email);

            var label = document.createElement('span');
            label.className = 'sidebar-account-email';
            label.textContent = email;
            item.appendChild(label);

            /* × 用 span 而非 button（避免 button 嵌套），键盘可达性交由外层条目 */
            var remove = document.createElement('span');
            remove.className = 'sidebar-account-remove';
            remove.setAttribute('role', 'button');
            remove.setAttribute('aria-label', '从列表移除 ' + email);
            remove.title = '从列表移除';
            remove.textContent = '×';
            item.appendChild(remove);

            ACCOUNT_LIST.appendChild(item);
        });
    }

    if (ACCOUNT_LIST) {
        /* 事件委托：条目点击切换，× 点击移除，互不干扰 */
        ACCOUNT_LIST.addEventListener('click', function (ev) {
            var itemEl = ev.target.closest('.sidebar-account-item');
            if (!itemEl) return;
            var email = itemEl.getAttribute('data-email') || '';
            if (!email) return;

            if (ev.target.closest('.sidebar-account-remove')) {
                /* × 移除：仅删 localStorage 记忆（不触碰服务端），就地重渲染；chip 不受影响 */
                writeKnownAccounts(readKnownAccounts().filter(function (e) { return e !== email; }));
                renderAccountList();
                return;
            }

            /* 点击账号：直接跳登录页预填邮箱切换（C1 轻量版，无确认弹窗）；移动端先收抽屉 */
            closeDrawer();
            window.location.href = '/login?email=' + encodeURIComponent(email);
        });
    }

    if (LOGOUT_BTN) {
        /* 退出登录：二次确认 → POST /api/auth/logout 清 cookie → 整页跳 /login
           （响应 204 不触发 auth-redirect.js 的 401 包装；SSE 流由整页导航自然中断） */
        LOGOUT_BTN.addEventListener('click', function () {
            if (!window.confirm('确定退出登录？当前页面将离开。')) return;
            closeDrawer();
            fetch('/api/auth/logout', { method: 'POST' })
                .then(function () { window.location.href = '/login'; })
                .catch(function () { window.location.href = '/login'; });
        });
    }

    renderAccountList();
})();
