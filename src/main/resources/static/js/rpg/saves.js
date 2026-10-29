/**
 * 存档继续链路（rpg-js-split 拆分自 rpg.js）
 *
 * rpg-conversation-list：首页存档网格迁出至 📜 存档 页（conversations.js），
 * 本文件退役 refreshHomeSaves / renderSaveCards / buildSaveCard / updateSaveCount /
 * deleteSaveCard / formatSaveTime，仅保留 continueSave 供 init.js ?resume= 深链使用
 * （对话页合成伪 card {gameStateId, sessionId, worldId} 喂入，见 design D3）。
 *
 * 本文件产出的全局符号：
 *   函数：continueSave
 *   依赖（运行时）：state.js（currentGameStateId, currentSessionId, currentWorldId,
 *         persistIdentity）；game.js（showView, startOpening, updateStatusPanel）；
 *         messages.js（clearMessages, loadHistory）
 */

async function continueSave(card) {
    try {
        currentGameStateId = card.gameStateId;
        currentWorldId = card.worldId || currentWorldId;
        // 作者主题注入（rpg-hud-bubble-components 4.2）：读档按存档世界应用，失败走默认
        applyWorldThemeById(currentWorldId);
        // 复用存档关联的 session 以重放历史；无关联时才新建
        currentSessionId = card.sessionId || crypto.randomUUID();
        persistIdentity();
        // 更新 session 关联
        await fetch('/rpg/game/load', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({gameStateId: card.gameStateId, sessionId: currentSessionId})
        });
        showView('game');
        clearMessages();
        // 重放该存档关联 session 的历史；为空（旧存档/空会话）则回退到重新生成开场白
        const replayed = await loadHistory();
        if (replayed) {
            document.getElementById('inputArea').hidden = false;
            document.getElementById('gameInput').focus();
            updateStatusPanel(card.gameStateId);
        } else {
            await startOpening(card.gameStateId);
        }
    } catch (e) { alert('继续存档失败: ' + e.message); }
}
