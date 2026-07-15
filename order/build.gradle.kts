dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    // k8s readiness/liveness probe + Prometheus 메트릭 노출 (버전은 Boot BOM/micrometer-bom 관리)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    //redis, kafka
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // Spring Boot 4.x는 RestClient 자동설정(RestClient.Builder 빈, 트레이스 계측 포함)이 별도 모듈로
    // 분리됨 — 없으면 ProductClientConfig의 RestClient.Builder 주입이 실패해 기동이 깨짐(payment/settlement와 동일 이유)
    implementation("org.springframework.boot:spring-boot-restclient")
}
