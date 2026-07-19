dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    //kafka
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus 메트릭 노출 (버전은 micrometer-bom 관리)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    //flyway (payment 모듈 단독 마이그레이션 — 6개 엔티티의 테이블 생성)
    // Spring Boot 4.x는 Flyway 자동설정이 별도 모듈로 분리되어, DB 드라이버(flyway-database-postgresql)만으론 부트 시점에 실행되지 않음
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    //PK UUIDv7 생성 (project_guide.md 기술스택 컨벤션 — UUID.randomUUID()는 v4라 컨벤션 위반)
    implementation("com.github.f4b6a3:uuid-creator:5.3.3")
    // Spring Boot 4.x는 Kafka 자동설정(@KafkaListener 등록 등)도 별도 모듈로 분리됨(flyway와 같은 이유)
    implementation("org.springframework.boot:spring-boot-kafka")
    // Spring Boot 4.x는 RestClient 자동설정(RestClient.Builder 빈, 트레이스 계측 포함)도 별도 모듈로
    // 분리됨(flyway/kafka와 같은 이유) — 없으면 OrderClientConfig/TossClientConfig의
    // RestClient.Builder 주입이 실패해 real 프로필 기동이 깨짐(7-15 research)
    implementation("org.springframework.boot:spring-boot-restclient")
    // 아웃바운드 회복탄력성(토스 PG·주문검증) — 코어 프로그래매틱 사용(Boot 4.1 AOP 리스크로 스타터·어노테이션 미사용).
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.3.0")
    // 서킷/리미터 상태를 기존 프로메테우스 노출로 바인딩(TaggedCircuitBreakerMetrics·TaggedRateLimiterMetrics)
    implementation("io.github.resilience4j:resilience4j-micrometer:2.3.0")
}

tasks.withType<Test> {
    // dotenv 플러그인이 루트 .env를 찾도록 테스트 작업 디렉터리를 루트로 고정
    workingDir = rootProject.projectDir
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    // bootRun의 기본 workingDir은 서브모듈 디렉터리라 dotenv가 루트 .env를 못 찾음(Test와 동일한 이유) —
    // 그 결과 application-local.yml의 ${DB_USER}/${DB_PASSWORD}가 치환 안 되고 그대로 인증 시도되어 실패함.
    workingDir = rootProject.projectDir
}
