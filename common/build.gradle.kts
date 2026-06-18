// 공통 모듈: 실행 애플리케이션이 아닌 라이브러리로만 사용한다.
// 따라서 실행 가능한 부트 JAR은 끄고 일반 JAR만 생성한다.
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }

dependencies {
    // 공통 응답/에러 표준이 검증 실패(@Valid)도 일관되게 처리하므로 validation을 공통에서 제공
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
