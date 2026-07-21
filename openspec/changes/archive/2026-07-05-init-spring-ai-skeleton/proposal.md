## Why

Agent Platform 智能代理平台目前是一个空白工程，需要初始化一个基于 Spring AI 2.x 的项目骨架，作为后续开发 skills 加载、subagent 编排等智能体能力的基础底座。Spring AI 2.0.0 GA 已于 2026-06-12 发布，配套的 spring-ai-agent-utils 0.7.0 提供了 Skills / SubAgent / Task 等智能体原语，现在正是搭建底座的时机。

## What Changes

- 新建 Maven 单模块工程（`pom.xml`），引入 Spring Boot 4.1.x parent、Spring AI 2.0.0 BOM、`spring-ai-starter-model-openai`、`spring-ai-agent-utils` 0.7.0
- 新增启动类 `AgentPlatformApplication`，作为 Spring Boot 应用入口
- 新增 `AgentUtilsConfiguration` 配置类，装配 `SkillsTool` Bean（classpath:skills 起步，支持外部目录覆盖）
- 新增 `ChatController` 最小对话端点，用于验证骨架可跑
- 新增 `application.yml`（公共配置）+ `application-local.yml.example`（本地密钥示例，真实文件纳入 `.gitignore`）
- 新增 `src/main/resources/skills/` 空目录占位，预留 skills 加载根目录

## Capabilities

### New Capabilities

- `spring-ai-bootstrap`: Spring AI 2.x 项目骨架初始化能力，覆盖依赖管理、启动类、SkillsTool 装配、最小可跑验证

### Modified Capabilities

<!-- specs 目录当前为空，无既有 capability 需要修改 -->

（无）

## Impact

- **新增代码**：`pom.xml`、`AgentPlatformApplication`、`AgentUtilsConfiguration`、`ChatController`、`application.yml`、`application-local.yml.example`、`.gitignore`
- **新增依赖**：`spring-boot-starter-web`、`spring-ai-starter-model-openai`、`spring-ai-agent-utils:0.7.0`
- **配置依赖**：需要 `OPENAI_API_KEY` 环境变量（本地通过 `application-local.yml` 注入，不硬编码）
- **仓库配置**：pom.xml 需声明 Spring milestone 仓库（agent-utils 0.7.0 发布在里程碑仓库）
- **破坏性变更**：无（全新工程，不存在既有代码受影响）
