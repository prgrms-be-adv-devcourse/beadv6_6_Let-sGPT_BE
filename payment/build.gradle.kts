dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    //kafka
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    //flyway (payment 모듈 단독 마이그레이션 — 6개 엔티티의 테이블 생성)
    implementation("org.flywaydb:flyway-database-postgresql")
    //PK UUIDv7 생성 (project_guide.md 기술스택 컨벤션 — UUID.randomUUID()는 v4라 컨벤션 위반)
    implementation("com.github.f4b6a3:uuid-creator:5.3.3")
}