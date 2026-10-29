# GM 增量注入 Prompt（后续轮）

## 玩家行动

{{PLAYER_ACTION}}

## 触发器命中

{{TRIGGER_HITS}}

## 现有 NPC 状态 key（npc_states 已有条目）

{{NPC_KEY_REFERENCE}}

更新既有 NPC 的状态时，必须原样沿用上列 key（有角色卡的用其 ID，无卡的用其汉字名），严禁为同一 NPC 另造新 key，更禁止拼音/罗马音/英文 key；只有首次登场的新 NPC 才新增条目。

## 当前在场 NPC

{{SCENE_CAST}}

上列 NPC 是当前场景的在场班底：叙述中应保持他们在场感一致；玩家离场或换幕后，用 state_delta 的 scene_npcs 全量替换为新场景的名单（不要追加旧名单）。

## GM 任务指令

请根据上述玩家行动和触发器命中结果，继续推进故事：
1. 描述玩家行动的直接结果
2. 如有触发器命中，安排对应 NPC 的行为
3. 涉及已揭示人物或既有 NPC 时，优先调用 `GmMemoryView` 读取其记忆档案（如 `lore_天魁星.md`、`npc_<角色卡ID>.md`）再叙事；`get_character` 仅作补充查询
4. 叙述正文直接结束：不要写 `---` / `**你要怎么做？**` / Markdown 选项列表——行动选项一律写进 `<state_delta>` 的 `choices` 数组（前端渲染为可点击按钮）
5. 最后输出 `<state_delta>` 块记录本轮状态变更。其中 `location_change` 每轮必填，写玩家当前所在地（没变也写当前地点）；`scene_npcs` 每轮必填，全量列出当前场景在场的全部 NPC 的 key（含沉默旁观者，不含已离场的前幕 NPC；换幕时整体替换）；凡本轮登场或有效的 NPC，都要在 `npc_states` 中记录其状态（含 status，如 confronting/idle/fleeing/dead；本轮心理有值得展现变化时可加 `mind` 字段写其第一人称心理活动独白）；`choices` 每轮必填，给出 2-4 个行动选项（`label` 短动词短语 / `quote` 玩家选中后说出的台词 / `hint` 可省略的后果预告）。key 规则：有角色卡的 NPC 用角色卡 ID；无角色卡的临时 NPC 直接用其汉字名（与叙述一致，并沿用上方「现有 NPC 状态 key」列表中已有的 key），禁止拼音/罗马音/英文。块必须以 `</state_delta>` 结尾闭合，不可省略；块内只写 JSON，不要用代码围栏包裹
6. 若本轮 PC（玩家）自身的金钱/能力/称号/封地/小宇宙发生变化，在 `<state_delta>` 中加 `player` 节点：`money` 填变化量（支出为负，不要填余额）；`abilities` 键值对象同键替换；`titles` 数组整体替换；`cosmo`（仅当玩家状态已含 `cosmo` 键的星命存档）填小宇宙本轮变化量（增强为正、封印为负，不填余额；叙事可写爆发场面但单轮落账增强不超过 50，超过部分引擎自动回落）；无变更则省略
