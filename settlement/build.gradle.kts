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
}
