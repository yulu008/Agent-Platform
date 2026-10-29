/**
 * 页面初始化：默认落作品墙首屏（works-view-and-world-publish D7：home ⊕ workshop 合一），
 * 带 ?preset= 时落地预设向导（preset-rpg-entry），
 * 带 ?resume= 时直接继续对应存档（rpg-conversation-list D3），
 * 带 ?world= 时落作品墙并展开对应作品卡的角色 chips（world-card-gallery D9），
 * 带 ?view=works 时直达作品首屏；?view=workshop 旧深链兼容同落作品首屏
 * （unified-global-topbar D2 原工坊深链，单视图化后无独立工坊）
 * （rpg-js-split 拆分自 rpg.js，必须最后加载）
 *
 * 每次访问 /rpg 均落作品墙：只渲染作品卡（存档网格已迁至 📜 存档 页）；
 * 不再自动恢复游戏模式（刷新后须从对话页/深链显式继续或点卡开局）。
 * 根入口页橱窗卡带 ?preset= / ?world= 深链，对话页 [继续] 带 ?resume= 深链；
 * 优先级 preset > resume > world > view（design D3/Risks）。
 *
 * 依赖（加载时）：state.js / gallery.js / messages.js / workshop.js / game.js / preset.js / saves.js 的全部符号
 */

// ===== 页面初始化：默认落作品墙，?preset= 落预设向导，?resume= 继续存档，?world= 展开深链卡 =====
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

    // 3) 落地视图：?preset= 进预设向导（内部已 showView('preset')）；
    //    ?resume= 直接继续存档（内部已 showView('game')）；
    //    ?view=works / ?view=workshop（旧深链兼容，D7）落作品首屏；
    //    否则落作品墙；?world= 深链在墙渲染后展开对应卡的角色 chips
    const params = new URLSearchParams(location.search);
    const presetKey = params.get('preset');
    const resumeId = params.get('resume');
    const worldParam = params.get('world');
    const viewParam = params.get('view');
    const entered = presetKey ? await enterPresetWizard(presetKey) : false;
    const resumed = !entered && resumeId ? await resumeFromDeepLink(resumeId) : false;
    if (!entered && !resumed) {
        if (viewParam === 'works' || viewParam === 'workshop') {
            // 旧工坊深链兼容：/rpg?view=workshop 一律落 works 作品首屏（单视图已无独立工坊）
            showView('works');
        } else {
            await goWorks();
            if (worldParam) {
                const found = await expandWorldCardChips(worldParam);
                if (!found) console.warn('深链世界不在当前租户作品墙上（可能已删除或跨租户）:', worldParam);
            }
        }
    }

    // 4) 工坊世界选择器与落地视图无关，两条路径都要填（预设向导顶栏回工坊后仍可用）；
    //    放在物化之后，首次进向导时新建的预设世界也能出现在列表里
    await loadWorldsIntoSelector();
})();

/**
 * ?resume= 深链继续存档（rpg-conversation-list D3）：
 * GET /rpg/game/state 取 sessionId/worldId，合成伪 card（continueSave 只读这三字段）
 * 喂既有重放/开场白链路；存档不存在/查询失败 warn 回落作品墙视图（与 ?world= 落空同口吻）
 */
async function resumeFromDeepLink(gameStateId) {
    try {
        const res = await fetch('/rpg/game/state?gameStateId=' + encodeURIComponent(gameStateId));
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const gs = await res.json();
        if (!gs || !gs.id) throw new Error('存档不存在');
        await continueSave({gameStateId: gs.id, sessionId: gs.sessionId, worldId: gs.worldId});
        return true;
    } catch (e) {
        console.warn('resume 深链落空（存档可能已删除）:', gameStateId, e.message);
        return false;
    }
}
