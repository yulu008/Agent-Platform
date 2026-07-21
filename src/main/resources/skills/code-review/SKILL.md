---
name: code-review
description: 代码审查技能，指导如何进行代码审查并委派给 code-reviewer 子Agent 执行
---
# 代码审查技能

本技能演示 **模式 C：skill 文本驱动委派**。当用户请求代码审查时，按以下流程委派给 `code-reviewer` 子Agent。

## 委派流程

1. **识别审查目标**：从用户请求中提取待审查的代码路径或模块
2. **调用 task 工具**：使用 `subagent_type=code-reviewer` 委派子Agent
3. **构造详细 prompt**：在 prompt 中包含
   - 待审查的文件或目录路径
   - 审查重点（质量、安全、性能等）
   - 期望的输出格式
4. **等待结果**：子Agent 返回结构化审查报告后，向用户摘要呈现

## 委派示例

```
task(
  subagent_type="code-reviewer",
  description="审查 src/main/java/com/example/agent/governance/ 目录下的限流装饰器实现",
  prompt="请审查 RateLimitingSubagentExecutor.java 与 GovernedTaskRepository.java 的代码质量，重点关注：1) 并发安全性 2) 资源泄漏 3) 异常处理。返回结构化审查报告。"
)
```

## 注意事项

- 主Agent **必须已挂载 TaskTool**（模式 A）才能按本技能指示委派子Agent
- 子Agent 在隔离上下文执行，不共享主Agent对话历史
- 委派是**无状态**的，prompt 必须自包含完整任务描述
