dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    // k8s probe + Prometheus 메트릭
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-restclient")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
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
