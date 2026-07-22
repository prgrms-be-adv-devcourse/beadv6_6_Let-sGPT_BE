package com.openat.apigateway.admission;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 클라이언트 주도 진입 통제: 드롭 주문 생성 요청은 입장권
 * ({@code admission:{dropId}:{userId}} Redis 키)이 있어야만 통과시킨다. 정적 "핫 드롭" 목록은
 * 없다 - dropId가 파싱되는 모든 주문 요청에 균일하게 적용된다(오버셀 방지는 product 모듈의
 * 원자적 재고 차감이 이미 별도로 보장하므로, 이 계층은 순수하게 "순서대로 공정하게" 대기열을
 * 강제하는 UX 계층이다. 경쟁이 없는 드롭은 queue 모듈의 즉시 입장 fast path 덕분에 사실상
 * 대기 없이 입장권을 받으므로, 균일 적용에 따른 체감 지연은 실제 경쟁이 있는 드롭에만 생긴다).
 *
 * <p>흐름: POST 본문을 한 번 읽어 dropId를 파싱한 뒤, 다운스트림이 여전히 원본 본문을
 * 읽을 수 있도록 {@link ServerHttpRequestDecorator}로 body를 다시 흘려보낸다(캐시).
 * dropId 파싱 실패면 그대로 통과(하류 @Valid가 처리). 파싱되면 JWT의 sub(userId)로 입장권
 * 키를 조회해 {@code GETDEL} 한 번으로 "유효하고 아직 안 썼는가"를 원자적으로 확인 + 소진한다.
 * 입장권이 없으면 419 + 대기열 엔드포인트 안내를 반환한다(자동 리다이렉트/자동 enqueue 없음 -
 * 클라이언트는 그래서 주문 전에 항상 먼저 {@code /queues/{dropId}/entry}를 호출해야 한다).
 *
 * <p>이미 인증(JWT 검증)이 끝난 뒤에만 도달하는 라우트(anyExchange().access(...)가
 * order 경로를 이미 커버)이므로 별도 인증 계층을 추가하지 않는다 - {@code UserContextRelayFilter}와
 * 동일하게 {@link ReactiveSecurityContextHolder}에서 이미 검증된 JWT를 읽기만 한다.
 */
@Component
public class AdmissionCheckGatewayFilterFactory
        extends AbstractGatewayFilterFactory<AdmissionCheckGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(AdmissionCheckGatewayFilterFactory.class);
    private static final String ADMISSION_ERROR_CODE = "QUEUE_ADMISSION_REQUIRED";
    private static final String QUANTITY_MISMATCH_ERROR_CODE = "QUEUE_QUANTITY_MISMATCH";
    private static final String PAYLOAD_TOO_LARGE_ERROR_CODE = "PAYLOAD_TOO_LARGE";

    private final AdmissionProperties admissionProperties;
    private final ApiErrorResponseWriter responseWriter;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisScript<Long> releaseOutstandingScript =
            RedisScript.of(new ClassPathResource("redis/release-outstanding.lua"), Long.class);
    private final RedisScript<Long> releaseAdmittedTrackingScript =
            RedisScript.of(new ClassPathResource("redis/release-admitted-tracking.lua"), Long.class);
    private final RedisScript<Long> restoreAdmissionScript =
            RedisScript.of(new ClassPathResource("redis/restore-admission.lua"), Long.class);

    public AdmissionCheckGatewayFilterFactory(
            AdmissionProperties admissionProperties,
            ApiErrorResponseWriter responseWriter,
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        super(Config.class);
        this.admissionProperties = admissionProperties;
        this.responseWriter = responseWriter;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            if (request.getMethod() != HttpMethod.POST) {
                return chain.filter(exchange);
            }

            // 이 필터는 dropId 파싱을 위해 본문 전체를 메모리에 버퍼링하므로 반드시 상한을 건다 -
            // 상한이 없으면 대용량 본문 몇 개로 게이트웨이(전 서비스 공유 진입점)의 메모리를
            // 고갈시킬 수 있다. 1차: Content-Length 선차단(본문을 읽기도 전에 거부 - 단
            // chunked 인코딩은 길이를 신고하지 않으므로 이것만으론 불완전). 2차: join의
            // maxByteCount(실제 수신 바이트 기준 하드 캡 - chunked까지 잡는 최종 방어).
            long contentLength = request.getHeaders().getContentLength();
            if (contentLength > admissionProperties.maxBodyBytes()) {
                return payloadTooLarge(exchange, contentLength);
            }

            return DataBufferUtils.join(request.getBody(), admissionProperties.maxBodyBytes())
                    .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                    .flatMap(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);

                        ServerWebExchange cachedExchange = exchange.mutate()
                                .request(new CachedBodyRequestDecorator(
                                        request, bytes, exchange.getResponse().bufferFactory()))
                                .build();

                        // dropId 파싱 실패/필드 없음이면 하류 @Valid가 처리하도록 그냥 통과시킨다
                        // (검증 계층 중복 없음). 정적 hot-drops 목록은 없다 - dropId가 있는 모든
                        // 주문 요청이 입장권 검증을 받는다(모든 드롭에 균일 적용).
                        String dropId = extractDropId(bytes);
                        if (dropId == null) {
                            return chain.filter(cachedExchange);
                        }
                        Integer requestedQuantity = extractQuantity(bytes);

                        // 주의: Mono<Void>는 성공하더라도 onNext를 방출하지 않아 항상 "empty"로
                        // 취급되므로, flatMap(... -> Mono<Void>) 뒤에 switchIfEmpty를 붙이면
                        // 실제 결과와 무관하게 항상 재실행되는 Reactor 함정이 있다(과거 버그).
                        // 그래서 분기 판단은 Mono<Void>가 아닌 값 타입(String/Boolean)
                        // 단계에서 defaultIfEmpty/hasElement로 먼저 끝내고, 그 값으로
                        // 단 하나의 Mono<Void> 액션만 고르는 flatMap을 마지막에 둔다.
                        return resolveUserId()
                                .defaultIfEmpty("")
                                .flatMap(userId -> userId.isEmpty()
                                        // 이 라우트는 이미 인증 필수 구간이라 통상 발생하지 않지만,
                                        // 방어적으로 인증 정보가 없으면 하류(도메인 서비스)의 판단에 맡긴다.
                                        ? chain.filter(cachedExchange)
                                        : checkAdmission(cachedExchange, chain, dropId, userId, requestedQuantity));
                    })
                    // Content-Length 미신고(chunked) 요청이 상한을 넘기면 join(maxByteCount)이
                    // DataBufferLimitException으로 끊는다 - 413으로 응답(최종 방어).
                    .onErrorResume(DataBufferLimitException.class, e -> payloadTooLarge(exchange, -1));
        };
    }

    /** 본문이 버퍼링 상한을 넘는 요청을 읽지 않고(또는 읽다 말고) 413으로 거부한다. */
    private Mono<Void> payloadTooLarge(ServerWebExchange exchange, long declaredLength) {
        log.info("[admission-payload-too-large] declaredContentLength={} maxBodyBytes={}",
                declaredLength, admissionProperties.maxBodyBytes());
        return responseWriter.write(
                exchange,
                HttpStatusCode.valueOf(413),
                PAYLOAD_TOO_LARGE_ERROR_CODE,
                "요청 본문이 허용 크기(%d bytes)를 초과했습니다.".formatted(admissionProperties.maxBodyBytes()));
    }

    private Mono<String> resolveUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(auth -> auth.getToken().getSubject());
    }

    /**
     * 재고 인지형 검증: 입장권 값이 "수량"이다. 반드시 {@code GETDEL}로 먼저 원자
     * 소진한 뒤에 주문 요청 본문의 quantity와 비교한다 - peek(GET) 후 별도 delete로 나누면
     * 그 사이의 좁은 창에서 동시 요청(중복 클릭/재시도) 두 개가 같은 티켓을 동시에 통과시킬 수
     * 있는 레이스가 생기기 때문이다(이 시스템의 핵심 보장인 "오버셀 0"을 깨는 결함이라 반드시
     * 원자 소진이 먼저).
     *
     * 수량이 어긋나면(예: 3개 입장권인데 5개 주문 시도) 이미 소진된 티켓이므로 되돌릴 수 없고,
     * 대신 outstanding만큼은 즉시 반환해(그렇지 않으면 이 자리가 영구히 묶임) 400으로 거부한다 -
     * 사용자는 다시 대기열에 진입해야 한다(정상 클라이언트라면 상태 폴링에서 받은 수량 그대로
     * 주문하므로 흔치 않은 경로).
     *
     * 수량이 맞으면 {@link Mono#usingWhen}으로 주문 응답이 끝나는 시점에 뒤처리를 한다 -
     * order/product 모듈을 건드리지 않고, 게이트웨이가 이미 리버스 프록시로서 보고 있는
     * 응답 완료 시점만으로 "점유 해제"를 감지한다. 뒤처리는 응답 결과에 따라 갈린다
     * ({@link #settleAfterResponse} 참고):
     * <ul>
     *   <li>2xx(주문 실제 생성 성공) - admitted 추적만 정리하고 outstanding은 여기서 안 푼다.
     *       product가 이미 재고를 차감했고 CREATED 이벤트가 곧 발행될 것이므로, queue의
     *       StockAdjustmentConsumer가 그 이벤트를 컨슘하는 순간 reserved 증가와 원자적으로
     *       같이 outstanding을 넘겨받는다({@link #releaseAdmittedTrackingOnly} 참고 - "이중
     *       공백" 레이스를 닫기 위한 버그 수정, 예전엔 여기서 즉시 풀었다).</li>
     *   <li>그 외 4xx(검증 실패 등, CREATED 이벤트가 애초에 안 나감) - 즉시 전부 release.
     *       클라이언트 책임이라 티켓 소진이 정당하고, 아무도 나중에 outstanding을 대신
     *       넘겨받지 않으므로 지금 풀어야 한다.</li>
     *   <li>다운스트림 5xx 또는 연결오류(예외) - 티켓 복구(restore-admission.lua). 사용자
     *       잘못이 아닌 서버 장애로 몇 시간 기다린 순번을 잃지 않도록, 소진했던 입장권을
     *       남은 TTL로 되살리고 outstanding은 유지한다(다음 폴링에서 다시 READY).</li>
     *   <li>취소(클라이언트 이탈) - 기존과 동일하게 전부 release(서버 장애 복구 범위 아님).</li>
     * </ul>
     */
    private Mono<Void> checkAdmission(ServerWebExchange exchange, GatewayFilterChain chain,
                                       String dropId, String userId, Integer requestedQuantity) {
        String admissionKey = "admission:" + dropId + ":" + userId;
        return redisTemplate.opsForValue().getAndDelete(admissionKey)
                .defaultIfEmpty("")
                .flatMap(qtyStr -> {
                    if (qtyStr.isEmpty()) {
                        log.info("[admission-denied] dropId={} userId={}", dropId, userId);
                        return responseWriter.write(
                                exchange,
                                HttpStatusCode.valueOf(419),
                                ADMISSION_ERROR_CODE,
                                admissionProperties.guidanceMessage());
                    }

                    int ticketQuantity = Integer.parseInt(qtyStr);
                    if (requestedQuantity == null || !requestedQuantity.equals(ticketQuantity)) {
                        log.info("[admission-quantity-mismatch] dropId={} userId={} ticketQty={} requestedQty={}",
                                dropId, userId, ticketQuantity, requestedQuantity);
                        return releaseOutstanding(dropId, userId, ticketQuantity)
                                .then(responseWriter.write(
                                        exchange,
                                        HttpStatusCode.valueOf(400),
                                        QUANTITY_MISMATCH_ERROR_CODE,
                                        "발급된 입장권의 수량(%d개)과 주문 수량이 다릅니다. 입장권은 소진되었으니 "
                                                + "다시 대기열에 진입해 주세요.".formatted(ticketQuantity)));
                    }

                    return Mono.usingWhen(
                            Mono.just(ticketQuantity),
                            qty -> chain.filter(exchange),
                            qty -> settleAfterResponse(exchange, dropId, userId, qty),
                            (qty, error) -> restoreAdmission(dropId, userId, qty),
                            qty -> releaseOutstanding(dropId, userId, qty)
                    );
                });
    }

    /**
     * 정상 완료된 응답의 상태코드로 뒤처리를 고른다.
     *
     * <ul>
     *   <li>5xx(다운스트림 장애) - 티켓 복구(변경 없음).</li>
     *   <li>2xx(주문 실제 생성 성공) - outstanding을 여기서 즉시 풀지 않는다(admitted 추적만
     *       정리). CREATED 이벤트가 곧 발행될 것이므로, queue의 StockAdjustmentConsumer가
     *       그 이벤트를 컨슘하는 순간 reserved 증가와 원자적으로 같이 넘겨받는다 - "이중
     *       공백" 레이스 수정, {@link #releaseAdmittedTrackingOnly} 자바독 참고.</li>
     *   <li>그 외(4xx 등, 주문 자체가 성립 안 함 - product의 decreaseStock이 예외를 던져
     *       CREATED 이벤트가 애초에 발행되지 않음) - 즉시 전부 해제한다. 아무도 이 몫을
     *       나중에 대신 넘겨받지 않으므로 지금 풀어야 이 자리가 영구히 묶이지 않는다.</li>
     * </ul>
     */
    private Mono<Long> settleAfterResponse(ServerWebExchange exchange, String dropId, String userId, int quantity) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (status != null && status.is5xxServerError()) {
            return restoreAdmission(dropId, userId, quantity);
        }
        if (status != null && status.is2xxSuccessful()) {
            return releaseAdmittedTrackingOnly(dropId, userId);
        }
        return releaseOutstanding(dropId, userId, quantity);
    }

    /**
     * 다운스트림 장애(5xx/연결오류)로 실패한 주문의 입장권을 남은 TTL로 되살린다
     * (restore-admission.lua - 스위퍼가 이미 회수했거나 만료 임박이면 복구하지 않는 멱등 처리).
     * outstanding은 건드리지 않는다 - 티켓이 살아있는 한 그 수량의 점유는 유효해야
     * available 계산이 안 깨진다.
     */
    private Mono<Long> restoreAdmission(String dropId, String userId, int quantity) {
        return redisTemplate.execute(
                restoreAdmissionScript,
                List.of("admitted:" + dropId, "admission:" + dropId + ":" + userId),
                List.of(userId, String.valueOf(quantity), String.valueOf(System.currentTimeMillis()))
        ).next().doOnNext(restored -> {
            if (restored > 0) {
                log.info("[admission-restored] dropId={} userId={} qty={} - 다운스트림 장애로 입장권 복구", dropId, userId, quantity);
            } else {
                log.info("[admission-restore-skipped] dropId={} userId={} - 이미 회수됐거나 만료 임박(재진입 필요)", dropId, userId);
            }
        });
    }

    /** 주문 요청이 끝난 뒤(실패/취소, CREATED 이벤트가 나가지 않는 경로) 미소진 입장권 추적에서 제거하고 outstanding을 즉시 되돌린다. */
    private Mono<Long> releaseOutstanding(String dropId, String userId, int quantity) {
        return redisTemplate.execute(
                releaseOutstandingScript,
                List.of("admitted:" + dropId, "admitted:" + dropId + ":qty", "outstanding:" + dropId),
                List.of(userId, String.valueOf(quantity))
        ).next().doOnNext(released -> {
            if (released > 0) {
                log.debug("[admission-outstanding-released] dropId={} userId={} qty={}", dropId, userId, released);
            }
        });
    }

    /**
     * 주문이 성공(2xx)했을 때만 쓰는 "부분" 해제 - admitted 추적(ZSET/HASH)만 정리하고
     * outstanding은 일부러 그대로 둔다. queue의 StockAdjustmentConsumer가 이 사용자의
     * CREATED 이벤트를 컨슘하는 순간 reserved 증가와 원자적으로 같이 outstanding을 넘겨받는다
     * (apply-created-reservation.lua) - 그래서 이 메서드는 outstanding을 스크립트에 넘기지도
     * 않는다(release-admitted-tracking.lua는 그 키를 아예 모른다).
     *
     * <p>버그 이력(라이브 데모에서 재현, "이중 공백" 레이스): 예전엔 {@link #releaseOutstanding}을
     * 성공 경로에도 그대로 써서 outstanding을 즉시 풀었는데, reserved 증가(Kafka 이벤트 컨슘)는
     * 항상 그보다 뒤늦게 일어나므로 그 사이 밀리초 단위 창에서 admit.lua가 과다 admission을
     * 일으켰다. 자세한 배경은 release-admitted-tracking.lua 헤더 주석 참고.
     */
    private Mono<Long> releaseAdmittedTrackingOnly(String dropId, String userId) {
        return redisTemplate.execute(
                releaseAdmittedTrackingScript,
                List.of("admitted:" + dropId, "admitted:" + dropId + ":qty"),
                List.of(userId)
        ).next().doOnNext(cleared -> {
            if (cleared > 0) {
                log.debug("[admission-admitted-tracking-cleared] dropId={} userId={} (outstanding은 CREATED 컨슘 시점에 인계)", dropId, userId);
            }
        });
    }

    /** body 파싱 실패/필드 없음 → null 반환해 통과시킨다(하류 @Valid가 400 처리, 검증 계층 중복 없음). */
    private String extractDropId(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode dropIdNode = node.get("dropId");
            return (dropIdNode == null || dropIdNode.isNull()) ? null : dropIdNode.asString();
        } catch (Exception e) {
            return null;
        }
    }

    /** body 파싱 실패/필드 없음 → null(입장권 수량과 비교 시 항상 불일치로 처리되어 안전 측으로 거부됨). */
    private Integer extractQuantity(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode quantityNode = node.get("quantity");
            return (quantityNode == null || quantityNode.isNull()) ? null : quantityNode.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    /** 이미 읽은 본문 바이트를 다운스트림(프록시 대상)에 다시 흘려보내기 위한 캐시 래퍼. */
    private static final class CachedBodyRequestDecorator extends ServerHttpRequestDecorator {
        private final byte[] cachedBody;
        private final DataBufferFactory bufferFactory;

        private CachedBodyRequestDecorator(ServerHttpRequest delegate, byte[] cachedBody, DataBufferFactory bufferFactory) {
            super(delegate);
            this.cachedBody = cachedBody;
            this.bufferFactory = bufferFactory;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            if (cachedBody.length == 0) {
                return Flux.empty();
            }
            return Flux.just(bufferFactory.wrap(cachedBody));
        }
    }

    public static class Config {
    }
}
