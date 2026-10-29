package com.luyu.agent.rpg.eval.service;

import java.util.List;

import com.luyu.agent.rpg.eval.model.EvalRecord;
import com.luyu.agent.rpg.eval.repository.EvalRecordRepository;
import org.springframework.stereotype.Service;

/**
 * 人工复核编排服务（content-eval tasks 7.2，design D10）。
 * <p>
 * 机器筛人拍板：复核队列（borderline 自动入队 + 分歧标记行）由 repository
 * 查询语义保证；复核收口按「终裁 vs 机器 verdict」判定——一致 {@code confirmed}
 * （人工确认机器判分）、不一致 {@code override}（终裁写 {@code review_final_verdict}，
 * 机器原判保留于 {@code verdict} 列不覆盖，spec「override 保留机器原判」）。
 * 终裁 = fail 支持一键沉淀回归集（{@code is_regression=true}，后续批跑必含）。
 */
@Service
public class EvalReviewService {

    private final EvalRecordRepository recordRepository;

    public EvalReviewService(EvalRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /** 复核队列（最近 limit 条；borderline 不计失败率——队列是唯一单列出口） */
    public List<EvalRecord> reviewQueue(int limit) {
        return recordRepository.listReviewQueue(Math.max(1, Math.min(limit, 200)));
    }

    /**
     * 复核收口：终裁三档校验 → confirmed/override 判定 → 落终裁与回归标记。
     *
     * @return 更新后的 record；记录不存在（或跨租户）返回 null（controller 404 语义）
     * @throws IllegalArgumentException finalVerdict 非三档；或非 fail 终裁要求沉淀回归集
     */
    public EvalRecord review(String recordId, String finalVerdict, boolean regression) {
        if (!EvalRecord.VERDICT_PASS.equals(finalVerdict)
                && !EvalRecord.VERDICT_BORDERLINE.equals(finalVerdict)
                && !EvalRecord.VERDICT_FAIL.equals(finalVerdict)) {
            throw new IllegalArgumentException("finalVerdict 须为 pass / borderline / fail");
        }
        if (regression && !EvalRecord.VERDICT_FAIL.equals(finalVerdict)) {
            throw new IllegalArgumentException("仅 final verdict=fail 支持沉淀回归集");
        }
        EvalRecord record = recordRepository.findById(recordId);
        if (record == null) {
            return null;
        }
        // 终裁 vs 机器 verdict：一致 = 人工确认；不一致（含机器 verdict 为 null 的
        // 作废行人工补判）= override，机器原判列不动
        String status = finalVerdict.equals(record.getVerdict())
                ? EvalRecord.REVIEW_CONFIRMED
                : EvalRecord.REVIEW_OVERRIDE;
        recordRepository.applyReview(recordId, status, finalVerdict, regression);
        return recordRepository.findById(recordId);
    }
}
