package com.luyu.agent.rpg.eval.engine;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.config.EvalProperties;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 确定性检查器单测（content-eval tasks 4.5）：四检查器检出/未检出/边界
 * + 聚合限流与单器防御 + 「L1 全程无 LLM 调用无计量」的构造依赖证明。
 */
class L1CheckersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GS = "gs-1";

    // ==================== HardFactChecker ====================

    @Test
    void 硬事实_星位冲突检出_档案星位一致不检出() throws Exception {
        EvalCase c = dynamicCase("kagetro", List.of("景虎", "越后之龙"),
                pack("{\"constellation\":\"处女座\",\"eraNote\":\"弘治二年二十六岁\"}", null),
                List.of(row(1, "景虎披挂双子座黄金圣衣。"), row(2, "景虎的处女座圣衣与主人共鸣。")));

        List<L1Warning> warnings = new HardFactChecker().check(c);

        // 轮1：句含指称 + 非档案星名（双子座）→ 预警；轮2：星位一致 → 不检出
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).checker()).isEqualTo(L1Warning.CHECKER_HARD_FACTS);
        assertThat(warnings.get(0).turn()).isEqualTo(1);
        assertThat(warnings.get(0).message()).contains("双子座").contains("处女座");
    }

    @Test
    void 硬事实_无人物指称的星位叙述不检出() throws Exception {
        EvalCase c = dynamicCase("kagetro", List.of("景虎"),
                pack("{\"constellation\":\"处女座\"}", null),
                List.of(row(1, "远方的双子座圣衣静静沉睡，无人知晓其主。")));

        assertThat(new HardFactChecker().check(c)).isEmpty();
    }

    @Test
    void 硬事实_阵营背离动词检出() throws Exception {
        EvalCase c = dynamicCase("kagetro", List.of("景虎"),
                pack("{\"constellation\":\"处女座\",\"faction\":\"saint\"}", null),
                List.of(row(1, "景虎改投武田家麾下。")));

        List<L1Warning> warnings = new HardFactChecker().check(c);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message()).contains("阵营变动").contains("saint");
    }

    @Test
    void 硬事实_家名归属冲突检出() throws Exception {
        EvalCase c = dynamicCase("kagetro", List.of("景虎"),
                pack("{\"constellation\":\"处女座\"}", "越后国主长尾家当主"),
                List.of(row(1, "景虎效忠武田家，成了武田家的重臣。")));

        List<L1Warning> warnings = new HardFactChecker().check(c);

        assertThat(warnings).extracting(L1Warning::message)
                .anyMatch(m -> m.contains("武田家") && m.contains("长尾家"));
    }

    @Test
    void 硬事实_年龄断崖中文数字比对() throws Exception {
        // eraNote 中文数字（二十六）；叙述阿拉伯数字 36 → |差| = 10 ≥ 容差
        EvalCase cliff = dynamicCase("kagetro", List.of("景虎"),
                pack("{\"constellation\":\"处女座\",\"eraNote\":\"弘治二年二十六岁\"}", null),
                List.of(row(1, "三十六岁的景虎纵马而来。")));
        EvalCase same = dynamicCase("kagetro", List.of("景虎"),
                pack("{\"constellation\":\"处女座\",\"eraNote\":\"弘治二年二十六岁\"}", null),
                List.of(row(1, "年方二十六的景虎意气风发。")));

        assertThat(new HardFactChecker().check(cliff))
                .extracting(L1Warning::message)
                .anyMatch(m -> m.contains("年龄"));
        assertThat(new HardFactChecker().check(same)).isEmpty();
    }

    @Test
    void 硬事实_静态层设定包段内矛盾检出() throws Exception {
        // 无叙述窗 → 设定包文本为被检对象（档案自洽）；turn = TURN_NONE
        // personality 段含背离动词 + 指称
        EvalCase.SettingPack p = new EvalCase.SettingPack(
                "{\"constellation\":\"处女座\"}", null, "景虎早已背叛长尾家，改投敌门。", null, null, null);
        EvalCase c = new EvalCase("static", EvalCase.SOURCE_DESTINED, "kagetro", "长尾景虎", "{}",
                JSON.writeValueAsString(p), List.of("景虎"), null, null, null, null, null, null);

        List<L1Warning> warnings = new HardFactChecker().check(c);

        assertThat(warnings).isNotEmpty();
        assertThat(warnings.get(0).turn()).isEqualTo(L1Warning.TURN_NONE);
    }

    @Test
    void 硬事实_中文数字解析() {
        assertThat(HardFactChecker.cnNumber("二十六")).isEqualTo(26);
        assertThat(HardFactChecker.cnNumber("三十五")).isEqualTo(35);
        assertThat(HardFactChecker.cnNumber("十五")).isEqualTo(15);
        assertThat(HardFactChecker.cnNumber("二十")).isEqualTo(20);
        assertThat(HardFactChecker.cnNumber("九")).isEqualTo(9);
    }

    // ==================== StateTrajectoryChecker ====================

    @Test
    void 断崖_无语义支撑检出_有支撑不检出() {
        EvalProperties props = new EvalProperties(); // 默认阈值 40
        StateTrajectoryChecker checker = new StateTrajectoryChecker(props);
        // 轮2 叙述无 cosmo 语义词 → 检出；轮3 叙述有「爆发」→ 不检出
        EvalCase c = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(row(2, "他沉默地转过身去。"), row(3, "小宇宙骤然爆发，气浪掀翻帷幕。")),
                List.of(new EvalCase.TurnState(2, Map.of("player:cosmo", 50.0)),
                        new EvalCase.TurnState(3, Map.of("player:cosmo", 45.0))));

        List<L1Warning> warnings = checker.check(c);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).turn()).isEqualTo(2);
        assertThat(warnings.get(0).checker()).isEqualTo(L1Warning.CHECKER_STATE_TRAJECTORY);
        assertThat(warnings.get(0).message()).contains("player:cosmo").contains("50");
    }

    @Test
    void 断崖_阈值下不检出_未知键保守预警() {
        StateTrajectoryChecker checker = new StateTrajectoryChecker(new EvalProperties());
        // 阈值下（39 < 40）→ 无；未知键 reputation +60 无语义词表 → 保守预警
        EvalCase c = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(row(1, "风平浪静。"), row(2, "他获得了名望。")),
                List.of(new EvalCase.TurnState(1, Map.of("player:cosmo", 39.0)),
                        new EvalCase.TurnState(2, Map.of("player:reputation", 60.0))));

        List<L1Warning> warnings = checker.check(c);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).turn()).isEqualTo(2);
        assertThat(warnings.get(0).message()).contains("reputation").contains("无语义词表");
    }

    @Test
    void 断崖_轨迹缺失自然零检出() {
        EvalCase c = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(row(1, "叙述。")), null);

        assertThat(new StateTrajectoryChecker(new EvalProperties()).check(c)).isEmpty();
    }

    // ==================== KanjiReferenceChecker ====================

    @Test
    void 指称_罗马音检出_user行不检() {
        EvalCase c = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(new EvalCase.TurnText(1, "user", "I am Kagetro of Echigo.", null),
                        row(1, "Kagetro 现身于军阵之前。")));

        List<L1Warning> warnings = new KanjiReferenceChecker().check(c);

        // user 行的英文不算（玩家输入不是被评产物）；assistant 行检出 Kagetro
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).turn()).isEqualTo(1);
        assertThat(warnings.get(0).message()).contains("Kagetro");
        assertThat(warnings.get(0).span()).contains("Kagetro");
    }

    @Test
    void 指称_纯中文零检出_静态层设定包检出() throws Exception {
        EvalCase clean = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(row(1, "晴信与景虎对峙于川中岛。")));
        assertThat(new KanjiReferenceChecker().check(clean)).isEmpty();

        // 静态层：identity 段自身夹罗马音（档案也不该有）
        EvalCase staticWithLatin = new EvalCase("static", EvalCase.SOURCE_DESTINED,
                "kagetro", "长尾景虎", "{}",
                JSON.writeValueAsString(new EvalCase.SettingPack(
                        "{}", "Uesugi Kenshin 的化身", null, null, null, null)),
                List.of("景虎"), null, null, null, null, null, null);
        List<L1Warning> warnings = new KanjiReferenceChecker().check(staticWithLatin);
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.get(0).turn()).isEqualTo(L1Warning.TURN_NONE);
    }

    // ==================== HookAssertionChecker ====================

    @Test
    void 钩子断言_触发词加指称检出_真实资源清单加载() {
        EvalCase c = dynamicCase("kagetro", List.of("景虎", "越后之龙"), pack("{}", null),
                List.of(row(1, "景虎当众打开圣衣箱，金光四溢。")));

        List<L1Warning> warnings = new HookAssertionChecker().check(c);

        // 真实 classpath 资源 rpg/eval-character-assertions.md 的 kagetro 断言
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).checker()).isEqualTo(L1Warning.CHECKER_HOOK_ASSERTIONS);
        assertThat(warnings.get(0).message()).contains("圣衣箱").contains("开启");
    }

    @Test
    void 钩子断言_触发词无指称不检出_人物不匹配不检出() {
        // 触发词命中但句中无该人物指称（他人开箱）
        EvalCase noMention = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(row(1, "远处的无名士兵打开圣衣箱。")));
        assertThat(new HookAssertionChecker().check(noMention)).isEmpty();

        // subject 不在断言清单（工坊自建人物）
        EvalCase unknownSubject = dynamicCase("cc-999", List.of("阿茜"), pack("{}", null),
                List.of(row(1, "阿茜打开圣衣箱。")));
        assertThat(new HookAssertionChecker().check(unknownSubject)).isEmpty();
    }

    @Test
    void 钩子断言_静态层无叙述自然零检出() {
        EvalCase c = new EvalCase("static", EvalCase.SOURCE_DESTINED, "kagetro",
                "长尾景虎", "{}", pack("{}", null), List.of("景虎"),
                null, null, null, null, null, null);
        assertThat(new HookAssertionChecker().check(c)).isEmpty();
    }

    // ==================== L1CheckRunner ====================

    @Test
    void 聚合_四检查器输出合并_限流截断() {
        EvalProperties props = new EvalProperties();
        props.setMaxL1WarningsPerCase(2);
        // 构造 3 条预警输入：3 个罗马音词 → KanjiReference 3 条 → 超 2 条截断
        EvalCase c = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(row(1, "Kagetro 与 Harunobu 及 Nobukatsu 会面。")));

        List<L1Warning> warnings = new L1CheckRunner(new HardFactChecker(),
                new StateTrajectoryChecker(props), new KanjiReferenceChecker(),
                new HookAssertionChecker(), props).run(c);

        // 截断 + 末条注明（l1-runner）
        assertThat(warnings).hasSize(3);
        assertThat(warnings.get(2).checker()).isEqualTo("l1-runner");
        assertThat(warnings.get(2).message()).contains("截断");
    }

    @Test
    void 聚合_单检查器异常防御其余照常() {
        KanjiReferenceChecker throwing = mock(KanjiReferenceChecker.class);
        when(throwing.check(org.mockito.ArgumentMatchers.any(EvalCase.class)))
                .thenThrow(new RuntimeException("检查器内部错误"));
        EvalCase c = dynamicCase("kagetro", List.of("景虎"), pack("{}", null),
                List.of(row(1, "景虎改投武田家。")));

        List<L1Warning> warnings = new L1CheckRunner(new HardFactChecker(),
                new StateTrajectoryChecker(new EvalProperties()), throwing,
                new HookAssertionChecker(), new EvalProperties()).run(c);

        // kanji 检查器挂掉，硬事实阵营背离照常产出
        assertThat(warnings).extracting(L1Warning::checker)
                .contains(L1Warning.CHECKER_HARD_FACTS);
    }

    @Test
    void 架构证明_L1全链路无LLM与计量依赖() {
        // spec「L1 无 LLM 调用」：四检查器 + 聚合入口的构造仅依赖纯数据/配置
        // （无 ChatClient、无 JdbcTemplate、无 metering 组件），check 纯函数执行
        EvalProperties props = new EvalProperties();
        L1CheckRunner runner = new L1CheckRunner(new HardFactChecker(),
                new StateTrajectoryChecker(props), new KanjiReferenceChecker(),
                new HookAssertionChecker(), props);
        EvalCase c = dynamicCase("kagetro", List.of("景虎"),
                pack("{\"constellation\":\"处女座\",\"eraNote\":\"弘治二年二十六岁\"}", null),
                List.of(row(1, "三十六岁的景虎披挂双子座圣衣，Kagetro之名远扬。")));

        List<L1Warning> warnings = runner.run(c);

        assertThat(warnings).isNotEmpty();
    }

    // ==================== 构造辅助 ====================

    /** 设定包 JSON（structuredJson + identity） */
    private static String pack(String structuredJson, String identity) {
        try {
            return JSON.writeValueAsString(new EvalCase.SettingPack(
                    structuredJson, identity, null, null, null, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 动态层 case（early 即叙述窗；late 同 early 由去重合并） */
    private static EvalCase dynamicCase(String subject, List<String> aliases,
                                        String settingPackJson, List<EvalCase.TurnText> early,
                                        List<EvalCase.TurnState> trajectory) {
        return new EvalCase("dynamic", EvalCase.SOURCE_DESTINED, subject, "长尾景虎",
                "{}", settingPackJson, aliases, early, early, "{}", "{}",
                "hash123", trajectory);
    }

    private static EvalCase dynamicCase(String subject, List<String> aliases,
                                        String settingPackJson, List<EvalCase.TurnText> early) {
        return dynamicCase(subject, aliases, settingPackJson, early, null);
    }

    private static EvalCase.TurnText row(int turn, String text) {
        return new EvalCase.TurnText(turn, EvalCase.TurnText.ROLE_ASSISTANT, text, "a" + turn);
    }
}
