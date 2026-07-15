import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "7.0.2" apply false

    kotlin("jvm") version "2.1.20" apply false
    kotlin("plugin.spring") version "2.1.20" apply false
    kotlin("plugin.jpa") version "2.1.20" apply false
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
    //
    // ratchetFrom은 내부적으로 JGit으로 git 저장소를 찾는데, git worktree(`.git`이 디렉터리가
    // 아니라 실제 저장소를 가리키는 포인터 파일인 체크아웃 - `git worktree add`로 만든 브랜치별
    // 작업 디렉터리)에서는 JGit이 그 포인터를 못 찾아 "Cannot find git repository in any parent
    // directory"로 태스크 생성 자체가 실패한다(메인 클론에서는 `.git`이 진짜 디렉터리라 문제없음).
    // 그래서 `.git`이 진짜 디렉터리일 때만 Spotless를 적용한다 - 메인 클론/CI는 기존과 동일하게
    // ratchet 검사가 걸리고, worktree 체크아웃에서는 빌드가 깨지지 않도록 Spotless 자체를 건너뛴다.
    if (File(rootDir, ".git").isDirectory) {
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            ratchetFrom("origin/dev")
            java {
                googleJavaFormat()
                targetExclude("**/build/**")
            }
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
