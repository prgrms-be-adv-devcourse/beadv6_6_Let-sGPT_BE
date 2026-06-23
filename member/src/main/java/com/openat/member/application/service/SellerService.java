package com.openat.member.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import com.openat.member.application.dto.UpdateSellerInfoRequest;
import com.openat.member.application.usecase.SellerUseCase;
import com.openat.member.domain.exception.MemberErrorCode;
import com.openat.member.domain.exception.SellerErrorCode;
import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.SellerInfo;
import com.openat.member.domain.repository.MemberRepository;
import com.openat.member.domain.repository.SellerInfoRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * member-SellerInfo는 1:N이지만, 논리적 삭제를 활용해 "활성 SellerInfo는 회원당 최대 1개"라는
 * 불변식을 여기서 보장한다. 프론트에는 항상 활성 SellerInfo 1건(또는 없음)만 노출된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerService implements SellerUseCase {

    private final MemberRepository memberRepository;
    private final SellerInfoRepository sellerInfoRepository;

    /**
     * 활성 SellerInfo가 없을 때만(null일 때만) 생성 가능 — 이미 있으면 PATCH/PUT을 쓰라는 의미로 충돌 처리.
     */
    @Override
    @Transactional
    public SellerInfoResponse create(UUID memberId, CreateSellerInfoRequest request) {
        Member member = getMember(memberId);

        if (sellerInfoRepository.existsActiveByMemberId(memberId)) {
            throw new BusinessException(SellerErrorCode.SELLER_INFO_ALREADY_EXISTS);
        }

        SellerInfo sellerInfo = SellerInfo.builder()
                .businessNumber(request.businessNumber())
                .storeName(request.storeName())
                .member(member)
                .build();
        SellerInfo saved = sellerInfoRepository.save(sellerInfo);

        member.promoteToSeller();

        return SellerInfoResponse.from(saved);
    }

    /**
     * 기존에 활성 SellerInfo가 있다면 논리적 삭제하고, 이번 PUT 요청 값으로 새로 생성한다(없었으면 그냥 생성).
     * businessNumber까지 같이 바꿀 수 있는 유일한 방법이라 갈아끼우는 방식을 쓴다.
     */
    @Override
    @Transactional
    public SellerInfoResponse update(UUID memberId, UpdateSellerInfoRequest request) {
        Member member = getMember(memberId);

        sellerInfoRepository.findActiveByMemberId(memberId)
                .ifPresent(SellerInfo::markDeleted);

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
    public SellerInfoResponse patch(UUID memberId, PatchSellerInfoRequest request) {
        SellerInfo sellerInfo = sellerInfoRepository.findActiveByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_INFO_NOT_FOUND));

        sellerInfo.changeStoreName(request.storeName());

        return SellerInfoResponse.from(sellerInfo);
    }

    /**
     * 논리적 삭제 후, 더 이상 활성 SellerInfo가 남지 않으므로(불변식: 회원당 활성은 최대 1개) role을 USER로 내린다.
     */
    @Override
    @Transactional
    public void delete(UUID memberId) {
        Member member = getMember(memberId);

        SellerInfo sellerInfo = sellerInfoRepository.findActiveByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_INFO_NOT_FOUND));

        sellerInfo.markDeleted();
        member.demoteToUser();
    }

    private Member getMember(UUID memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
