package com.luyu.agent.rpg.model;

import java.util.List;
import java.util.Objects;

/**
 * 招式技能树的一个节点。
 * <p>
 * 只承载结构信息（招式名 + 前置招式名集合），招式的描述/来源/攻击类型仍在
 * {@link StarTechnique} 里 —— 刻意不合并两者：{@code ConstellationTechniqueMap} 有 339 个
 * {@code StarTechnique} 构造点，给它加字段会让表从 790 行膨胀到 1200+ 行且可读性下降。
 * 两表可能漂移，由 {@code TechniqueTreeMapValidationTest} 的覆盖断言在 CI 兜住。
 * <p>
 * {@code prerequisites} 为空即根节点（建档时可无条件起手掌握）。前置名的作用域锁定在
 * <b>同一星座/星辰且同一阵营表</b>内 —— 天秤座与天龙座都含「庐山升龙霸」，二者互不相关。
 *
 * @param name          招式名，MUST 与 {@link ConstellationTechniqueMap} 中该条目的招式名逐字一致
 * @param prerequisites 前置招式名列表，空列表表示根节点
 */
public record TechniqueNode(String name, List<String> prerequisites) {

    public TechniqueNode {
        Objects.requireNonNull(name, "招式名不能为 null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("招式名不能为空白");
        }
        // List.copyOf 同时完成防御性拷贝与不可变化，并拒绝 null 元素
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
    }

    /** 是否根节点（无任何前置，建档时可直接掌握）。 */
    public boolean isRoot() {
        return prerequisites.isEmpty();
    }
}
