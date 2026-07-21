## Context

本变更建立在已归档的 `spring-ai-bootstrap` 骨架之上。当前状态与关键约束：

- 骨架已引入 `spring-ai-agent-utils:0.7.0`，但只装配了 `SkillsTool` Bean；[ChatController](../../src/main/java/com/example/agent/controller/ChatController.java) 以 `chatClientBuilder.build()` 构建主客户端，**工具未挂载到 ChatClient**——这是遗留缺口。
- 通过反编译 0.7.0 JAR 确认了子Agent SPI 的实际签名（与 Spring 官方博客示例的 0.4.x `TaskToolCallbackProvider` 已漂移）：
  - `SubagentExecutor`（SPI）：`String getKind()` + `String execute(TaskCall, SubagentDefinition)`——**同步、单一入口、可装饰**
  - `SubagentType`（record）：`(SubagentResolver resolver, SubagentExecutor executor)`——record 构造器公开，可重建
  - `TaskTool.Builder`：`.subagentTypes(SubagentType...)` / `.taskRepository(TaskRepository)` / `.build() → ToolCallback`
  - `ClaudeSubagentType.Builder`：`.chatClientBuilder(name, builder)` / `.skillsResource(Resource)` / `.build() → SubagentType`
  - `ClaudeSubagentReferences.fromRootDirectory(String) → List<SubagentReference>`
  - `TaskCall`（record）含 `run_in_background()` 字段；`BackgroundTask` 内部用 `CompletableFuture<String>`，已有 `waitForCompletion(long)` / `cancel(boolean)`
- 技术栈：JDK 21、Spring Boot 4.1、Spring AI 2.0.0 GA、agent-utils 0.7.0、Maven。无需新增依赖。

## Goals / Non-Goals

**Goals:**

- 修复骨架遗留缺口：主 ChatClient 真正挂载 `SkillsTool` + `TaskTool`
- 装配 `TaskTool`，从 markdown 目录加载子Agent定义（`.claude/agents/` 风格）
- 实现 skills×subAgent 三模式（A 主Agent挂载 / B 子Agent带技能 / C skill 文本驱动委派）
- 在 `SubagentExecutor` SPI 层叠加限流装饰器：`Semaphore` 控最大并行度、`Future.get(timeout)` 控存活时长
- 子Agent执行记录到独立日志文件，MDC 注入 `subagentType` / `taskId` / `parentId`

**Non-Goals:**

- 不限流子Agent**内部**工具调用（Shell/FS/Grep 等）的 QPS——仅作用于子Agent执行层（用户决策）
- 不做结构化审计落库（独立文件 + MDC 已满足当前可观测性需求）
- 不做多模型路由（仅注册单个 `default` ChatClient.Builder，多路由作为后续优化）
- 不实现子Agent会话级长生命周期管理（仅单次执行超时 + 后台任务 TTL）
- 不升级 agent-utils 版本（锁定 0.7.0，与既有 design 决策一致）

## Decisions

### 决策 1：限流用 SubagentExecutor 装饰器（零侵入库源码）

- **方案**：实现 `RateLimitingSubagentExecutor implements SubagentExecutor`，内部持有被装饰的 `delegate`（即 `ClaudeSubagentExecutor`）。`execute()` 内：`Semaphore.tryAcquire(maxParallel, timeout)` → 提交 `Future` → `future.get(lifespan)` 超时则 `cancel(true)`。
- **理由**：`SubagentExecutor.execute()` 是同步单一入口，是子Agent执行的唯一必经路径；`SubagentType` 是 record，可 `new SubagentType(claudeType.resolver(), wrapped)` 重建，无需改库源码。
- **备选 A**（否决）：包装 `TaskTool` 的 `ToolCallback`——拦在主Agent调用层，但 `run_in_background=true` 时走 `TaskRepository.putTask(supplier)` 异步路径会绕过外层拦截。
- **备选 B**（否决）：fork agent-utils 改 `ClaudeSubagentExecutor`——升级负担重，违反版本锁定策略。

### 决策 2：skills×subAgent 三模式递进落地（A→B→C）

- **模式 A**：`ChatClient.builder().defaultToolCallbacks(skillsTool, taskTool).build()`——主Agent同时具备技能发现与子Agent委派能力。
- **模式 B**：`ClaudeSubagentType.builder().skillsResource(skillsResource).build()`——子Agent在隔离上下文加载并执行技能，复用现有 `skillsRoot` 资源。
- **模式 C**：提示词约定层面。提供示例 `SKILL.md`，正文指示"当遇到 X 任务时调用 task 工具委派 `code-reviewer` 子Agent"；并文档化子Agent命名规范。**A 是 C 的前置**（主Agent须先挂载 TaskTool，模型才能按 skill 指示委派）。
- **落地顺序**：A → B → C。

### 决策 3：子Agent定义从 markdown 目录加载

- **方案**：`ClaudeSubagentReferences.fromRootDirectory(agentsRoot)` 加载 `.claude/agents/` 风格 markdown（YAML frontmatter + 正文），默认 `classpath:agents`，与 skills 目录对称、可热更新。
- **备选**（否决）：代码内建 `SubagentType`——不便运行时调整，与 skills 加载源不对称。

### 决策 4：分离日志 = 独立 logger + MDC + Logback 路由

- **方案**：装饰器在 `execute()` 前后写独立 logger `subagent.audit`（INFO 级），MDC 注入 `subagentType` / `taskId` / `parentId` / `duration`；`logback-spring.xml` 新增 `RollingFileAppender` 路由到 `logs/subagent-audit.log`，`additivity=false` 避免重复写主日志。
- **备选 A**（否决）：仅 MDC 标记同文件——物理不分离，检索与归档不便。
- **备选 B**（否决）：结构化审计落 JSON/DB——骨架阶段工作量过大，超出当前需求。

### 决策 5：后台任务存活时长用 TTL 清扫器

- **前台**（`run_in_background != true`）：装饰器直接 `future.get(lifespan)`，超时 `cancel(true)` 并返回超时错误。
- **后台**（`run_in_background == true`）：`TaskCall` 走 `TaskRepository.putTask(id, supplier)` 异步路径，装饰器拦截不到异步体。方案：装饰器包装传入 `TaskRepository` 的 `Supplier`，在 `get()` 内再套 `Future.get(lifespan)`；同时用 `ScheduledExecutorService` 定期扫描 `DefaultTaskRepository`，对超过 lifespan 的 `BackgroundTask` 调 `cancel(true)` 作为兜底。
- **理由**：`BackgroundTask` 已暴露 `cancel(boolean)` 与 `CompletableFuture`，TTL 清扫器复用既有能力。

### 决策 6：限流超限/超时返回友好错误而非抛异常

- **方案**：`Semaphore.tryAcquire` 超限或 `Future.get` 超时，装饰器返回结构化错误文本（如 `[subagent-governance] 并行度超限，当前最大 N`）作为 `execute()` 的 String 返回，由主Agent的 Task 工具回传给模型，不抛 RuntimeException 中断主链路。
- **理由**：Task 工具的契约是返回 String 给模型；抛异常会让主Agent对话直接失败。

## Risks / Trade-offs

- **[后台任务 TTL 清扫器复杂度]** → 前台优先 `Future.get(lifespan)`；后台用包装 `Supplier` + 定时清扫兜底，清扫周期默认 10s 可配。
- **[多模型路由下装饰器覆盖遗漏]** → 当前 Non-Goal 不做多路由；但装配逻辑提供 `wrapAll(List<SubagentType>)` 工具方法遍历逐一套装饰器，为后续多路由预留。
- **[skill 文本驱动委派依赖主Agent挂载 TaskTool]** → A 是 C 前置，落地顺序 A→B→C；提供示例 SKILL.md + 子Agent命名规范文档化。
- **[当前 ChatClient 未挂工具的遗留缺口]** → 本变更顺带修复，`ChatController` 改为 `defaultToolCallbacks`。
- **[agent-utils 0.7.0 API 与博客示例漂移]** → 以 0.7.0 实际字节码为准（已确认 `TaskTool.builder()` 而非 `TaskToolCallbackProvider`），实现时以编译/运行为准。
- **[Semaphore 阻塞致主Agent线程长时挂起]** → `tryAcquire` 带超时（默认与 lifespan 同量级），超限返回友好错误而非死等。
- **[子Agent定义 markdown 缺失或格式错误]** → 启动时 `fromRootDirectory` 容错（参考 SkillsTool 空目录容错经验），目录为空时跳过 TaskTool 装配不阻断启动。

## Migration Plan

- 单次部署，无数据迁移。配置项均有默认值：`agents-root=classpath:agents`、`subagent.max-parallelism=4`、`subagent.lifespan=120s`。
- 未配置 agents 目录或目录为空时，TaskTool 跳过装配，主Agent退化为仅挂载 SkillsTool，不影响 `/chat` 可用性。
- 回滚：移除 `ChatController` 的 `defaultToolCallbacks` 即回到裸 ChatClient 行为。

## Open Questions

- 限流超限/超时返回给模型的具体错误文本格式（是否需要 i18n 或固定中文）？
- 后台任务 TTL 清扫周期默认值（10s 是否合理，是否应与 lifespan 挂钩）？
- 子Agent命名是否需要启动时强校验重复（markdown 里 `name` 重复时告警还是阻断）？
