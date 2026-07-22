package com.openat.queue.infrastructure.persistence

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * product의 `GET /api/v1/drops/{dropId}`(공개 REST API - Redis 직접접근 아님)를 캐시 미스 시
 * 1회만 호출해 queue 소유 부트스트랩 캐시(`total:{dropId}`, `drop-meta:{dropId}`)를 채운다.
 *
 * 예전엔 `remaining`/`closeAt`/`limitPerUser`를 product 소유 `drop:{dropId}` Redis 해시에서
 * 직접 HGET했다 - 다른 모듈의 내부 데이터스토어를 직접 침범하는 MSA 경계 위반이라 없앴다.
 * 지금은:
 * - `remaining`은 이 큐가 이미 갖고 있는 두 값(`total`, `reserved`)의 뺄셈으로 계산한다
 *   (`total - reserved`가 product의 실제 remaining과 수학적으로 항상 같음이 증명됨 -
 *   queue-remaining-sync 재설계 작업, `docs/_local/piped-snuggling-cascade.md` 참고).
 * - `total`(불변값)과 `closeAt`/`limitPerUser`(사실상 불변)는 이 클래스가 REST 1회 호출로
 *   같은 응답에서 한 번에 채운다 - "remaining은 계산 가능한데 closeAt/limitPerUser는 아직
 *   없음" 같은 분리된 상태가 생기지 않도록.
 *
 * `total`(캐시 있음/조회 성공)을 기준으로 캐시 완료 여부를 판단한다 - `total`이 null이면
 * (드롭이 아직 생성 전이거나 product가 잠시 응답 불가) 호출부가 안전하게 저하한다(성급한
 * 판정을 내리지 않고 대기시킴 - 예전 "product 캐시 미워밍" 저하와 동일한 조건).
 */
@Component
class DropSnapshotBootstrapper(
    private val redisTemplate: StringRedisTemplate,
    private val productRestClient: RestClient,
) {

    private val log = LoggerFactory.getLogger(DropSnapshotBootstrapper::class.java)

    fun ensureTotalCached(dropId: String): Long? {
        redisTemplate.opsForValue().get(RedisKeys.total(dropId))?.toLongOrNull()?.let { return it }

        return try {
            // 버그 이력(라이브 데모에서 실제 재현됨): product의 `WebConfig`가 `@InternalApi`가
            // 아닌 모든 컨트롤러에 `/api/v1` 접두어를 전역으로 붙인다(DropController도 해당 -
            // `@RequestMapping("/drops")`이지만 실제 경로는 `/api/v1/drops`). 이 접두어 없이
            // 호출하면 product가 라우팅되지 않은 경로를 500으로 응답해(404가 아니라) 여기서
            // 계속 실패했었다.
            val response = productRestClient.get()
                .uri("/api/v1/drops/{dropId}", dropId)
                .retrieve()
                .body(DropSnapshotResponse::class.java)

            val total = response?.totalQuantity?.toLong()
            if (total != null) {
                redisTemplate.opsForValue().set(RedisKeys.total(dropId), total.toString())
                val closeAtField = response.closeAt?.toEpochMilli()?.toString() ?: UNSET_SENTINEL
                val limitPerUserField = response.limitPerUser?.toString() ?: UNSET_SENTINEL
                redisTemplate.opsForHash<String, String>().putAll(
                    RedisKeys.dropMeta(dropId),
                    mapOf("closeAt" to closeAtField, "limitPerUser" to limitPerUserField),
                )
            } else {
                log.warn("[drop-snapshot] dropId={} 응답에 totalQuantity가 없습니다.", dropId)
            }
            total
        } catch (e: Exception) {
            // product가 잠시 응답 불가여도 큐 자체는 죽으면 안 된다 - null 반환 시 호출부가
            // "낙관적 최대를 모른다"로 안전하게 처리한다(성급한 판정을 내리지 않음).
            log.warn("[drop-snapshot] dropId={} product 드롭 조회 실패: {}", dropId, e.message)
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DropSnapshotResponse(
        val totalQuantity: Int? = null,
        val closeAt: Instant? = null,
        val limitPerUser: Int? = null,
    )

    companion object {
        private const val UNSET_SENTINEL = "-1"
    }
}
