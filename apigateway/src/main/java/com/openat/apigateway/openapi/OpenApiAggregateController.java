package com.openat.apigateway.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class OpenApiAggregateController {

    private static final ParameterizedTypeReference<Map<String, Object>> OPENAPI_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient = WebClient.create();

    @Value("${openapi.aggregate.member-docs-url:http://localhost:9100/api-docs}")
    private String memberDocsUrl;

    @Value("${openapi.aggregate.settlement-docs-url:http://localhost:9140/api-docs}")
    private String settlementDocsUrl;

    @GetMapping(value = "/v3/api-docs/all", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> getAggregatedOpenApi() {
        // 각 서비스를 독립적으로 요청 — 실패한 서비스는 목록에서 제외하고 나머지만 합산
        return Flux.merge(
                        fetchServiceOpenApi("member-service", memberDocsUrl),
                        fetchServiceOpenApi("settlement-service", settlementDocsUrl)
                )
                .collectList()
                .map(this::mergeOpenApis);
    }

    /**
     * 서비스 OpenAPI 스펙을 가져온다.
     * 연결 실패·타임아웃·4xx/5xx 등 어떤 오류가 나도 {@code Mono.empty()}를 반환해
     * 해당 서비스를 조용히 건너뛰고 나머지 서비스만 집계에 포함시킨다.
     */
    private Mono<ServiceOpenApi> fetchServiceOpenApi(String name, String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(OPENAPI_TYPE)
                .map(spec -> new ServiceOpenApi(name, spec))
                .onErrorResume(e -> {
                    log.warn("[OpenAPI 집계] {} 스펙 로드 실패 — 건너뜀 (url={}, cause={})",
                            name, url, e.getMessage());
                    return Mono.empty();
                });
    }

    private Map<String, Object> mergeOpenApis(List<ServiceOpenApi> serviceOpenApis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openapi", "3.1.0");
        result.put("info", Map.of(
                "title", "OpenAt Gateway Integrated API",
                "description", "Member service and settlement service OpenAPI documents.",
                "version", "v1"
        ));
        result.put("servers", List.of(Map.of(
                "url", "http://localhost:8000",
                "description", "API Gateway"
        )));

        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();
        List<Object> tags = new ArrayList<>();
        List<Object> security = new ArrayList<>();
        List<String> aggregatedServices = new ArrayList<>();

        for (ServiceOpenApi serviceOpenApi : serviceOpenApis) {
            Map<String, Object> openApi = serviceOpenApi.openApi();
            aggregatedServices.add(serviceOpenApi.name());
            mergeObjectMap(paths, openApi.get("paths"));
            mergeObjectMap(components, openApi.get("components"));
            addList(tags, openApi.get("tags"));
            addList(security, openApi.get("security"));
        }

        result.put("paths", paths);
        result.put("components", components);
        if (!tags.isEmpty()) {
            result.put("tags", tags);
        }
        if (!security.isEmpty()) {
            result.put("security", security);
        }
        result.put("x-aggregated-services", aggregatedServices);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void mergeObjectMap(Map<String, Object> target, Object source) {
        if (!(source instanceof Map<?, ?> sourceMap)) {
            return;
        }

        for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object sourceValue = entry.getValue();
            Object targetValue = target.get(key);

            if (targetValue instanceof Map<?, ?> targetMap && sourceValue instanceof Map<?, ?>) {
                mergeObjectMap((Map<String, Object>) targetMap, sourceValue);
            } else {
                target.put(key, sourceValue);
            }
        }
    }

    private void addList(List<Object> target, Object source) {
        if (source instanceof List<?> sourceList) {
            target.addAll(sourceList);
        }
    }

    private record ServiceOpenApi(String name, Map<String, Object> openApi) {
    }
}
