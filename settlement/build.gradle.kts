dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    //batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    // Kafka Consumer + DLQ Producer
    implementation("org.springframework.kafka:spring-kafka")
}
