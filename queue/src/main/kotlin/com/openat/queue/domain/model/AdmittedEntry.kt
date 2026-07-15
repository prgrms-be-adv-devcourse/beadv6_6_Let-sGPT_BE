package com.openat.queue.domain.model

/**
 * 한 번의 admit 실행(스케줄러 tick)에서 실제로 입장 처리된 사용자 1명.
 *
 * @param userId 입장 처리된 사용자
 * @param quantity 발급된 입장권의 수량 - 게이트웨이가 주문 요청의 quantity와 대조(불일치 시 거부)한 뒤
 *   소진하고, 소진 완료 시 이만큼 outstanding에서 되돌린다.
 */
data class AdmittedEntry(
    val userId: String,
    val quantity: Int,
)
