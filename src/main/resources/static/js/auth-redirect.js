// 全局 401 统一跳转（multi-tenant-column-isolation 变更）
// 包装 window.fetch：认证失效（401）且非认证端点时跳转登录页。
// EventSource 由浏览器原生携带 cookie，过期时服务端返回 401，
// EventSource 会触发 onerror 并自动重连——由 JwtTenantFilter 对 SSE 端点
// 返回 401 终止，前端不在此处特殊处理（重连也会继续 401）。
(function () {
    'use strict';

    const originalFetch = window.fetch;
    window.fetch = function (input, init) {
        return originalFetch.call(this, input, init).then(response => {
            if (response.status === 401) {
                const url = typeof input === 'string' ? input : (input && input.url) || '';
                // 认证端点自身的 401（密码错误）不跳转，由页面自行展示
                if (url.indexOf('/api/auth/') === -1 && window.location.pathname !== '/login') {
                    window.location.href = '/login';
                }
            }
            return response;
        });
    };
})();
