/**
 * 消息级回溯/重新生成的请求与交互 handler（rpg-js-split 拆分自 rpg.js）
 *
 * 本文件产出的全局符号：
 *   函数：requestBacktrack, handleBacktrackClick, handleRegenerateClick,
 *         applyTurnPersisted
 *   依赖（运行时）：state.js（abortController, currentSessionId, currentGameStateId,
 *         liveUserWrap, liveGmWrap, lastKnownTurn）；messages.js（回溯按钮 UI）；
 *         game.js（loadHistory, updateStatusPanel, refreshContextInfo, sendTurn,
 *         gameInput, showCompactToast）
 */

/** 调 backtrack 端点；成功返回响应体，失败 toast 并返回 null */
async function requestBacktrack(anchorEventId) {
    try {
        const res = await fetch('/rpg/game/backtrack', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                sessionId: currentSessionId,
                gameStateId: currentGameStateId,
                anchorEventId
            })
        });
        const body = await res.json().catch(() => null);
        if (!res.ok || !body || !body.success) {
            showCompactToast(`❌ 回溯失败：${(body && body.error) || ('HTTP ' + res.status)}`);
            return null;
        }
        return body;
    } catch (e) {
        showCompactToast(`❌ 回溯失败：${e.message}`);
        return null;
    }
}

/** 回溯按钮点击：confirm → backtrack → 重放历史；玩家气泡场景回填原文到输入框 */
async function handleBacktrackClick(wrap) {
    if (abortController) return;
    const eventId = wrap.dataset.eventId;
    if (!eventId) return;
    const isPlayer = wrap.classList.contains('user');
    const msgs = [...getMessagesContainer().querySelectorAll('.rpg-message')];
    const willRemove = Math.max(msgs.length - msgs.indexOf(wrap), 0);
    if (!confirm('确定回溯吗？\n\n将从该消息起删除 ' + willRemove + ' 条消息，游戏状态与 GM 记忆一并回退到该轮之前。' +
            '\n（回溯范围内 GM 新写/修改的记忆会一并回退，含你在记忆弹窗中手动修改的内容）')) return;

    const result = await requestBacktrack(eventId);
    if (!result) return;

    showCompactToast(`✅ 已回溯到第 ${result.rolledBackToTurn} 轮（删除 ${result.removedCount} 条会话消息）`);
    if (result.warning) showCompactToast(`⚠️ ${result.warning}`);

    clearMessages();
    await loadHistory();
    updateStatusPanel(currentGameStateId);
    refreshContextInfo();
    if (isPlayer && result.playerMessage) {
        gameInput.value = result.playerMessage;
        gameInput.focus();
    }
}

/** 重新生成按钮点击（仅最新 GM 轮）：backtrack 后重放干净历史，再自动重发玩家原文 */
async function handleRegenerateClick(wrap) {
    if (abortController) return;
    const eventId = wrap.dataset.eventId;
    if (!eventId) return;
    if (!confirm('重新生成最后一轮？\n\n将删除该轮全部内容并重掷触发器与概率检定，随后自动重发玩家原文。')) return;

    const result = await requestBacktrack(eventId);
    if (!result) return;
    showCompactToast('✅ 已回滚最后一轮，正在重新生成…');
    // 回滚后旧气泡锚点已作废（会话事件已删）：重放干净历史，再以玩家原文重开一轮
    clearMessages();
    await loadHistory();
    await sendTurn(result.playerMessage);
}

/** turn_persisted 尾包：把事件 ID 补挂到本局 live 气泡并渲染操作按钮 */
function applyTurnPersisted(persisted, roundNo) {
    if (persisted.userEventId && liveUserWrap) {
        liveUserWrap.dataset.eventId = persisted.userEventId;
        liveUserWrap.dataset.approxTurn = String(roundNo);
        addBacktrackButton(liveUserWrap);
    }
    if (persisted.assistantEventId && liveGmWrap) {
        liveGmWrap.dataset.eventId = persisted.assistantEventId;
        liveGmWrap.dataset.approxTurn = String(roundNo);
        addBacktrackButton(liveGmWrap);
        addRegenerateButton(liveGmWrap);
    }
    refreshBacktrackButtonStates();
}
