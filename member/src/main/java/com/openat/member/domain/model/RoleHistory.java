package com.openat.member.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "role_history")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity roleEntity;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime deletedAt;

    /** 역할 부여 이력 생성. */
    public static RoleHistory of(Member member, RoleEntity roleEntity) {
        RoleHistory history = new RoleHistory();
        history.member = member;
        history.roleEntity = roleEntity;
        return history;
    }

    /** 아직 회수되지 않은(활성) 상태인지. */
    public boolean isActive() {
        return deletedAt == null;
    }

    /** 역할 회수: deleted_at 기록. */
    public void revoke() {
        this.deletedAt = LocalDateTime.now();
    }

    /** 이 이력이 가리키는 역할 enum. */
    public Role getRole() {
        return roleEntity.getRole();
    }
}
