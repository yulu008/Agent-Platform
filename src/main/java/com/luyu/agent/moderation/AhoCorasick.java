package com.luyu.agent.moderation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aho-Corasick 多模式串匹配器（design D2）。
 * <p>
 * 一次 O(n) 扫描（n = 归一化后输入长度）即可命中词库全部词条，与词条数量无关，
 * 适合每请求同步调用。词条在构建期归一化并插入 Trie，失败指针 BFS 建立，
 * 每个状态的命中类别集合在构建期沿失败链预合并，匹配时无需回溯失败链。
 * <p>
 * 线程安全：构建后只读，可被多线程并发匹配。
 */
final class AhoCorasick {

    /** goto 转移：state -> (char -> nextState) */
    private final List<Map<Character, Integer>> next = new ArrayList<>();
    /** 失败指针 */
    private final List<Integer> fail = new ArrayList<>();
    /** 每个状态命中的类别集合（已沿失败链预合并） */
    private final List<Set<ModerationCategory>> output = new ArrayList<>();

    AhoCorasick(Map<ModerationCategory, ? extends Collection<String>> wordsByCategory) {
        newRoot();
        if (wordsByCategory != null) {
            wordsByCategory.forEach((category, words) -> {
                if (category == null || words == null) {
                    return;
                }
                for (String word : words) {
                    addWord(ModerationText.normalize(word), category);
                }
            });
        }
        buildFailLinks();
    }

    private void newRoot() {
        next.add(new HashMap<>());
        fail.add(0);
        output.add(EnumSet.noneOf(ModerationCategory.class));
    }

    private void addWord(String normalized, ModerationCategory category) {
        if (normalized == null || normalized.isEmpty()) {
            return;
        }
        int state = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            Map<Character, Integer> edges = next.get(state);
            Integer nxt = edges.get(c);
            if (nxt == null) {
                nxt = next.size();
                edges.put(c, nxt);
                next.add(new HashMap<>());
                fail.add(0);
                output.add(EnumSet.noneOf(ModerationCategory.class));
            }
            state = nxt;
        }
        output.get(state).add(category);
    }

    /** BFS 建立失败指针，并把失败链上的命中类别预合并进当前状态。 */
    private void buildFailLinks() {
        Deque<Integer> queue = new ArrayDeque<>();
        // 根的直接子节点失败指针指向根
        for (Integer child : next.get(0).values()) {
            fail.set(child, 0);
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            int state = queue.poll();
            for (Map.Entry<Character, Integer> e : next.get(state).entrySet()) {
                char c = e.getKey();
                int child = e.getValue();
                int f = fail.get(state);
                while (f != 0 && !next.get(f).containsKey(c)) {
                    f = fail.get(f);
                }
                int failState = next.get(f).containsKey(c) && next.get(f).get(c) != child
                        ? next.get(f).get(c)
                        : 0;
                fail.set(child, failState);
                // 预合并失败链命中类别
                Set<ModerationCategory> inherited = output.get(failState);
                if (!inherited.isEmpty()) {
                    output.get(child).addAll(inherited);
                }
                queue.add(child);
            }
        }
    }

    /**
     * 扫描文本，返回命中的全部类别（空集表示未命中）。文本会先归一化。
     */
    Set<ModerationCategory> matchCategories(String text) {
        String normalized = ModerationText.normalize(text);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<ModerationCategory> hits = EnumSet.noneOf(ModerationCategory.class);
        int state = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            while (state != 0 && !next.get(state).containsKey(c)) {
                state = fail.get(state);
            }
            Integer nxt = next.get(state).get(c);
            state = nxt == null ? 0 : nxt;
            Set<ModerationCategory> out = output.get(state);
            if (!out.isEmpty()) {
                hits.addAll(out);
            }
        }
        return hits.isEmpty() ? Set.of() : hits;
    }

    /** 词库是否为空（无任何词条）。 */
    boolean isEmpty() {
        return next.size() <= 1;
    }
}
