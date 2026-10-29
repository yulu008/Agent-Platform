// 登录/注册页逻辑（multi-tenant-column-isolation 变更）
(function () {
    'use strict';

    const tabLogin = document.getElementById('tabLogin');
    const tabRegister = document.getElementById('tabRegister');
    const form = document.getElementById('authForm');
    const submitBtn = document.getElementById('submitBtn');
    const errorBox = document.getElementById('authError');

    // 登录后进入：目的地选择（记忆到 localStorage，默认 AI助手）
    const DEST_KEY = 'agent.loginDest';
    const destOptions = document.getElementById('destOptions');
    let dest = localStorage.getItem(DEST_KEY) === '/rpg' ? '/rpg' : '/chat';

    function renderDest() {
        destOptions.querySelectorAll('.auth-dest-option').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.dest === dest);
        });
    }
    renderDest();

    destOptions.addEventListener('click', (event) => {
        const btn = event.target.closest('.auth-dest-option');
        if (!btn) {
            return;
        }
        dest = btn.dataset.dest === '/rpg' ? '/rpg' : '/chat';
        localStorage.setItem(DEST_KEY, dest);
        renderDest();
    });

    let mode = 'login';

    // 切换账号预填（account-switch-sidebar）：侧栏已知账号跳入 /login?email=<目标>，
    // 预填邮箱、密码留空、光标落密码框（还差一次密码输入即完成切换）
    const prefillEmail = new URLSearchParams(window.location.search).get('email');
    if (prefillEmail) {
        document.getElementById('email').value = prefillEmail;
        document.getElementById('password').focus();
    }

    // 已知账号记忆（account-switch-sidebar）：仅存邮箱明文（无凭据），去重置顶，
    // 截断保留最近 8 条；sidebar.js 读同一 key 渲染侧栏切换列表
    const ACCOUNTS_KEY = 'agent.knownAccounts';
    const ACCOUNTS_MAX = 8;

    function rememberAccount(email) {
        try {
            const raw = localStorage.getItem(ACCOUNTS_KEY);
            const list = raw ? JSON.parse(raw) : [];
            const next = [email]
                .concat(Array.isArray(list) ? list.filter(e => e !== email) : [])
                .slice(0, ACCOUNTS_MAX);
            localStorage.setItem(ACCOUNTS_KEY, JSON.stringify(next));
        } catch (e) { /* localStorage 不可用（隐私模式等）时静默降级为不记忆 */ }
    }

    function setMode(next) {
        mode = next;
        tabLogin.classList.toggle('active', mode === 'login');
        tabRegister.classList.toggle('active', mode === 'register');
        submitBtn.textContent = mode === 'login' ? '登录' : '注册';
        hideError();
    }

    tabLogin.addEventListener('click', () => setMode('login'));
    tabRegister.addEventListener('click', () => setMode('register'));

    function showError(message) {
        errorBox.textContent = message;
        errorBox.hidden = false;
    }

    function hideError() {
        errorBox.hidden = true;
    }

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        hideError();

        const email = document.getElementById('email').value.trim();
        const password = document.getElementById('password').value;
        if (!email || !password) {
            showError('请填写邮箱与密码');
            return;
        }

        submitBtn.disabled = true;
        try {
            const response = await fetch(`/api/auth/${mode}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email, password })
            });
            if (response.ok) {
                // cookie 已由响应 Set-Cookie 写入；账号入已知列表后按用户选择的目的地跳转
                rememberAccount(email);
                window.location.href = dest;
                return;
            }
            const body = await response.json().catch(() => ({}));
            showError(body.error || '请求失败，请稍后重试');
        } catch (e) {
            showError('网络错误，请稍后重试');
        } finally {
            submitBtn.disabled = false;
        }
    });
})();
