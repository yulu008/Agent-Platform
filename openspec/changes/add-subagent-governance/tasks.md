## 1. 配置属性扩展

- [x] 1.1 修改 `AgentUtilsProperties`，新增 `agentsRoot`（默认 `classpath:agents`）、`maxParallelism`（默认 4）、`lifespan`（默认 `120s`）字段，绑定 `spring.ai.agent-utils` 前缀；保留既有 `skillsRoot` 与规范构造器容错逻辑
- [x] 1.2 在 `application.yml` 的 `spring.ai.agent-utils` 下新增 `agents-root`、`subagent.max-parallelism`、`subagent.lifespan` 配置项及中文注释；在 `application-local.yml.example` 补充示例
- [x] 1.3 执行 `mvn clean compile` 验证配置属性编译通过

## 2. 子Agent审计日志

- [x] 2.1 新建 `com.example.agent.audit.SubagentAuditLogger`，封装独立 logger `subagent.audit`，提供 `logStart/logEnd/logTimeout/logRejected` 方法，内部通过 MDC 注入 `subagentType` / `taskId` / `parentId` / `duration`
- [x] 2.2 新建 `src/main/resources/logback-spring.xml`，为 `subagent.audit` 配置独立 `RollingFileAppender`（输出 `logs/subagent-audit.log`），`additivity=false`，保留 Spring Boot 默认 CONSOLE/FILE appender
- [x] 2.3 执行 `mvn clean compile` 验证日志模块编译通过

## 3. 限流装饰器

- [x] 3.1 新建 `com.example.agent.governance.RateLimitingSubagentExecutor implements SubagentExecutor`，持有 `delegate`（被装饰的 `SubagentExecutor`）、`Semaphore maxParallelism`、`lifespan`、`SubagentAuditLogger`
- [x] 3.2 实现 `execute(TaskCall, SubagentDefinition)`：`tryAcquire` 带超时→提交 `Future`→`future.get(lifespan)` 超时 `cancel(true)`→审计日志记录→超限/超时返回友好错误文本（不抛异常）→finally 释放许可
- [x] 3.3 实现后台任务 TTL 清扫：包装传入 `TaskRepository` 的 `Supplier` 在 `get()` 内套 `Future.get(lifespan)`；新增 `ScheduledExecutorService` 周期扫描 `DefaultTaskRepository`，对超 `lifespan` 的 `BackgroundTask` 调 `cancel(true)` 并审计记录
- [x] 3.4 提供 `wrapAll(List<SubagentType>)` 静态工具方法，遍历每个 `SubagentType` 用 `new SubagentType(resolver, wrapped)` 重建，为多路由预留
- [x] 3.5 执行 `mvn clean compile` 验证装饰器编译通过

## 4. TaskTool 装配与子Agent定义加载

- [x] 4.1 新建 `com.example.agent.config.SubagentConfiguration`，注入 `ChatClient.Builder`、`AgentUtilsProperties`、`ResourceLoader`、`SubagentAuditLogger`
- [x] 4.2 装配 `ClaudeSubagentType`：`.chatClientBuilder("default", chatClientBuilder.clone())` + `.skillsResource(skillsResource)`（模式 B，复用 `skillsRoot`）→ `.build()` 得到 `SubagentType`
- [x] 4.3 用 `RateLimitingSubagentExecutor` 装饰上一步的 `SubagentType`（调用 `wrapAll`），得到限流后的 `SubagentType`
- [x] 4.4 装配 `TaskTool`：从 `agentsRoot` 用 `ClaudeSubagentReferences.fromRootDirectory(...)` 加载 `SubagentReference`；`TaskTool.builder().subagentTypes(limited).subagentReferences(refs).taskRepository(defaultTaskRepository).build()` 得到 `ToolCallback`；目录为空时容错跳过并告警
- [x] 4.5 执行 `mvn clean compile` 验证装配编译通过

## 5. 主 Agent 工具挂载

- [x] 5.1 修改 `ChatController`，注入 `SkillsTool` 的 `ToolCallback` 与 `TaskTool` 的 `ToolCallback`，构造主 `ChatClient` 时调用 `.defaultToolCallbacks(skillsTool, taskTool)`（TaskTool 为空时仅挂载 SkillsTool）
- [x] 5.2 执行 `mvn clean compile` 验证编译通过

## 6. 子Agent定义与 skills×subAgent 示例

- [x] 6.1 新建 `src/main/resources/agents/.gitkeep` 占位；新建 `general-purpose.md` 与 `code-reviewer.md` 子Agent定义（YAML frontmatter 含 `name` / `description` / `tools`，正文为系统提示词）
- [x] 6.2 新建 `src/main/resources/skills/code-review/SKILL.md`，正文演示模式 C：指示"当遇到代码审查任务时调用 task 工具委派 `code-reviewer` 子Agent"，并在 frontmatter 声明 `name` / `description`
- [x] 6.3 在项目根新建 `docs/subagent-naming.md`，文档化子Agent命名规范（kebab-case、唯一性、与 SKILL.md 委派指示的一致性约定）

## 7. 验证与归档

- [x] 7.1 执行 `mvn clean package -DskipTests` 验证整体构建成功
- [x] 7.2 启动应用验证：agents 目录为空时不阻断启动、TaskTool 跳过装配日志可见、`/chat` 端点可用
- [ ] 7.3 启动应用验证：放入子Agent markdown 后 TaskTool 装配成功、启动日志输出子Agent名称列表、主 ChatClient 挂载 Skills+Task 工具
- [ ] 7.4 验证限流与日志：触发子Agent执行后 `logs/subagent-audit.log` 生成审计记录且含 MDC 上下文、主日志文件不含该记录
- [ ] 7.5 同步 delta spec 到主 specs 目录，将 change 移动到 archive 完成归档
