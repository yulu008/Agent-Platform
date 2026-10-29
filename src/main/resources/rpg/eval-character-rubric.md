# 人物一致性评估判据（rubric）

本判据是「人物一致性」维度 LLM-as-judge 的评分依据。版本号 = 本文件内容 hash（随内容变更即换版，进 judge 指纹）。

## 一、判定总则

- verdict 仅三档：`pass`（合格）/ `borderline`（存疑，不计失败率）/ `fail`（崩坏）。
- 每条 verdict 必须附证据 `{turn, span}`：turn = 叙述轮次（数字），span = 引用原文连续片段。无证据或证据无法在输入文本中定位的判分将被系统作废。
- rationale 用中文，不超过 50 字，直接给出判档理由。
- L1 规则引擎预警只是「疑似矛盾」，须核对原文后裁决——你的裁决覆盖预警（预警成立则 fail，预警系误报或可由设定解释则不计）。

## 二、密度基准表（静态层：设定自洽与丰满度）

| 字段 | 合格基准 |
|---|---|
| identity | 职位、星衣/圣衣绑定、称号三要素融于一句话 |
| personality | ≥3 个特质短语 + ≥1 情境性反差（同一人格的不同侧面有触发条件） |
| background | 3-4 句、含因果链、含 ≥1 未决钩子（供剧情推演的悬念） |
| eraNote | 年龄 + 势力处境 + 当前战略悬念三要素 |
| aliases | ≥3 个（含称号与历史别名） |

CharacterCard（工坊自建 NPC）适配：评三件套（identity/personality/background）齐全度与上述密度基准，不因缺失星衣绑定等预设世界专属字段而判 fail；structured 中的 motivation/speechStyle/knowledge 分别视同 personality 情境反差、言语风格、eraNote 处境的对应项。

## 三、情境人格面判据（speech-style / motivation 子维度核心）

不评「性格单一稳定」，评「行为落在档案定义的哪个人格面，切面与档案触发条件是否匹配」：

- 档案载明「战阵之上则如怒涛」→ 战阵叙述如怒涛 ✓；日常宴饮亦如怒涛 ✗（情境错位）
- 判 fail 的标准：行为与档案所有人格面均矛盾；或切面触发条件完全错位且无演化支撑
- 人格演化须有设定支撑：档案「温厚谦和」者后期表现阴鸷，须 background / 叙述因果链可见支撑，否则判漂移（跨窗对照：早窗与晚窗表现风格突变无中转事件）
- 动态层必含早窗/晚窗对照：早窗冷峻寡言、晚窗多言热络且无支撑事件 = 漂移预警，按子维度严重度判 fail 或 borderline

## 四、四子维度判据

- `hard-facts`：档案结构化字段（星座/阵营/年龄/家名归属/身份绑定）与叙述冲突 → fail；L1 预警定位的冲突须逐条核对原文。
- `motivation`：npc_states 动机轨迹与行为的一致性（动机值断崖无叙事支撑、行为与主导动机长期背离）。
- `relations`：trust_* 等关系状态键轨迹与互动叙事的一致性（关系突变无互动事件支撑）。
- `speech-style`：情境人格面匹配 + 称谓习惯 + 语言风格演化有无支撑（汉字指称、语体习惯）。

## 五、输出契约

仅输出一个 JSON 对象（不要围栏、不要解释文字、不要多余字段）：

```json
{
  "judgments": [
    {"sub_dimension": "hard-facts", "verdict": "pass", "rationale": "≤50字中文理由", "evidence": [{"turn": 3, "span": "叙述原文连续片段"}]},
    {"sub_dimension": "motivation", "verdict": "borderline", "rationale": "…", "evidence": [{"turn": 5, "span": "…"}]},
    {"sub_dimension": "relations", "verdict": "fail", "rationale": "…", "evidence": [{"turn": 7, "span": "…"}]},
    {"sub_dimension": "speech-style", "verdict": "pass", "rationale": "…", "evidence": [{"turn": 2, "span": "…"}]}
  ]
}
```

- 四条齐备，每子维度一条；`sub_dimension` 取值限定上节四值。
- 动态层：evidence 的 `turn` 必须是输入叙述窗中的真实轮次，`span` 必须是该轮文本的连续子串（引原文，不得改写）。
- 静态层：evidence 的 `turn` 填 `-1`，`span` 为设定包字段原文片段。
- verdict 非三档值、span 无法定位的输出会被系统作废，该 case 作废计数并可能触发重试。
