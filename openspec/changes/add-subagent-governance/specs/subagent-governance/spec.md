## ADDED Requirements

### Requirement: 子Agent最大并行度限流

系统 SHALL 通过装饰 `SubagentExecutor` SPI 限制同时执行的子Agent数量，最大并行度由 `spring.ai.agent-utils.subagent.max-parallelism` 配置，默认 4。当并发子Agent达到上限时，新的委派请求 SHALL 在获取许可超时后返回友好错误文本给主Agent，而非无限阻塞或抛出异常。

#### Scenario: 并行度达上限时返回友好错误

- **WHEN** 已有 4 个子Agent正在执行（达到 `max-parallelism` 上限），主Agent再次调用 task 工具委派新子Agent
- **THEN** 装饰器 `tryAcquire` 在超时时间内未获得许可，返回结构化错误文本（如 `[subagent-governance] 并行度超限，当前最大 4`）作为 task 工具结果回传模型，不抛出异常中断主对话

#### Scenario: 子Agent完成后释放并行许可

- **WHEN** 某个子Agent执行完成（正常结束或超时被取消）
- **THEN** 装饰器释放 `Semaphore` 许可，后续委派请求可获取许可继续执行

### Requirement: 子Agent存活时长限流

系统 SHALL 限制单次子Agent执行的最大存活时长，由 `spring.ai.agent-utils.subagent.lifespan` 配置，默认 120 秒。前台执行（`run_in_background != true`）超时 SHALL 通过 `Future.get(lifespan)` 触发 `cancel(true)` 并返回超时错误；后台执行（`run_in_background == true`）SHALL 通过 TTL 清扫器定期取消超过存活时长的 `BackgroundTask`。

#### Scenario: 前台执行超时被取消

- **WHEN** 子Agent前台执行时长超过配置的 `lifespan`（默认 120 秒）
- **THEN** 装饰器 `Future.get(lifespan)` 超时后 `cancel(true)` 中断执行，返回超时错误文本给主Agent，并释放并行许可

#### Scenario: 后台任务被 TTL 清扫器取消

- **WHEN** 子Agent以 `run_in_background=true` 后台执行，且执行时长超过 `lifespan`
- **THEN** TTL 清扫器周期性扫描 `TaskRepository`，对超过存活时长的 `BackgroundTask` 调用 `cancel(true)`，并在独立审计日志记录取消事件

### Requirement: 子Agent执行分离日志记录

系统 SHALL 将子Agent执行审计日志写入独立文件 `logs/subagent-audit.log`，使用独立 logger `subagent.audit` 且 `additivity=false`（不重复写主日志）。每条审计记录 SHALL 通过 MDC 携带 `subagentType` / `taskId` / `parentId` 上下文，并记录执行耗时与结果状态（成功/超时/超限/错误）。

#### Scenario: 子Agent执行写入独立审计日志

- **WHEN** 子Agent被委派执行（无论成功、超时或超限）
- **THEN** 独立 logger `subagent.audit` 在 `logs/subagent-audit.log` 写入一条审计记录，包含 MDC 上下文（`subagentType` / `taskId` / `parentId`）、执行耗时与结果状态，且该记录不出现在主应用日志文件中

#### Scenario: 独立日志文件按策略滚动

- **WHEN** `logs/subagent-audit.log` 达到滚动阈值
- **THEN** `RollingFileAppender` 按配置策略滚动归档，不影响主应用日志的滚动行为

### Requirement: 限流参数可配置

系统 SHALL 通过 `spring.ai.agent-utils.subagent.*` 配置前缀暴露限流参数：`max-parallelism`（最大并行度，默认 4）、`lifespan`（存活时长，默认 `120s`）。参数 SHALL 支持运行时通过配置文件调整，未配置时使用默认值不阻断启动。

#### Scenario: 使用默认限流参数启动

- **WHEN** 未显式配置 `spring.ai.agent-utils.subagent.*` 任何参数
- **THEN** 系统以 `max-parallelism=4`、`lifespan=120s` 默认值启动，启动日志输出生效的限流参数

#### Scenario: 覆盖限流参数

- **WHEN** 配置 `spring.ai.agent-utils.subagent.max-parallelism=8` 与 `spring.ai.agent-utils.subagent.lifespan=60s`
- **THEN** 装饰器以 `max-parallelism=8`、`lifespan=60s` 生效，启动日志输出覆盖后的参数值
