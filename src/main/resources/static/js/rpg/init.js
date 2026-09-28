/**
 * 页面初始化：统一落地首页（rpg-home-entry）
 * （rpg-js-split 拆分自 rpg.js，必须最后加载）
 *
 * 每次访问 /rpg 均落首页：渲染存档卡片列表与新开冒险下拉；
 * 不再自动恢复游戏模式（刷新后须从首页显式继续存档或新开冒险）。
 *
 * 依赖（加载时）：state.js / messages.js / workshop.js / game.js / saves.js 的全部符号
 */

// ===== 页面初始化：统一落地首页 =====
(async function initRpg() {
    // 1) 恢复“当前在玩存档”标记（仅用于删除判断等，不驱动落地视图）
    const saved = restoreIdentity();
    if (saved) {
        currentSessionId = saved.sessionId;
        currentGameStateId = saved.gameStateId;
    }

    // 2) 浏览器刷新会自动恢复工坊表单输入值，这里强制清空为新建态
    await loadWorldsIntoSelector();
    resetWorldForm();
    resetCharForm();

    // 3) 渲染首页：三视图切到 home，拉取存档卡片列表，加载新开冒险世界观下拉
    showView('home');
    await refreshHomeSaves();
    await refreshWorldSelects();
})();
