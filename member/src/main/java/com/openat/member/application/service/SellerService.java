package com.openat.member.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import com.openat.member.application.usecase.SellerUseCase;
import com.openat.member.domain.exception.MemberErrorCode;
import com.openat.member.domain.exception.SellerErrorCode;
import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.SellerInfo;
import com.openat.member.domain.repository.MemberRepository;
import com.openat.member.domain.repository.SellerInfoRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 정보(SellerInfo) 도메인 서비스.
 * 회원당 여러 개의 SellerInfo를 가질 수 있으며, 논리적 삭제로 이력을 보존한다.
 * 활성 SellerInfo가 하나라도 있으면 ROLE_SELLER, 없으면 ROLE_USER로 role을 관리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerService implements SellerUseCase {

    private final MemberRepository memberRepository;
    private final SellerInfoRepository sellerInfoRepository;

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

    /** 관리자 전용: 판매자 정보 UUID로 단건 조회. */
    @Override
    public SellerInfoResponse getSellerInfoById(UUID sellerId) {
        SellerInfo sellerInfo = sellerInfoRepository.findById(sellerId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_INFO_NOT_FOUND));
        return SellerInfoResponse.from(sellerInfo);
    }

    /** 판매자 정보 신규 등록. role을 ROLE_SELLER로 올린다. */
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

        member.promoteToSeller();

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
     * 논리적 삭제. 삭제 후 활성 건이 하나도 남지 않으면 role을 USER로 내린다.
     */
    @Override
    @Transactional
    public void delete(UUID memberId, UUID sellerId) {
        Member member = getMember(memberId);

        SellerInfo sellerInfo = sellerInfoRepository.findByIdAndMemberId(sellerId, memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_INFO_NOT_FOUND));

        sellerInfo.markDeleted();

        if (sellerInfoRepository.findActiveByMemberId(memberId).isEmpty()) {
            member.demoteToUser();
        }
    }

    private Member getMember(UUID memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
