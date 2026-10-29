package com.luyu.agent.rpg.repository;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code custom_blocks} 列的仓储 round-trip 测试（rpg-hud-custom-blocks tasks 5.1）。
 * <p>
 * INSERT 12 列 / UPDATE 全量重写 / SELECT * BeanPropertyRowMapper 映射三路径钉住
 * 「存取 / NULL 语义」：清空即存 NULL（D7：随表单全量重写，NULL = 无自定义块）；
 * 存量库升级（ALTER 段加列后旧行）默认 NULL 走无块状态。
 * 照 {@code WorldPublishEndToEndTest} 模式：@JdbcTest + 真实内存 H2（建表与迁移
 * 段由 {@link RpgSchemaInitializer} 执行，含本 change 的 ALTER ADD COLUMN 幂等段）。
 */
@JdbcTest
@Import({RpgSchemaInitializer.class, RpgWorldSettingRepository.class})
class RpgWorldSettingCustomBlocksRoundTripTest {

    private static final String CUSTOM_BLOCKS_JSON =
            "[{\"title\":\"本幕目标\",\"field\":\"objectives\"},{\"title\":\"传闻\",\"field\":\"rumors\",\"accent\":\"--npc-accent\"}]";

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static WorldSetting world(String name) {
        WorldSetting w = new WorldSetting();
        w.setId(UUID.randomUUID().toString());
        w.setName(name);
        w.setSettingDesc("desc");
        return w;
    }

    @Test
    void save携带customBlocks_读回等值原文() {
        WorldSetting w = world("带块世界");
        w.setCustomBlocks(CUSTOM_BLOCKS_JSON);
        worldRepo.save(w);

        WorldSetting loaded = worldRepo.findById(w.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCustomBlocks()).isEqualTo(CUSTOM_BLOCKS_JSON);
    }

    @Test
    void save不设customBlocks_读回null走无块状态() {
        WorldSetting w = world("无块世界");
        worldRepo.save(w);

        WorldSetting loaded = worldRepo.findById(w.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCustomBlocks()).isNull();
    }

    @Test
    void update全量重写customBlocks_新值落库() {
        WorldSetting w = world("编辑前世界");
        worldRepo.save(w);

        w.setCustomBlocks(CUSTOM_BLOCKS_JSON);
        w.setName("编辑后世界");
        worldRepo.update(w);

        WorldSetting loaded = worldRepo.findById(w.getId());
        assertThat(loaded.getName()).isEqualTo("编辑后世界");
        assertThat(loaded.getCustomBlocks()).isEqualTo(CUSTOM_BLOCKS_JSON);
    }

    @Test
    void update清空customBlocks_存NULL回默认() {
        WorldSetting w = world("清空世界");
        w.setCustomBlocks(CUSTOM_BLOCKS_JSON);
        worldRepo.save(w);

        w.setCustomBlocks(null);
        worldRepo.update(w);

        WorldSetting loaded = worldRepo.findById(w.getId());
        assertThat(loaded.getCustomBlocks()).isNull();
    }
}
