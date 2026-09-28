package com.luyu.agent.config;

/**
 * 流式调用在「已向下游发出内容」之后遇到 ChunkMerger 不兼容错误时的专用信号异常（design D1）。
 * <p>
 * {@link StreamFallbackChatModel} 的守卫检测到本次订阅已发出过 ChatResponse chunk 且又遇
 * Spring AI ChunkMerger 对 GLM 流式格式不兼容的异常时，不再静默降级为非流式重试
 * （降级重跑的完整响应会拼接在已发内容之后，造成前端与落库双重重复），而是抛出本异常，
 * 将「已有内容发出」的事实交给调用层决策：
 * <ul>
 *   <li>RPG 回合（RpgGameController）：发 reset 事件清空前端气泡后非流式重跑本回合</li>
 *   <li>主聊天（ChatStreamController）：按既有错误路径仅保存已发部分回复</li>
 * </ul>
 * cause 为原始 ChunkMerger 错误，emittedCount 供日志诊断。
 */
public class PostEmissionStreamException extends RuntimeException {

    /** 本次订阅中断前已向下游发出的 ChatResponse chunk 数 */
    private final int emittedCount;

    public PostEmissionStreamException(Throwable cause, int emittedCount) {
        super("流式响应已在发出 " + emittedCount + " 个 chunk 后中断，静默降级重试会造成内容重复", cause);
        this.emittedCount = emittedCount;
    }

    public int getEmittedCount() {
        return emittedCount;
    }

    /**
     * 沿异常 cause 链查找本类型（MessageAggregator 等中间层可能包装下游异常）。
     *
     * @return 命中返回实例，未命中返回 null
     */
    public static PostEmissionStreamException findIn(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof PostEmissionStreamException found) {
                return found;
            }
            cur = cur.getCause();
        }
        return null;
    }
}
