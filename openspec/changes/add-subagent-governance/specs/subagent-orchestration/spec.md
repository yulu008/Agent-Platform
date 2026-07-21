## ADDED Requirements

### Requirement: 主 Agent 工具挂载

系统 SHALL 将 `SkillsTool` 与 `TaskTool` 工具回调注册到主 `ChatClient`（通过 `defaultToolCallbacks`），使模型在 `/chat` 链路中可自主调用技能发现与子Agent委派。骨架阶段遗留的"Bean 装配但未挂载"缺口 SHALL 在本变更中修复。

#### Scenario: 主 ChatClient 携带 Skills 与 Task 工具

- **WHEN** 应用启动完成，`SkillsTool` 与 `TaskTool` 均装配成功
- **THEN** 主 `ChatClient` 通过 `defaultToolCallbacks` 注册两者，模型在对话中可识别并调用 skill 工具与 task 工具

#### Scenario: TaskTool 装配失败时不阻断 SkillsTool

- **WHEN** agents 根目录不存在或为空导致 `TaskTool` 跳过装配
- **THEN** 主 `ChatClient` 仍挂载 `SkillsTool`，`/chat` 端点保持可用，启动日志记录 TaskTool 跳过原因

### Requirement: TaskTool 装配与子Agent定义加载

系统 SHALL 装配 `spring-ai-agent-utils` 的 `TaskTool`，从配置的 agents 根目录加载 `.claude/agents/` 风格的子Agent markdown 定义（YAML frontmatter + 正文），将其作为 `SubagentReference` 注册到 `TaskTool.Builder`。agents 目录为空时 SHALL NOT 阻断应用启动。

#### Scenario: 从 markdown 目录加载子Agent定义

- **WHEN** agents 根目录 `classpath:agents` 下存在合法的子Agent markdown 文件（含 `name` / `description` frontmatter）
- **THEN** `TaskTool` 装配成功，启动日志输出加载到的子Agent名称列表，模型可通过 task 工具按 `subagent_type` 委派

#### Scenario: agents 目录为空时启动成功

- **WHEN** agents 根目录下无任何子Agent markdown 文件（仅 `.gitkeep` 占位）
- **THEN** 应用正常启动，`TaskTool` 跳过装配，启动日志输出跳过原因，主 `ChatClient` 仅挂载 `SkillsTool`

### Requirement: 子Agent定义根目录可配置

系统 SHALL 通过 `spring.ai.agent-utils.agents-root` 配置项指定子Agent定义加载根目录，默认值 `classpath:agents`，支持 `classpath:` 与 `file:` 前缀。

#### Scenario: 使用默认 classpath 加载

- **WHEN** 未显式配置 `spring.ai.agent-utils.agents-root`
- **THEN** 系统使用默认值 `classpath:agents` 加载子Agent定义

#### Scenario: 覆盖为外部文件目录

- **WHEN** 配置 `spring.ai.agent-utils.agents-root=file:./external-agents` 且该目录存在
- **THEN** 系统从指定文件系统路径加载子Agent定义，不再读取 classpath 默认目录

### Requirement: skills×subAgent 三模式

系统 SHALL 支持 skills 与 subAgent 的三种协作模式：
- **模式 A**：主Agent同时挂载 Skills 与 Task 工具，模型按需调用技能或委派子Agent
- **模式 B**：子Agent自身被赋予 skills 资源，在隔离上下文加载并执行技能（复用 `skillsRoot` 资源）
- **模式 C**：skill 文本可指示主Agent委派特定子Agent（提示词约定 + 示例 `SKILL.md`）

#### Scenario: 模式 A 主Agent同时具备技能与委派能力

- **WHEN** 主 `ChatClient` 同时挂载 `SkillsTool` 与 `TaskTool`
- **THEN** 模型可在单次对话中既调用 skill 工具发现技能，又调用 task 工具委派子Agent

#### Scenario: 模式 B 子Agent在隔离上下文执行技能

- **WHEN** `ClaudeSubagentType` 通过 `.skillsResource(...)` 被赋予技能资源
- **THEN** 子Agent在被委派执行时可在隔离上下文加载并执行技能，不污染主Agent上下文窗口

#### Scenario: 模式 C skill 文本驱动委派

- **WHEN** 某个 `SKILL.md` 正文指示"当遇到代码审查任务时调用 task 工具委派 `code-reviewer` 子Agent"，且主Agent已挂载 TaskTool
- **THEN** 模型按 skill 指示调用 task 工具并将 `subagent_type` 指定为 `code-reviewer`，委派对应子Agent执行
