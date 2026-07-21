## Context

Agent Platform 是一个全新工程，`openspec/specs` 目录当前为空，不存在既有 capability。本变更的目标是初始化一个基于 Spring AI 2.x 的项目骨架，集成 spring-ai-agent-utils 以加载 skills、subagent。

技术栈已由 `openspec/config.yaml` 声明：JDK 21、Spring Boot 4.1.x、Spring AI 2.0.0 GA、spring-ai-agent-utils 0.7.0、Maven。关键背景事实：

- Spring AI 2.0.0 GA 已于 2026-06-12 发布，面向 Spring Boot 4.0/4.1 + Spring Framework 7.0，强制 Java 21+
- spring-ai-agent-utils 0.7.0 是 Spring 官方点名的社区项目（groupId `org.springaicommunity`），明确支持 Spring AI 2.0.0，提供 Skills / SubAgent / Task / AskUserQuestion 等原语
- 当前 workspace 下无 pom.xml、无 src/、无启动类，是一个完全空白的起点

## Goals / Non-Goals

**Goals:**

- 搭建可跑的最小骨架：`mvn spring-boot:run` 能成功启动
- 装配 `SkillsTool` Bean，启动日志可见 skillsRoot 路径，skills 目录为空时不阻断启动
- 提供 `POST /chat` 最小端点，能返回 OpenAI 模型响应
- API Key 通过环境变量 + `application-local.yml` 注入，不硬编码进 git 跟踪文件

**Non-Goals:**

- 不实现具体 skill 内容（骨架阶段 `skills/` 目录为空占位）
- 不实现 subagent 编排逻辑（仅引入 agent-utils 依赖，预留能力）
- 不做向量存储 / RAG / 对话记忆持久化
- 不做多模块拆分（单模块起步，后续膨胀再拆）
- 不追 agent-utils 最新版（锁定 0.7.0，升级作为后续优化任务）

## Decisions

### 决策 1：版本组合 Boot 4.1.x + Spring AI 2.0.0 GA + agent-utils 0.7.0

- **理由**：Spring 官方明确 2.0.0 GA 面向 Boot 4.0/4.1；agent-utils 0.7.0 明确声明支持 2.0.0；与 config.yaml 声明的 Spring AI 2.X 一致
- **备选 A**：Boot 4.0.x——可用，但 4.1 是最新生产底座（gRPC 原生支持、观测增强），优先 4.1
- **备选 B**：Spring AI 1.1.x + Boot 3.5.x——与 config.yaml 声明的 2.X 不符，排除
- **备选 C**：agent-utils 追最新版——搜索未确认到比 0.7.0 更高的稳定版号，锁定 0.7.0 保证可跑

### 决策 2：单模块 Maven 结构

- **理由**：骨架阶段复杂度低，单模块够用；后续若 skills/subagent 膨胀可拆分为 `agent-core` / `agent-skills` / `agent-web`
- **备选**：多模块——骨架阶段过度设计，增加构建配置负担

### 决策 3：Skills 加载源 classpath:skills 起步 + 可配置外部覆盖

- **理由**：classpath 便于打包分发；外部目录便于运行时调整不需重新打包
- **实现**：`AgentUtilsProperties` 绑定 `spring.ai.agent-utils.skills-root`，默认 `classpath:skills`，支持 `file:` 前缀覆盖

### 决策 4：agent-utils 0.7.0 锁定（不追最新）

- **理由**：0.7.0 是已验证兼容 Spring AI 2.0.0 的版本；"最新版"未在调研中确认到更高稳定版号
- **后续**：实现完成后到 Maven Central 查询是否有 0.8+，作为优化任务记录

### 决策 5：配置安全 —— 环境变量 + application-local.yml

- **理由**：避免密钥硬编码进 git；`application-local.yml` 纳入 `.gitignore`，仅提供 `.example` 模板
- **实现**：`application.yml` 引用 `${OPENAI_API_KEY}` 占位；`application-local.yml.example` 提供本地配置示例

### 决策 6：Maven 仓库声明

- **理由**：spring-ai-agent-utils 0.7.0 发布在 Spring milestone 仓库，Maven Central 默认不包含
- **实现**：pom.xml 显式声明 `spring-milestones` 仓库（`https://repo.spring.io/milestone`）

## Risks / Trade-offs

- **[agent-utils 0.7.0 的 SkillsTool API 在 2.0 下可能有细微变化]** → 实现时以实际编译/运行为准，必要时调整 Bean 装配代码
- **[Spring Boot 4.1 自动配置模块化，第三方库兼容性需验证]** → 骨架仅使用官方 starter + 一个社区包，风险低；若 agent-utils 与 Boot 4.1 冲突，回退 Boot 4.0.x
- **[skills 目录为空时 SkillsTool 是否抛异常]** → 实现时 spike 验证；若抛异常，加空目录占位 `.gitkeep` 或在 Configuration 中加容错判断
- **[Maven milestone 仓库可达性]** → pom.xml 显式声明仓库；若网络受限，可配置镜像

## Open Questions

- agent-utils 是否存在比 0.7.0 更新的稳定版？（实现完成后到 Maven Central 验证，记录为后续优化任务）
- `SkillsTool` 在 skills 目录为空时的确切行为是抛异常还是返回空回调？（实现时 spike 验证，决定是否需要容错逻辑）
