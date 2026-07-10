import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "7.0.2" apply false
    java
}
tasks.jar { enabled = false }
group = "com.openat"
version = "1.0-SNAPSHOT"

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    group = "com.openat"
    version = "0.0.1-SNAPSHOT"

    // Spotless: 포맷 통일(google-java-format, 2-space). 비차단 시작 —
    // ratchetFrom("origin/dev")로 dev 이후 "변경된 파일만" 검사해 기존 코드(member 4-space 등)는
    // 손대지 않고 신규 변경분부터 수렴시킨다(argocd_ci_smoke_plan WS-E). 팀 컨벤션 합의 후 차단 전환.
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        ratchetFrom("origin/dev")
        java {
            googleJavaFormat()
            targetExclude("**/build/**")
        }
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    extra["springCloudVersion"] = "2025.1.2"

    configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${extra["springCloudVersion"] as String}")
        }
    }

    dependencies {
        compileOnly("org.projectlombok:lombok")
        annotationProcessor("org.projectlombok:lombok")
        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    if (project.name != "apigateway" && project.name != "common") {
        dependencies {
            implementation("org.springframework.boot:spring-boot-starter-data-jpa")
            implementation("org.springframework.boot:spring-boot-starter-web")
            // IntelliJ Run/Gradle bootRun에서도 루트 .env를 자동으로 읽어 ${DB_USER} 같은 placeholder를 채워줌
            implementation("me.paulschwarz:springboot4-dotenv:5.1.0")
            implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
            runtimeOnly("org.postgresql:postgresql")

            //Security
            implementation("org.springframework.boot:spring-boot-starter-security")
            //JWT
            implementation("io.jsonwebtoken:jjwt-api:0.12.3")
            runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
            runtimeOnly("io.jsonwebtoken:jjwt-gson:0.12.3")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
