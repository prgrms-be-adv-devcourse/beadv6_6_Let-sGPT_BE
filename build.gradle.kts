import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    java
}
tasks.jar { enabled = false }
group = "com.openat"
version = "1.0-SNAPSHOT"

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")

    group = "com.openat"
    version = "0.0.1-SNAPSHOT"

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
        implementation("org.springframework.boot:spring-boot-starter-data-jpa")
        implementation("org.springframework.boot:spring-boot-starter-web")
        // IntelliJ Run/Gradle bootRun에서도 루트 .env를 자동으로 읽어 ${DB_USER} 같은 placeholder를 채워줌
        implementation("me.paulschwarz:springboot4-dotenv:5.1.0")
        implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.8")
        compileOnly("org.projectlombok:lombok")
        runtimeOnly("org.postgresql:postgresql")
        annotationProcessor("org.projectlombok:lombok")
        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")

        //Security
        implementation("org.springframework.boot:spring-boot-starter-security")
        //JWT
        implementation("io.jsonwebtoken:jjwt-api:0.12.3")
        runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
        runtimeOnly("io.jsonwebtoken:jjwt-gson:0.12.3")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // dotenv 플러그인이 루트 .env를 찾도록 테스트 작업 디렉터리를 루트로 고정
        workingDir = rootProject.projectDir
    }
}
