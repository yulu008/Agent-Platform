package com.luyu.agent.rpg.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link TechniqueStateReader} 旧档兼容测试（rpg-technique-tree-data tasks 5.3）。
 * <p>
 * 三条兼容路径：旧档无新字段（learned 视为 true / proficiency 视为 400 / tier 与前置从树表现算）、
 * 旧档完全无 techniques（非星命世界存档返回空集）、新档全字段（原样读出不改写）。
 */
class TechniqueStateReaderTest {

    @Test
    void 旧档无新字段视为已学小成且树结构现算() {
        String json = "{\"constellation\":\"天马座\",\"faction\":\"saint\",\"techniques\":["
                + "{\"name\":\"天马流星拳\",\"description\":\"d1\",\"source\":\"经典系列\"},"
                + "{\"name\":\"天马彗星拳\",\"description\":\"d2\",\"source\":\"经典系列\"}]}";

        List<TechniqueState> states = TechniqueStateReader.read(json);

        assertThat(states).hasSize(2);
        TechniqueState root = states.get(0);
        assertThat(root.learned()).isTrue();
        assertThat(root.proficiency()).isEqualTo(400);
        assertThat(root.tier()).isEqualTo(1);
        assertThat(root.prerequisites()).isEmpty();
        TechniqueState comet = states.get(1);
        assertThat(comet.learned()).isTrue();
        assertThat(comet.proficiency()).isEqualTo(400);
        // tier / 前置从 TechniqueTreeMap 现算
        assertThat(comet.tier()).isEqualTo(2);
        assertThat(comet.prerequisites()).containsExactly("天马流星拳");
    }

    @Test
    void 旧档完全无techniques键返回空集() {
        assertThat(TechniqueStateReader.read("{\"money\":100}")).isEmpty();
        assertThat(TechniqueStateReader.read("{}")).isEmpty();
        assertThat(TechniqueStateReader.read("")).isEmpty();
        assertThat(TechniqueStateReader.read((String) null)).isEmpty();
        assertThatCode(() -> assertThat(TechniqueStateReader.read("not-a-json")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    void 新档全字段原样读出() {
        String json = "{\"constellation\":\"天马座\",\"faction\":\"saint\",\"techniques\":["
                + "{\"name\":\"天马流星拳\",\"description\":\"d1\",\"source\":\"经典系列\","
                + "\"tier\":1,\"prerequisites\":[],\"learned\":true,\"proficiency\":200},"
                + "{\"name\":\"天马彗星拳\",\"description\":\"d2\",\"source\":\"经典系列\","
                + "\"tier\":2,\"prerequisites\":[\"天马流星拳\"],\"learned\":false,\"proficiency\":0}]}";

        List<TechniqueState> states = TechniqueStateReader.read(json);

        assertThat(states).hasSize(2);
        assertThat(states.get(0).learned()).isTrue();
        assertThat(states.get(0).proficiency()).isEqualTo(200);
        assertThat(states.get(1).learned()).isFalse();
        assertThat(states.get(1).proficiency()).isZero();
        assertThat(states.get(1).prerequisites()).containsExactly("天马流星拳");
    }

    @Test
    void 越界熟练度读取时夹取() {
        String json = "{\"constellation\":\"天马座\",\"faction\":\"saint\",\"techniques\":["
                + "{\"name\":\"天马流星拳\",\"learned\":true,\"proficiency\":9999}]}";
        assertThat(TechniqueStateReader.read(json).get(0).proficiency())
                .isEqualTo(TechniqueProficiency.MAX);
    }
}
