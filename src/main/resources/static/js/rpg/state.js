/**
 * 全局状态与身份持久化（rpg-js-split 拆分自 rpg.js，必须最先加载）
 *
 * 本文件产出的全局符号：
 *   变量：currentWorldId, currentCharId, currentGameStateId, currentSessionId,
 *         isGameMode, abortController, narrationBuffer, lastKnownTurn,
 *         liveUserWrap, liveGmWrap
 *   常量：RPG_STORAGE_KEY, MAX_BACKTRACK_TURNS
 *   函数：persistIdentity, restoreIdentity
 *
 * rpg-home-entry：`rpg.mode` 键与 persistMode/restoreMode 已移除，
 * 刷新不再自动恢复游戏模式（每次落地首页）；`rpg.identity` 仅作“当前在玩存档”标记。
 */

// ===== 全局状态 =====
let currentWorldId = null;
let currentCharId = null;
let currentGameStateId = null;
let currentSessionId = null;
let isGameMode = false;
let abortController = null;
let narrationBuffer = '';

// ===== 回溯支持（rpg-chat-backtrack）=====
let lastKnownTurn = 0;      // 最近已知的当前轮次（history.currentTurn / 状态面板同步）
let liveUserWrap = null;    // 本局 live 玩家气泡（turn_persisted 尾包补挂事件 ID）
let liveGmWrap = null;      // 本局 live GM 气泡
const MAX_BACKTRACK_TURNS = 3;

// ===== 身份持久化（仅“当前在玩存档”标记：删除判断用，不驱动落地视图）=====
const RPG_STORAGE_KEY = 'rpg.identity';

function persistIdentity() {
    try {
        if (currentSessionId && currentGameStateId) {
            localStorage.setItem(RPG_STORAGE_KEY, JSON.stringify({
                sessionId: currentSessionId,
                gameStateId: currentGameStateId
            }));
        }
    } catch (e) { /* localStorage 不可用时忽略 */ }
}

function restoreIdentity() {
    try {
        const raw = localStorage.getItem(RPG_STORAGE_KEY);
        if (!raw) return null;
        const data = JSON.parse(raw);
        if (data && data.sessionId && data.gameStateId) return data;
    } catch (e) { /* 解析失败忽略 */ }
    return null;
}
