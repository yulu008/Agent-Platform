package com.luyu.agent.rpg.eval.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 静态层 EvalCase 装配器（content-eval tasks 3.1，design D6）。
 * <p>
 * 三源归一为设定包（{@link EvalCase.SettingPack}）：命定人物档案（真源
 * preset-adventure.md，物化于 rpg_destined_figure）、NPC 人物卡（工坊手作）、
 * 冥星武将名录条目（rpg_star_incarnation 世界模板行）。静态层评估的被评产物
 * 即设定包本身（档案自洽：密度基准 + 硬事实自洽 + 钩子断言）。
 * <p>
 * 指称词表 = displayName 头位 + aliases 拆分（中/英文逗号均支持——preset md
 * 用中文逗号，工坊手作卡历史数据可能混用），供动态层筛出场轮与 L1 检查复用。
 */
@Service
public class StaticEvalCaseAssembler {

    private static final Logger log = LoggerFactory.getLogger(StaticEvalCaseAssembler.class);

    /** JSON 序列化（设定包/unit，只写线程安全实例） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** NPC 卡类型常量（player 卡不参评——被评的是 NPC 一致性） */
    private static final String CARD_TYPE_NPC = "npc";

    private final RpgDestinedFigureRepository figureRepo;
    private final RpgCharacterCardRepository cardRepo;
    private final RpgStarIncarnationRepository starRepo;

    public StaticEvalCaseAssembler(RpgDestinedFigureRepository figureRepo,
                                   RpgCharacterCardRepository cardRepo,
                                   RpgStarIncarnationRepository starRepo) {
        this.figureRepo = figureRepo;
        this.cardRepo = cardRepo;
        this.starRepo = starRepo;
    }

    /**
     * 装配某世界的全部静态层评估单元（装配序：命定 → NPC 卡 → 冥星名录，
     * 各源内按存储序）。任一源读取失败跳过该源（warn 不中断——三源独立降级）。
     */
    public List<EvalCase> assemble(String worldId) {
        List<EvalCase> cases = new ArrayList<>();
        cases.addAll(destinedCases(worldId));
        cases.addAll(npcCardCases(worldId));
        cases.addAll(starLoreCases(worldId));
        return cases;
    }

    private List<EvalCase> destinedCases(String worldId) {
        List<EvalCase> cases = new ArrayList<>();
        try {
            for (DestinedFigure f : figureRepo.findByWorldId(worldId)) {
                List<String> aliases = aliasList(f.getDisplayName(), f.getAliases());
                Map<String, Object> structured = new LinkedHashMap<>();
                structured.put("faction", f.getFaction());
                structured.put("constellation", f.getConstellation());
                structured.put("starRank", f.getStarRank());
                structured.put("eraNote", f.getEraNote());
                EvalCase.SettingPack pack = new EvalCase.SettingPack(
                        toJson(structured), f.getIdentity(), f.getPersonality(),
                        f.getBackground(), null, aliases);
                cases.add(new EvalCase(EvalRun.LAYER_STATIC, EvalCase.SOURCE_DESTINED,
                        f.getFigureKey(), f.getDisplayName(),
                        unitJson(EvalCase.SOURCE_DESTINED, f.getFigureKey(), worldId, null),
                        toJson(pack), aliases, null, null, null, null, null, null));
            }
        } catch (Exception e) {
            log.warn("命定档案源装配失败（跳过该源）: worldId={}", worldId, e);
        }
        return cases;
    }

    private List<EvalCase> npcCardCases(String worldId) {
        List<EvalCase> cases = new ArrayList<>();
        try {
            for (CharacterCard c : cardRepo.findByWorldIdAndType(worldId, CARD_TYPE_NPC)) {
                List<String> aliases = aliasList(c.getName(), null);
                Map<String, Object> structured = new LinkedHashMap<>();
                structured.put("motivation", c.getMotivation());
                structured.put("speechStyle", c.getSpeechStyle());
                structured.put("knowledge", c.getKnowledge());
                EvalCase.SettingPack pack = new EvalCase.SettingPack(
                        toJson(structured), c.getIdentity(), c.getPersonality(),
                        c.getBackground(), null, aliases);
                cases.add(new EvalCase(EvalRun.LAYER_STATIC, EvalCase.SOURCE_NPC_CARD,
                        c.getId(), c.getName(),
                        unitJson(EvalCase.SOURCE_NPC_CARD, c.getId(), worldId, null),
                        toJson(pack), aliases, null, null, null, null, null, null));
            }
        } catch (Exception e) {
            log.warn("NPC 卡源装配失败（跳过该源）: worldId={}", worldId, e);
        }
        return cases;
    }

    private List<EvalCase> starLoreCases(String worldId) {
        List<EvalCase> cases = new ArrayList<>();
        try {
            for (StarIncarnation s : starRepo.findByWorldId(worldId)) {
                List<String> aliases = aliasList(s.getWarlordName(), s.getAliases());
                Map<String, Object> structured = new LinkedHashMap<>();
                structured.put("starName", s.getStarName());
                EvalCase.SettingPack pack = new EvalCase.SettingPack(
                        toJson(structured), null, null, null, s.getLore(), aliases);
                cases.add(new EvalCase(EvalRun.LAYER_STATIC, EvalCase.SOURCE_STAR_LORE,
                        s.getId(), s.getWarlordName(),
                        unitJson(EvalCase.SOURCE_STAR_LORE, s.getId(), worldId, null),
                        toJson(pack), aliases, null, null, null, null, null, null));
            }
        } catch (Exception e) {
            log.warn("冥星名录源装配失败（跳过该源）: worldId={}", worldId, e);
        }
        return cases;
    }

    /**
     * 指称词表：displayName 头位 + aliases 字符串拆分（中英文逗号/分号均容），
     * 去空白去重保持序。
     */
    static List<String> aliasList(String displayName, String aliases) {
        List<String> list = new ArrayList<>();
        if (displayName != null && !displayName.isBlank()) {
            list.add(displayName.trim());
        }
        if (aliases != null && !aliases.isBlank()) {
            for (String part : aliases.split("[，,;；]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
        }
        return list;
    }

    /** 评估单元 JSON（动态层另带 sessionId/窗，由动态装配器覆写） */
    static String unitJson(String source, String subject, String worldId, String extra) {
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("source", source);
        unit.put("subject", subject);
        unit.put("worldId", worldId);
        if (extra != null && !extra.isBlank()) {
            unit.put("extra", extra);
        }
        return toJson(unit);
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // LinkedHashMap/POJO 序列化不该失败；防御降级为 toString（判分层容错）
            return String.valueOf(value);
        }
    }
}
