package com.openat.member.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import com.openat.member.application.usecase.SellerUseCase;
import com.openat.member.domain.exception.MemberErrorCode;
import com.openat.member.domain.exception.SellerErrorCode;
import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleEntity;
import com.openat.member.domain.model.RoleHistory;
import com.openat.member.domain.model.SellerInfo;
import com.openat.member.domain.repository.MemberRepository;
import com.openat.member.domain.repository.RoleEntityRepository;
import com.openat.member.domain.repository.RoleHistoryRepository;
import com.openat.member.domain.repository.SellerInfoRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 정보(SellerInfo) 도메인 서비스.
 * 회원당 여러 개의 SellerInfo를 가질 수 있으며, 논리적 삭제로 이력을 보존한다.
 * 역할 승격/강등은 role_history 테이블에 이력을 남기는 방식으로 관리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerService implements SellerUseCase {

    private final MemberRepository memberRepository;
    private final SellerInfoRepository sellerInfoRepository;
    private final RoleEntityRepository roleEntityRepository;
    private final RoleHistoryRepository roleHistoryRepository;

    @Override
    public List<SellerInfoResponse> getMySellerInfo(UUID memberId, boolean isActive) {
        if (isActive) {
            return sellerInfoRepository.findAllByMemberId(memberId).stream()
                    .map(SellerInfoResponse::from)
                    .toList();
        }
        return sellerInfoRepository.findActiveByMemberId(memberId).stream()
                .map(SellerInfoResponse::from)
                .toList();
    }

    @Override
    public List<SellerInfoResponse> getSellerInfosByUserId(UUID userId) {
        return sellerInfoRepository.findAllByMemberId(userId).stream()
                .map(SellerInfoResponse::from)
                .toList();
    }

    /**
     * 판매자 정보 신규 등록.
     * 활성 SELLER 역할 이력이 없는 경우에만 role_history에 ROLE_SELLER 이력을 추가한다.
     * (이미 SELLER인 상태에서 추가 등록 시 중복 이력 방지)
     */
    @Override
    @Transactional
    public SellerInfoResponse create(UUID memberId, CreateSellerInfoRequest request) {
        Member member = getMember(memberId);

        SellerInfo sellerInfo = SellerInfo.builder()
                .businessNumber(request.businessNumber())
                .storeName(request.storeName())
                .member(member)
                .build();
        SellerInfo saved = sellerInfoRepository.save(sellerInfo);

        // 이미 SELLER 권한이 없는 경우에만 부여 (중복 이력 방지)
        boolean alreadySeller = roleHistoryRepository
                .findActiveByMemberIdAndRole(memberId, Role.ROLE_SELLER)
                .isPresent();
        if (!alreadySeller) {
            RoleEntity sellerRole = findRoleEntity(Role.ROLE_SELLER);
            roleHistoryRepository.save(RoleHistory.of(member, sellerRole));
        }

        return SellerInfoResponse.from(saved);
    }

    @Override
    @Transactional
    public SellerInfoResponse patch(UUID memberId, UUID sellerId, PatchSellerInfoRequest request) {
        SellerInfo sellerInfo = sellerInfoRepository.findByIdAndMemberId(sellerId, memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_INFO_NOT_FOUND));

        sellerInfo.changeStoreName(request.storeName());

        return SellerInfoResponse.from(sellerInfo);
    }

    /**
     * 판매자 정보 논리 삭제.
     * 삭제 후 활성 SellerInfo가 하나도 남지 않으면 role_history의 SELLER 이력을 회수(deleted_at 기록)한다.
     */
    @Override
    @Transactional
    public void delete(UUID memberId, UUID sellerId) {
        SellerInfo sellerInfo = sellerInfoRepository.findByIdAndMemberId(sellerId, memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_INFO_NOT_FOUND));

        sellerInfo.markDeleted();

        // 활성 SellerInfo가 모두 사라진 경우에만 SELLER 역할 회수
        if (sellerInfoRepository.findActiveByMemberId(memberId).isEmpty()) {
            roleHistoryRepository.findActiveByMemberIdAndRole(memberId, Role.ROLE_SELLER)
                    .ifPresent(RoleHistory::revoke);
        }
    }

    private Member getMember(UUID memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    private RoleEntity findRoleEntity(Role role) {
        return roleEntityRepository.findByRole(role)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_ROLE_NOT_CONFIGURED));
    }
}
