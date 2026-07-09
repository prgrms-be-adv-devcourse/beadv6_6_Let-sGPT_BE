plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.openat"
version = "0.0.1-SNAPSHOT"
description = "apigateway"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "2025.1.2"

dependencies {
    implementation(project(":common"))

    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")

    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")

    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.3")

    // k8s readiness/liveness probe + Prometheus 메트릭 노출 (버전은 Boot BOM/micrometer-bom 관리)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
