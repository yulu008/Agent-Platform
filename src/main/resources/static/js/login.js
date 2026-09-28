// 登录/注册页逻辑（multi-tenant-column-isolation 变更）
(function () {
    'use strict';

    const tabLogin = document.getElementById('tabLogin');
    const tabRegister = document.getElementById('tabRegister');
    const form = document.getElementById('authForm');
    const submitBtn = document.getElementById('submitBtn');
    const errorBox = document.getElementById('authError');

    // 登录后进入：目的地选择（记忆到 localStorage，默认主会话）
    const DEST_KEY = 'agent.loginDest';
    const destOptions = document.getElementById('destOptions');
    let dest = localStorage.getItem(DEST_KEY) === '/rpg' ? '/rpg' : '/';

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
        dest = btn.dataset.dest === '/rpg' ? '/rpg' : '/';
        localStorage.setItem(DEST_KEY, dest);
        renderDest();
    });

    let mode = 'login';

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
                // cookie 已由响应 Set-Cookie 写入，按用户选择的目的地跳转
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
