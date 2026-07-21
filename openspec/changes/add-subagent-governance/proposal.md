## Why

当前骨架虽装配了 `SkillsTool` Bean，但 [ChatController](../../src/main/java/com/example/agent/controller/ChatController.java) 仅以 `chatClientBuilder.build()` 构建主客户端，**工具从未真正挂载到 ChatClient**；同时完全未启用 `TaskTool`（子Agent编排）。要让平台具备"主Agent按需委派子Agent、子Agent在隔离上下文执行技能"的能力，并防止子Agent失控（无限并行、长时执行）且使子Agent行为可审计追溯，需要启用子Agent编排并叠加执行层治理（限流 + 分离日志）。spring-ai-agent-utils 0.7.0 已在依赖中提供 `TaskTool` / `SubagentExecutor` SPI / `ClaudeSubagentType`，无需新增依赖，正是落地时机。

## What Changes

- 修复骨架遗留缺口：将 `SkillsTool` 真正注册到主 `ChatClient`（`defaultToolCallbacks`）
- 装配 `TaskTool`（spring-ai-agent-utils 0.7.0 的子Agent编排工具），从 markdown 目录加载子Agent定义（`.claude/agents/` 风格，复用 `ClaudeSubagentReferences.fromRootDirectory`）
- 实现 skills×subAgent 三模式：
  - 主Agent同时具备 Skills + Task 工具，模型按需调用 skill 或委派子Agent
  - 子Agent自身被赋予 skills（`ClaudeSubagentType.builder().skillsResource(...)`），在隔离上下文执行技能
  - skill 文本可指示主Agent委派特定子Agent（提示词约定 + 示例 SKILL.md）
- 新增 `RateLimitingSubagentExecutor` 装饰器：基于 `SubagentExecutor` SPI，用 `Semaphore` 控制最大并行度、`Future.get(timeout)` 控制单次执行存活时长；后台任务用 TTL 清扫器 `cancel` 过期任务
- 新增分离日志：独立 logger `subagent.audit` + MDC（`subagentType` / `taskId` / `parentId`），Logback 路由到独立文件
- 新增子Agent markdown 定义目录 `resources/agents/` + 示例子Agent（`general-purpose.md`、`code-reviewer.md`）
- 新增配置项：`agents-root`（子Agent定义根目录）、`subagent.max-parallelism`（最大并行度）、`subagent.lifespan`（存活时长）

## Capabilities

### New Capabilities

- `subagent-orchestration`: 子Agent编排能力，覆盖主Agent工具挂载（SkillsTool + TaskTool）、markdown 子Agent定义加载、skills×subAgent 三模式（主Agent挂载 / 子Agent带技能 / skill 文本驱动委派）
- `subagent-governance`: 子Agent执行层治理能力，覆盖限流（最大并行度、存活时长）与分离日志记录（独立文件 + MDC）

### Modified Capabilities

（无。`spring-ai-bootstrap` 既有需求"SkillsTool Bean 装配成功"仅约束 Bean 存在性，本变更不改变其需求语义，工具挂载作为新能力 `subagent-orchestration` 的需求定义。）

## Impact

- **新增代码**：`SubagentConfiguration`（TaskTool + 装饰器装配）、`RateLimitingSubagentExecutor`（限流装饰器）、`SubagentAuditLogger`（MDC + 独立 logger）、`logback-spring.xml`（独立 appender）、`resources/agents/` 示例子Agent、示例 `SKILL.md`（文本驱动委派约定）
- **修改代码**：`AgentUtilsProperties`（扩展 `agents-root` / `subagent.*` 配置项）、`AgentUtilsConfiguration`（装配 TaskTool 与装饰器）、`ChatController`（主 ChatClient 挂载工具回调）
- **新增配置**：`spring.ai.agent-utils.agents-root`、`spring.ai.agent-utils.subagent.max-parallelism`、`spring.ai.agent-utils.subagent.lifespan`
- **依赖**：复用现有 `spring-ai-agent-utils:0.7.0`（`TaskTool` / `SubagentExecutor` / `ClaudeSubagentType` / `DefaultTaskRepository` 均已在依赖中），**无需新增依赖**
- **破坏性变更**：`POST /chat` 端点行为变化（响应链路现携带工具调用能力，模型可能自主调用 skill / 委派子Agent），但 HTTP 接口签名不变，向后兼容
