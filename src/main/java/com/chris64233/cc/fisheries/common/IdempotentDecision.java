package com.chris64233.cc.fisheries.common;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 决策幂等键：复核事件号 / 更正号在处理成功后登记一次。
 *
 * <p>唯一约束兜底并发重复提交：同一键只能有一笔决策写入台账，
 * 重复处理直接返回已登记的结果引用，不再核销或归还。
 */
@Entity
@Table(name = "idempotent_decision",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotent_key", columnNames = "idempotencyKey"))
public class IdempotentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务幂等键，例如 review:{eventId} 或 correction:{correctionId}。 */
    @Column(nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    /** 决策处理后关联主记录的当前状态，用于重复请求返回一致结果。 */
    @Column(nullable = false, updatable = false, length = 32)
    private String resultingState;

    /** 决策作用对象的业务号（卸港事件号或原申报事件号），重放时校验目标一致。 */
    @Column(nullable = false, updatable = false, length = 64)
    private String targetRef;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    protected IdempotentDecision() {
    }

    public IdempotentDecision(String idempotencyKey, String resultingState, String targetRef) {
        this.idempotencyKey = idempotencyKey;
        this.resultingState = resultingState;
        this.targetRef = targetRef;
        this.recordedAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getResultingState() {
        return resultingState;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
