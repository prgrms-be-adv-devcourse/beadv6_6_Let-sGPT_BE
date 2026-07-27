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

    // stage1(MVC+폴링)에서는 대기열 자료구조(ZSET)+입장권 저장소가 이 블로킹 스타터만으로
    // 충분했다. stage2-webflux-sse-full로 오면서 요청 경로(WaitingQueueRepository/
    // StockRepository/ConfirmedSalesRepository)는 전부 data-redis-reactive 쪽으로 옮겼지만,
    // 이 스타터는 여전히 남아있다 - ReservedStockRepository(Kafka 컨슈머 전용, 요청 경로
    // 밖이라 재작성 범위 밖)가 아직 이걸 쓴다.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // WebFlux+SSE 전환분: 웹 계층(spring.main.web-application-type=reactive, application.yml)뿐
    // 아니라 Redis 접근 계층(WaitingQueueRepository/StockRepository/ConfirmedSalesRepository)도
    // ReactiveStringRedisTemplate으로 전면 재작성했다 - Schedulers.boundedElastic() 브릿지가
    // 요청 경로에 없다(예외 하나: DropSnapshotBootstrapper의 product REST 호출 - Redis가 아니라
    // REST이고 드롭당 캐시 미스 1회뿐이라 범위 밖으로 명시적으로 남겨둠, 해당 클래스 주석 참고).
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    // mapNotNull 등 Mono/Flux용 코틀린 확장 함수 - 위 재작성 전반에서 "값 있으면 변환, 없으면
    // 빈 Mono"를 표현하는 데 계속 쓰인다.
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    // Reactor -> 코루틴 전환분: Mono<T>.awaitSingle()/awaitSingleOrNull(), Flux<T>.asFlow() 같은
    // 브릿지 확장 함수 + Spring WebFlux의 suspend fun 컨트롤러 지원(CoroutinesUtils)이 이 의존성을
    // 요구한다. ReactiveStringRedisTemplate 자체는 그대로 두고(Redis 접근은 여전히 리액티브
    // 드라이버 기반), 그 위에서 코루틴 스타일로 호출부를 다시 쓰는 방식 - Redis 병목 자체를 손대는
    // 작업(Option B)은 이번 세션에서 실측으로 전제가 깨져 범위 밖으로 확정됨(별도 기록 참고).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

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

    // QueueStreamService 동시성 테스트(재연결 레이스, 재조회 순서 보장) - runTest/가상 시간으로
    // delay()가 실제로 기다리지 않게 하고, 코루틴 스케줄링을 결정적으로 제어한다. 버전은 위
    // kotlinx-coroutines-reactor(전이 의존성)로 실제 resolve되는 1.10.2에 맞춰 명시 고정.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
