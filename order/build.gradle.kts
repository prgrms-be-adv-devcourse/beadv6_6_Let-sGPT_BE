dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    // k8s probe + Prometheus 메트릭
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    //redis, kafka
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // Boot 4.x에서 분리된 RestClient 자동설정 — 없으면 RestClient.Builder 주입 실패로 기동 불가
    implementation("org.springframework.boot:spring-boot-restclient")

    // 리포지토리 JPQL 통합 테스트용
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test> {
    // dotenv가 루트 .env를 찾도록 고정
    workingDir = rootProject.projectDir
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    // dotenv가 루트 .env를 찾도록 고정 — 없으면 DB 계정 미치환으로 기동 실패
    workingDir = rootProject.projectDir
}
