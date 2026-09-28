/**
 * 认证与 Token 管理模块
 *
 * - 页面加载时检查 localStorage 中的 JWT token，无 token 则跳转登录页
 * - 拦截全局 fetch，自动注入 Authorization: Bearer <token> 头
 * - 401 响应自动跳转登录页
 */
(function () {
    'use strict';

    var TOKEN_KEY = 'auth_token';
    var USER_KEY = 'auth_user';

    var Auth = {
        /**
         * 获取 localStorage 中的 token
         */
        getToken: function () {
            return localStorage.getItem(TOKEN_KEY);
        },

        /**
         * 获取当前登录用户信息
         */
        getUser: function () {
            var raw = localStorage.getItem(USER_KEY);
            if (!raw) return null;
            try { return JSON.parse(raw); } catch (e) { return null; }
        },

        /**
         * 是否已登录（token 存在）
         */
        isLoggedIn: function () {
            return !!this.getToken();
        },

        /**
         * 保存 token 和用户信息
         */
        saveAuth: function (token, userId, username) {
            localStorage.setItem(TOKEN_KEY, token);
            localStorage.setItem(USER_KEY, JSON.stringify({
                userId: userId,
                username: username
            }));
        },

        /**
         * 清除 token 并跳转登录页
         */
        logout: function () {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(USER_KEY);
            window.location.href = '/login';
        },

        /**
         * 检查登录状态，未登录则跳转
         */
        requireAuth: function () {
            if (!this.isLoggedIn()) {
                window.location.href = '/login';
                return false;
            }
            return true;
        },

        /**
         * 注册
         */
        register: function (username, password) {
            return fetch('/api/auth/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username, password: password })
            }).then(function (res) { return res.json(); });
        },

        /**
         * 登录
         */
        login: function (username, password) {
            return fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username, password: password })
            }).then(function (res) { return res.json(); });
        }
    };

    // 拦截全局 fetch：自动注入 Authorization 头 + 401 跳转
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
        init = init || {};
        // 跳过认证 API（登录/注册自身不需要 token）
        var url = typeof input === 'string' ? input :
                   (input && input.url ? input.url : '');
        if (url.indexOf('/api/auth/') === 0) {
            return originalFetch.apply(this, arguments);
        }
        // 注入 Authorization 头
        var token = Auth.getToken();
        if (token) {
            init.headers = init.headers || {};
            if (init.headers instanceof Headers) {
                if (!init.headers.has('Authorization')) {
                    init.headers.set('Authorization', 'Bearer ' + token);
                }
            } else {
                if (!init.headers['Authorization']) {
                    init.headers['Authorization'] = 'Bearer ' + token;
                }
            }
        }
        // 调用原始 fetch，拦截 401
        return originalFetch.call(this, input, init).then(function (response) {
            if (response.status === 401) {
                Auth.logout();
                return new Promise(function () {}); // 永不 resolve，阻止后续处理
            }
            return response;
        });
    };

    // 暴露到全局
    window.Auth = Auth;
})();
