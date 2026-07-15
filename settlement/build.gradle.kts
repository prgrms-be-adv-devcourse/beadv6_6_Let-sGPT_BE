dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    // k8s readiness/liveness probe + Prometheus 메트릭 노출 (버전은 Boot BOM/micrometer-bom 관리)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    //batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    // Kafka Consumer + DLQ Producer
    implementation("org.springframework.kafka:spring-kafka")
    // Spring Boot 4.x는 RestClient 자동설정(RestClient.Builder 빈, 트레이스 계측 포함)이 별도 모듈로
    // 분리됨 — 없으면 PaymentReconciliationClientConfig의 RestClient.Builder 주입이 실패해 기동이
    // 깨짐(7-15 research)
    implementation("org.springframework.boot:spring-boot-restclient")
}
