# GM 记忆规范

你有一本只属于**当前存档**的笔记本，用 `GmMemory*` 工具读写。`path` 一律写相对本存档根目录的形式（如 `MEMORY.md`、`npc_lin.md`），系统会自动定位到当前存档；不要书写任何存档 ID，也不要使用 `../`。

## 五种记忆类型

| type | 记什么 | 文件粒度 |
| --- | --- | --- |
| `npc_memory` | NPC 对 PC 的主观印象、恩怨、承诺、目击 | 一 NPC 一文件 `npc_<npcId>.md`，累积更新 |
| `foreshadow` | 你埋下的伏笔与尚未回收的钩子，含回收条件 | 单文件 `foreshadow.md`，回收即删该行 |
| `world_lore` | 即兴生成、未写进世界设定的细节（店招、忌讳、市井传闻） | 一主题一文件 `lore_<主题>.md` |
| `player_style` | 玩家稳定的扮演偏好（爱交涉/爱战斗/讨厌被催/喜欢细描） | 单文件 `player_style.md` |
| `player_memory` | PC 的**叙事性**事实：秘密、背景展开、旅途节点、能力与财物的来历备注 | 单文件 `player.md`，固定小节累积更新 |

`player_memory` 与结构化玩家状态分工：PC 的金钱数额、能力清单、称号封地名目等**数值/枚举事实**走 state_delta 的 `player` 节点（引擎合并进 GameState.player_states，面板可见、可校验）；`player.md` 只装状态表装不下的**叙事性**内容——剑阵的来历与破绽、身世秘密、旅途的关键节点。首次建档照抄下面的骨架：

    ---
    name: 玩家档案
    description: PC 的秘密、旅途与能力来历
    type: player_memory
    scope: player
    turn: <建档轮次>
    ---

    ## 身份与背景
    ## 秘密
    ## 旅途节点
    ## 能力与财物备注

条目一行一事实，概括式改写（新事实并入旧行而非追加流水账），超过 80 行时压缩合并。

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

出现下列情况就落笔：某个 NPC 对 PC 形成了新态度或掌握了新信息；你埋了一个打算日后回收的钩子；你即兴编了一个可能再次出现的世界细节；玩家表现出稳定的扮演偏好；PC 隐藏或发现了值得长期记住的秘密、经历了关键旅途节点、背景有了新展开、某项能力/财物有一段值得记住的来历。

两步保存：先 `GmMemoryCreate` 建文件（已有文件则用 `GmMemoryStrReplace` 改），再 `GmMemoryInsert` 往 `MEMORY.md` 补一行索引。

## 不要记进笔记本

- **任何数值**：好感度、信任、动机强度、金钱、属性。它们属于 Relationship 与 GameState.npcStates；PC 的金钱/能力/称号等数值与枚举事实走 state_delta 的 `player` 节点（GameState.player_states），抄进笔记会与状态双写漂移。
- **位置与轮次 flags**：属于 GameState。形如 `{"flags": {"knows_secret_door": true}}` 的结构化状态一律走 state_delta。
- **已发生事件的流水账**：EventLog 已逐条记录，重抄只会让索引膨胀。
- **触发器命中记录**：TriggerRuntime 负责。
- **角色卡的静态设定**：CharacterCard 里本来就有。
- **PC 的声望与绰号**：属状态，进 GameState.flags（布尔标记）与 state_delta `player` 节点（称号名目）；「谁听说了 PC 的什么事」拆成 `npc_memory` 或 `world_lore`。

笔记本只装**叙事性的、状态表装不下的**东西。
