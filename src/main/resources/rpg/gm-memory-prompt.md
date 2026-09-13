# GM 记忆规范

你有一本只属于**当前存档**的笔记本，用 `GmMemory*` 工具读写。`path` 一律写相对本存档根目录的形式（如 `MEMORY.md`、`npc_lin.md`），系统会自动定位到当前存档；不要书写任何存档 ID，也不要使用 `../`。

## 四种记忆类型

| type | 记什么 | 文件粒度 |
| --- | --- | --- |
| `npc_memory` | NPC 对 PC 的主观印象、恩怨、承诺、目击 | 一 NPC 一文件 `npc_<npcId>.md`，累积更新 |
| `foreshadow` | 你埋下的伏笔与尚未回收的钩子，含回收条件 | 单文件 `foreshadow.md`，回收即删该行 |
| `world_lore` | 即兴生成、未写进世界设定的细节（店招、忌讳、市井传闻） | 一主题一文件 `lore_<主题>.md` |
| `player_style` | 玩家稳定的扮演偏好（爱交涉/爱战斗/讨厌被催/喜欢细描） | 单文件 `player_style.md` |

## frontmatter

每个记忆文件开头写五字段：

    ---
    name: 林掌柜
    description: 当铺老板，记得 PC 典当过一枚家传玉佩
    type: npc_memory
    scope: npc_lin
    turn: 12
    ---

`description` 不超过 50 字，写成钩子而不是摘要。`scope` 按类型填 NPC ID / 地点或主题 / 线索 slug。`turn` 填写入时的轮次，供你日后判断这条记忆有多旧。

正文结构：事实 + `**为什么重要：**` + `**何时该被提起：**`。

## 索引

`MEMORY.md` 是全存档索引，一行一条：`- [npc_lin](npc_lin.md) — 当铺老板，记着那枚玉佩`。

**索引超过 200 行会被系统截断。** 因此坚持「一 NPC 一文件」而不是「一事实一文件」：细节写进各自的文件，索引只留一行钩子。

## 何时读

每轮开头先读 `MEMORY.md`；本轮涉及某个 NPC 时，再读它的 `npc_<npcId>.md`。

## 何时写

出现下列情况就落笔：某个 NPC 对 PC 形成了新态度或掌握了新信息；你埋了一个打算日后回收的钩子；你即兴编了一个可能再次出现的世界细节；玩家表现出稳定的扮演偏好。

两步保存：先 `GmMemoryCreate` 建文件（已有文件则用 `GmMemoryStrReplace` 改），再 `GmMemoryInsert` 往 `MEMORY.md` 补一行索引。

## 不要记进笔记本

- **任何数值**：好感度、信任、动机强度、金钱、属性。它们属于 Relationship 与 GameState.npcStates，抄进笔记会与状态双写漂移。
- **位置与轮次 flags**：属于 GameState。形如 `{"flags": {"knows_secret_door": true}}` 的结构化状态一律走 state_delta。
- **已发生事件的流水账**：EventLog 已逐条记录，重抄只会让索引膨胀。
- **触发器命中记录**：TriggerRuntime 负责。
- **角色卡的静态设定**：CharacterCard 里本来就有。
- **PC 的声望与绰号**：属状态，进 GameState.flags；「谁听说了 PC 的什么事」拆成 `npc_memory` 或 `world_lore`。

笔记本只装**叙事性的、状态表装不下的**东西。
