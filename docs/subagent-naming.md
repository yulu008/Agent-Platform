# 子Agent命名规范

本文档定义 Agent Platform 中子Agent（SubAgent）的命名约定，确保 `SKILL.md` 文本驱动委派（模式 C）时主Agent能正确匹配子Agent名称。

## 命名规则

1. **kebab-case**：子Agent名称使用小写字母与连字符，如 `code-reviewer`、`general-purpose`
2. **唯一性**：每个子Agent名称在 agents 目录内必须唯一，重复时启动日志告警
3. **语义化**：名称应反映子Agent职责，如 `code-reviewer`（代码审查）、`test-runner`（测试运行）
4. **与 SKILL.md 一致**：`SKILL.md` 中 `subagent_type` 参数必须与子Agent定义的 `name` 字段完全一致

## 定义位置

子Agent markdown 定义存放在 `src/main/resources/agents/`，每个 `.md` 文件一个子Agent，文件名建议与 `name` 字段一致。

## frontmatter 字段

| 字段        | 必填 | 说明                                         |
|-------------|------|----------------------------------------------|
| `name`      | 是   | 子Agent唯一标识，kebab-case                  |
| `description` | 是   | 职责描述，主Agent据此判断何时委派            |
| `tools`     | 否   | 允许使用的工具白名单，逗号分隔               |
| `model`     | 否   | 模型路由标识，默认 `default`                 |

## 示例

```yaml
---
name: code-reviewer
description: 代码审查专家，审查代码质量与潜在问题
tools: Read, Grep, Glob
model: default
---
```

在 `SKILL.md` 中委派时：

```
task(subagent_type="code-reviewer", description="...", prompt="...")
```

## 校验

- 启动时 `ClaudeSubagentReferences.fromRootDirectory` 加载所有 `.md` 文件
- 名称重复时以最后加载的定义为准（未来可增加启动时强校验）
- `SKILL.md` 中的 `subagent_type` 若无匹配子Agent，task 工具返回错误
