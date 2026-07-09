dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    // k8s readiness/liveness probe + Prometheus 메트릭 노출 (버전은 Boot BOM/micrometer-bom 관리)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // 리프레시 토큰 저장(TTL 자동 만료)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // RSA 공개키를 JWKS(JSON Web Key Set) 포맷으로 노출하기 위함
    // (버전은 apigateway가 spring-security-oauth2-jose로 끌어오는 버전과 맞춤)
    implementation("com.nimbusds:nimbus-jose-jwt:10.9")

    // 통합 테스트: MockMvc + TestContainers(PostgreSQL/Redis)
    // 버전은 Spring Boot BOM(testcontainers 2.0.5)이 관리
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}
