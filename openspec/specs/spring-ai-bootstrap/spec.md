# spring-ai-bootstrap

Spring AI 2.x 项目骨架初始化能力，覆盖依赖管理、启动类、SkillsTool 装配、最小可跑验证。

## Requirements

### Requirement: 项目可通过 Maven 构建

系统 SHALL 提供一个可被 Maven 成功编译打包的单模块工程，`pom.xml` 声明 Spring Boot 4.1.x parent、Spring AI 2.0.0 BOM、`spring-ai-starter-model-openai`、`spring-ai-agent-utils:0.7.0`，并显式声明 Spring milestone 仓库。

#### Scenario: 执行 mvn compile 成功

- **WHEN** 开发者在项目根目录执行 `mvn clean compile`
- **THEN** 构建成功退出，无编译错误，依赖均能从声明的仓库解析

#### Scenario: 执行 mvn package 生成可执行 jar

- **WHEN** 开发者执行 `mvn clean package -DskipTests`
- **THEN** 生成可执行 jar 文件，构建成功退出

### Requirement: 应用可启动

系统 SHALL 提供一个 Spring Boot 启动类 `AgentPlatformApplication`，执行 `mvn spring-boot:run` 能正常启动应用并监听配置端口。

#### Scenario: 启动应用成功

- **WHEN** 开发者执行 `mvn spring-boot:run` 且已通过环境变量或 `application-local.yml` 配置 `OPENAI_API_KEY`
- **THEN** 应用启动成功，日志显示 Tomcat 监听配置端口，无启动异常

### Requirement: SkillsTool Bean 装配成功

系统 SHALL 装配 spring-ai-agent-utils 的 `SkillsTool` Bean，启动时从配置的 skills 根目录加载技能资源。skills 目录为空时 SHALL NOT 阻断应用启动。

#### Scenario: skills 目录为空时启动成功

- **WHEN** skills 根目录 `classpath:skills` 下无任何技能文件（仅 `.gitkeep` 占位）
- **THEN** 应用正常启动，`SkillsTool` Bean 装配成功，启动日志输出 skillsRoot 路径

#### Scenario: 配置外部 skills 目录

- **WHEN** 配置 `spring.ai.agent-utils.skills-root=file:./external-skills` 且该目录存在
- **THEN** `SkillsTool` 从外部目录加载，不依赖 classpath

### Requirement: 最小对话端点可响应

系统 SHALL 提供 `POST /chat` 端点，接收消息文本，调用 OpenAI 模型并返回响应。

#### Scenario: 发送消息获得模型响应

- **WHEN** 客户端向 `/chat` 发送 POST 请求，请求体包含 `{"message":"hello"}`
- **THEN** 端点返回 HTTP 200，响应体包含模型生成的回复文本

### Requirement: API 密钥不硬编码

系统 SHALL 通过环境变量或 `application-local.yml` 注入 OpenAI API Key，密钥 SHALL NOT 出现在任何被 git 跟踪的文件中。

#### Scenario: 密钥从环境变量注入

- **WHEN** 设置环境变量 `OPENAI_API_KEY` 并启动应用
- **THEN** `application.yml` 中 `${OPENAI_API_KEY}` 占位被正确解析，应用可调用 OpenAI 模型

#### Scenario: application-local.yml 被 git 忽略

- **WHEN** 开发者创建 `application-local.yml` 写入真实密钥
- **THEN** `.gitignore` 规则使其不被 git 跟踪，仓库中仅存在 `application-local.yml.example` 模板

### Requirement: skills 根目录可配置

系统 SHALL 通过 `spring.ai.agent-utils.skills-root` 配置项指定 skills 加载根目录，默认值 `classpath:skills`，支持 `classpath:` 与 `file:` 前缀。

#### Scenario: 使用默认 classpath 加载

- **WHEN** 未显式配置 `spring.ai.agent-utils.skills-root`
- **THEN** 系统使用默认值 `classpath:skills` 加载技能资源

#### Scenario: 覆盖为外部文件目录

- **WHEN** 配置 `spring.ai.agent-utils.skills-root=file:/data/skills`
- **THEN** 系统从指定文件系统路径加载技能，不再读取 classpath 默认目录
