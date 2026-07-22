plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    // "plugin.jpa"는 적용하지 않는다 - queue는 @Entity/JpaRepository가 하나도 없는 Redis 전용
    // 모듈이라(WaitingQueueRedisRepository 등), JPA를 열어줄 이유가 없다(루트 build.gradle.kts
    // subprojects 블록도 queue를 spring-boot-starter-data-jpa/postgres 적용 대상에서 제외함).
}

dependencies {
    implementation(project(":common"))

    // Kotlin data class <-> JSON (Boot가 클래스패스에서 자동 감지해 KotlinModule을 등록한다)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // Spring이 코틀린 클래스의 파라미터 이름을 리플렉션으로 읽기 위해 필요(@ConfigurationProperties 등)
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // 대기열 자료구조(ZSET) + 입장권 저장소
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // 진입 요청 바디(QueueEntryRequest.quantity) 검증(@Min) - product/order와 동일 관례
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Phase B: order.completed 이벤트를 구독해 확정(결제완료) 수량을 자체 집계
    implementation("org.springframework.kafka:spring-kafka")
    // 버그 이력(라이브 통합 검증 중 재현됨) - `spring-kafka`만 있으면 컴파일은 되고 기동도 조용히
    // 성공하지만, StockAdjustmentConsumer의 @KafkaListener가 완전히 무시된다(에러 로그조차 없음).
    // 원인: Spring Boot 4.x는 Kafka 자동설정(@EnableKafka 상당의 리스너 어노테이션 처리,
    // ConsumerFactory/KafkaTemplate 빈 자동 생성 등)을 `spring-boot-autoconfigure`에서 떼어내
    // 별도 모듈(`spring-boot-kafka`)로 분리했다(payment 모듈이 동일한 이유로 이미 겪고 고친 문제 -
    // flyway/restclient도 같은 패턴, payment/build.gradle.kts 주석 참고). 이 의존성이 빠지면
    // `spring-kafka`의 클래스(KafkaTemplate 등)는 클래스패스에 있어도 그걸 스프링 빈으로
    // 자동 연결해줄 자동설정 자체가 없어서, @KafkaListener 메서드가 등록되지 않고 어떤 예외도
    // 던지지 않은 채 조용히 아무 일도 안 한다(디버그 로그에도 "kafka" 문자열이 전혀 안 잡힘).
    implementation("org.springframework.boot:spring-boot-kafka")

    // Phase B: product의 총재고(GET /drops/{dropId}) 1회 조회는 RestClient(spring-boot-starter-web -
    // 루트 build.gradle.kts subprojects 블록에 이미 전역 적용됨)로 충분해 별도 의존성 추가 불필요.

    // k8s readiness/liveness probe + Prometheus 메트릭 노출 (버전은 Boot BOM/micrometer-bom 관리)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Lua 스크립트(enqueue-or-admit 등)를 실제 Redis로 검증 - product의
    // DropCacheRedisAdaptorTest와 동일한 관례(버전 고정 이유도 동일: Testcontainers 2.x가
    // Spring BOM에 없어 명시적으로 고정해야 함).
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")

    // QueueService 단위 테스트(Docker 불필요) - 코틀린 인터페이스 mock에 관용적인 DSL(whenever/mock<T>()).
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        // "kotlin.plugin.spring"이 @Configuration/@Service/@Component 등을 자동으로 open
        // 처리한다(코틀린 클래스는 기본이 final이라 이 플러그인 없이는 CGLIB 프록시가 실패한다).
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// 코틀린 최상위 fun main()은 "QueueApplicationKt"로 컴파일되므로 bootJar가 찾을 메인 클래스를 명시한다.
springBoot {
    mainClass.set("com.openat.queue.QueueApplicationKt")
}
