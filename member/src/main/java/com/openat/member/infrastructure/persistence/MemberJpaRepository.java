package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberJpaRepository extends JpaRepository<Member, UUID> {

    // 탈퇴(논리적 삭제)한 회원은 로그인/내정보/수정/탈퇴 등에서 보이면 안 되므로 활성 회원만 조회.
    Optional<Member> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Member> findByEmailAndDeletedAtIsNull(String email);

    // 탈퇴 여부와 무관하게 전체에서 조회 — 로그인 시 "탈퇴한 계정으로 로그인 시도"를
    // 구분해서 알려주려면 일단 deletedAt 필터 없이 찾아본 뒤 isDeleted()로 판별해야 한다.
    Optional<Member> findByEmail(String email);

    // 이메일 unique 제약은 DB 컬럼 자체에 걸려있어 탈퇴 여부와 무관하게 항상 유일해야 하므로
    // (탈퇴한 회원의 이메일이라도 새 가입에서 재사용 불가) deletedAt 필터 없이 전체를 본다.
    boolean existsByEmail(String email);

    // 닉네임도 email과 동일한 이유로 deletedAt 필터 없이 전체에서 중복을 본다.
    boolean existsByNickname(String nickname);
}
