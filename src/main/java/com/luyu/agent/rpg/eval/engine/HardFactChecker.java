package com.luyu.agent.rpg.eval.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.springframework.stereotype.Component;

/**
 * 硬事实核对器（content-eval tasks 4.1，design D5）。
 * <p>
 * 档案结构化字段 → 规则化矛盾证据检索（确定性，零 LLM）。四条规则：
 * <ol>
 *   <li><b>星位冲突</b>：同句含人物指称 + 非档案星位的世界星名（枚举词表 =
 *       preset 的 16 星座 + 14 已知冥星，类别词「魔星」等不在词表零误报）；</li>
 *   <li><b>阵营背离</b>：同句含指称 + 背离动词（改投/背叛/叛出…），提示核对
 *       faction 绑定（saint/specter 等抽象域无家名可比）；</li>
 *   <li><b>家名归属冲突</b>：档案 identity 的家名（首个「X家」）为基准，同句
 *       含指称 + 其他家名 + 归属词（效忠/家臣/麾下…）；</li>
 *   <li><b>年龄断崖</b>：eraNote 的年龄（阿拉伯/中文数字均容）为基准，同句
 *       含指称 + 数字岁且 |差| ≥ 10。</li>
 * </ol>
 * 被检文本源：动态层 = GM 叙述行（turn 定位）；静态层 = 设定包文本段
 * （档案段内自洽；跨段矛盾交 judge 裁决）。「同句」约束 = 分句内含指称，
 * 避免他人叙事误挂到被评人物。L1 只筛不判。
 */
@Component
public class HardFactChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 世界星名全集（preset-adventure：16 星座 + 14 已知冥星；检索用枚举，非任意 X星 模式） */
    private static final Set<String> STAR_TOKENS = Set.of(
            "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座", "天秤座", "天蝎座",
            "射手座", "摩羯座", "水瓶座", "双鱼座",
            "仙女座", "天马座", "凤凰座", "天鹅座",
            "天魁星", "地魁星", "天猛星", "天雄星", "天贵星", "天英星", "天牢星", "天捷星",
            "天哭星", "天兽星", "天魔星", "地妖星", "天寿星", "天究星");

    /** 背离动词（叙述含此 + 指称 → 阵营变动事件信号） */
    private static final List<String> DEFECT_VERBS =
            List.of("改投", "投靠", "背叛", "叛出", "背弃", "逐出家门", "驱逐出");

    /** 归属词（家名归属冲突的语境约束） */
    private static final List<String> ALLEGIANCE_WORDS =
            List.of("效忠", "侍奉", "家臣", "麾下", "重臣", "之臣", "归属", "旗下", "先锋");

    private static final Pattern CLAN = Pattern.compile("[\\u4e00-\\u9fa5]{1,4}家");

    /** 阿拉伯数字年龄（叙述与 eraNote 双侧） */
    private static final Pattern AGE_NUM = Pattern.compile("([0-9]{1,3})\\s*岁");

    /** 中文数字年龄（preset eraNote 用「二十六岁」形态） */
    private static final Pattern AGE_CN = Pattern.compile("([一二三四五六七八九十]{1,4})\\s*岁");

    /** 年龄断崖容差 */
    private static final int AGE_TOLERANCE = 10;

    /** 单 case 预警上限（防长叙述刷屏） */
    private static final int MAX_HITS_PER_CASE = 20;

    public List<L1Warning> check(EvalCase c) {
        Profile profile = profile(c);
        Set<String> aliases = new LinkedHashSet<>();
        if (c.aliases() != null) {
            c.aliases().stream().filter(a -> a != null && !a.isBlank()).forEach(aliases::add);
        }
        List<L1Warning> warnings = new ArrayList<>();
        List<EvalCase.TurnText> narration = L1Texts.narrationRows(c);
        if (!narration.isEmpty()) {
            for (EvalCase.TurnText row : narration) {
                scan(row.turn(), row.text(), profile, aliases, warnings);
            }
        } else {
            // 静态层：设定包段内自洽（跨段矛盾由 judge 按 rubric 裁决）
            for (String text : L1Texts.settingTexts(c)) {
                scan(L1Warning.TURN_NONE, text, profile, aliases, warnings);
            }
        }
        return warnings;
    }

    private void scan(int turn, String text, Profile profile, Set<String> aliases,
                      List<L1Warning> warnings) {
        for (String sentence : L1Texts.sentences(text)) {
            if (!mentionsAlias(sentence, aliases)) {
                continue;
            }
            if (warnings.size() >= MAX_HITS_PER_CASE) {
                return;
            }
            checkStarConflict(turn, sentence, profile.star(), warnings);
            checkDefectVerb(turn, sentence, profile.faction(), warnings);
            checkClanConflict(turn, sentence, profile.homeClan(), warnings);
            checkAgeCliff(turn, sentence, profile.profileAge(), warnings);
        }
    }

    private void checkStarConflict(int turn, String sentence, String profileStar,
                                   List<L1Warning> warnings) {
        if (profileStar == null || profileStar.isBlank()) {
            return;
        }
        for (String star : STAR_TOKENS) {
            if (star.equals(profileStar)) {
                continue;
            }
            if (sentence.contains(star)) {
                warnings.add(new L1Warning(L1Warning.CHECKER_HARD_FACTS, turn,
                        "叙述将人物关联到星位「" + star + "」，档案绑定为「" + profileStar + "」",
                        clip(sentence)));
            }
        }
    }

    private void checkDefectVerb(int turn, String sentence, String faction,
                                 List<L1Warning> warnings) {
        for (String verb : DEFECT_VERBS) {
            if (sentence.contains(verb)) {
                String binding = faction == null || faction.isBlank() ? "未标注" : faction;
                warnings.add(new L1Warning(L1Warning.CHECKER_HARD_FACTS, turn,
                        "叙述含阵营变动事件（" + verb + "），请核对与档案阵营绑定（faction="
                                + binding + "）是否冲突", clip(sentence)));
                return; // 一句一预警即可（多动词同句语义重复）
            }
        }
    }

    private void checkClanConflict(int turn, String sentence, String homeClan,
                                   List<L1Warning> warnings) {
        if (homeClan == null) {
            return;
        }
        String homeCore = clanCore(homeClan);
        boolean hasAllegiance = ALLEGIANCE_WORDS.stream().anyMatch(sentence::contains);
        if (!hasAllegiance) {
            return;
        }
        Matcher m = CLAN.matcher(sentence);
        while (m.find()) {
            String other = m.group();
            if (!clanCore(other).equals(homeCore)) {
                warnings.add(new L1Warning(L1Warning.CHECKER_HARD_FACTS, turn,
                        "叙述将人物归属到「" + other + "」，档案 identity 家名为「" + homeClan + "」",
                        clip(sentence)));
                return;
            }
        }
    }

    private void checkAgeCliff(int turn, String sentence, Integer profileAge,
                               List<L1Warning> warnings) {
        if (profileAge == null) {
            return;
        }
        Matcher num = AGE_NUM.matcher(sentence);
        if (num.find()) {
            addAgeWarningIfCliff(turn, sentence, Integer.parseInt(num.group(1)), profileAge, warnings);
            return;
        }
        Matcher cn = AGE_CN.matcher(sentence);
        if (cn.find()) {
            addAgeWarningIfCliff(turn, sentence, cnNumber(cn.group(1)), profileAge, warnings);
        }
    }

    private void addAgeWarningIfCliff(int turn, String sentence, int narratedAge,
                                      int profileAge, List<L1Warning> warnings) {
        if (Math.abs(narratedAge - profileAge) >= AGE_TOLERANCE) {
            warnings.add(new L1Warning(L1Warning.CHECKER_HARD_FACTS, turn,
                    "叙述年龄「" + narratedAge + "岁」与档案 eraNote 年龄「" + profileAge
                            + "岁」偏差超容差", clip(sentence)));
        }
    }

    /** 档案侧硬事实基准（settingPackJson 解析，防御降级空 Profile） */
    private Profile profile(EvalCase c) {
        String star = null;
        String faction = null;
        String eraNote = null;
        String identity = null;
        try {
            JsonNode pack = MAPPER.readTree(c.settingPackJson());
            JsonNode structured = pack.get("structuredJson");
            if (structured != null && structured.isTextual()) {
                JsonNode fields = MAPPER.readTree(structured.asText());
                star = textOrNUll(fields, "constellation", "starName");
                faction = textOrNUll(fields, "faction");
                eraNote = textOrNUll(fields, "eraNote");
            }
            JsonNode id = pack.get("identity");
            if (id != null && id.isTextual()) {
                identity = id.asText();
            }
        } catch (Exception ignored) {
            // 设定包非法 JSON：硬事实基准为空（全部规则自然跳过，防御）
        }
        return new Profile(star, faction, extractAge(eraNote), extractHomeClan(identity));
    }

    private static String textOrNUll(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    /** eraNote 年龄（阿拉伯/中文数字并容；「弘治二年二十六岁」形态） */
    private static Integer extractAge(String eraNote) {
        if (eraNote == null) {
            return null;
        }
        Matcher num = AGE_NUM.matcher(eraNote);
        if (num.find()) {
            return Integer.parseInt(num.group(1));
        }
        Matcher cn = AGE_CN.matcher(eraNote);
        if (cn.find()) {
            return cnNumber(cn.group(1));
        }
        return null;
    }

    /** identity 段首个「X家」（家名归属基准；无家名叙事（浪人等）为 null） */
    private static String extractHomeClan(String identity) {
        if (identity == null) {
            return null;
        }
        Matcher m = CLAN.matcher(identity);
        return m.find() ? m.group() : null;
    }

    private static boolean mentionsAlias(String sentence, Set<String> aliases) {
        for (String alias : aliases) {
            if (sentence.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    /** 家名核心词（去末尾「家」，规避「织田家」vs「织田」形态差异） */
    private static String clanCore(String clan) {
        return clan.endsWith("家") ? clan.substring(0, clan.length() - 1) : clan;
    }

    /** 中文数字 → 整数（一~九十九：二十六=26、三十五=35、十五=15、二十=20） */
    static int cnNumber(String s) {
        int total = 0;
        int current = 0;
        for (char ch : s.toCharArray()) {
            int v = switch (ch) {
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                case '十' -> 10;
                default -> 0;
            };
            if (v == 10) {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else {
                current = v;
            }
        }
        return total + current;
    }

    /** span 摘录（句截 40 字，judge 核对定位用） */
    private static String clip(String sentence) {
        return sentence.length() <= 40 ? sentence : sentence.substring(0, 40) + "…";
    }

    /** 档案硬事实基准（四规则的比对锚点） */
    private record Profile(String star, String faction, Integer profileAge, String homeClan) {
    }
}
