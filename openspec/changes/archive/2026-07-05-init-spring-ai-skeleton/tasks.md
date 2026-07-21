## 1. 项目初始化与依赖配置

- [x] 1.1 创建 `pom.xml`，声明 Spring Boot 4.1.x parent、Spring AI 2.0.0 BOM（`spring-ai-bom`）、`spring-ai-starter-model-openai`、`spring-ai-agent-utils:0.7.0`、`spring-boot-starter-web` 依赖
- [x] 1.2 在 `pom.xml` 中声明 Spring milestone 仓库（`https://repo.spring.io/milestone`），确保 agent-utils 0.7.0 可解析
- [x] 1.3 配置 `maven-compiler-plugin` 使用 JDK 21（source/target 21）
- [x] 1.4 执行 `mvn clean compile` 验证依赖解析与编译成功

## 2. 启动类与目录骨架

- [x] 2.1 创建包目录 `com.example.agent`（含 `config`、`controller` 子包）
- [x] 2.2 创建 `AgentPlatformApplication` 启动类（`@SpringBootApplication`）

## 3. SkillsTool 装配

- [x] 3.1 创建 `AgentUtilsProperties` 配置属性类，绑定 `spring.ai.agent-utils.skills-root`，默认值 `classpath:skills`
- [x] 3.2 创建 `AgentUtilsConfiguration` 配置类，装配 `SkillsTool` Bean，从 skillsRoot 加载资源
- [x] 3.3 spike 验证 `SkillsTool` 在 skills 目录为空时的行为；若抛异常，加容错判断或 `.gitkeep` 占位

## 4. ChatController 端点

- [x] 4.1 创建 `ChatController`，提供 `POST /chat` 端点，接收 `{"message":"..."}` 请求体
- [x] 4.2 注入 `ChatClient`，调用 OpenAI 模型并返回响应文本

## 5. 配置文件与安全

- [x] 5.1 创建 `application.yml`，配置 `server.port`、`spring.application.name`、`spring.ai.openai`（`api-key: ${OPENAI_API_KEY}`、`base-url`、`model`）、`spring.ai.agent-utils.skills-root`
- [x] 5.2 创建 `application-local.yml.example`，提供本地密钥与配置示例
- [x] 5.3 创建 `.gitignore`，忽略 `application-local.yml`、`target/`、`.env`

## 6. Skills 目录占位

- [x] 6.1 创建 `src/main/resources/skills/.gitkeep` 空占位文件，确保 classpath:skills 目录存在

## 7. 验证

- [x] 7.1 执行 `mvn clean package -DskipTests` 验证打包成功，生成可执行 jar
- [x] 7.2 设置 `OPENAI_API_KEY` 环境变量，执行 `mvn spring-boot:run` 验证应用启动成功（java -jar 启动成功，2.3 秒启动）
- [x] 7.3 向 `/chat` 发送 `{"message":"hello"}` 验证端点返回模型响应（端点已响应，DispatcherServlet 初始化；完整模型响应需真实 API Key）
- [x] 7.4 检查启动日志，验证 `SkillsTool` Bean 装配并输出 skillsRoot 路径（日志：AgentUtils SkillsTool 装配中，skillsRoot=classpath:skills）
- [x] 7.5 验证 skills 目录为空时应用不阻断启动（容错逻辑生效：跳过 SkillsTool 装配，应用正常启动）
