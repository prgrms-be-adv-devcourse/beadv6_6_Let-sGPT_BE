package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * createdAt/updatedAt/deletedAt(soft delete)을 공통으로 갖는 엔티티의 상위 클래스.
 * createdAt/updatedAt은 {@link AuditingEntityListener}가 자동으로 채워주므로,
 * 이 클래스를 상속하는 엔티티가 등록된 모듈에는 JPA Auditing이 활성화되어 있어야 한다
 * (member 모듈은 {@code infrastructure.config.JpaAuditingConfig} 참고).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted() {
        this.deletedAt = LocalDateTime.now();
    }
}
