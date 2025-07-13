import com.google.protobuf.gradle.*
import com.github.spotbugs.snom.SpotBugsTask
plugins {
    id("org.springframework.boot") version "3.5.3"
    id("org.flywaydb.flyway") version "11.10.2"
    id("com.google.protobuf")
}

dependencies {
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")
    compileOnly("org.projectlombok:lombok:1.18.38")
    implementation("org.flywaydb:flyway-core:11.10.2")
    implementation("org.mapstruct:mapstruct:1.6.3")
    implementation("org.springframework.boot:spring-boot-starter:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-actuator:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-data-redis:3.5.3")
    implementation(project(":common-library"))
    implementation("org.springframework.boot:spring-boot-starter-aop:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-mail:3.5.3")
    implementation("io.github.lognet:grpc-spring-boot-starter:5.2.0")
    implementation("io.micrometer:micrometer-core:1.15.1")
    implementation("io.micrometer:micrometer-registry-prometheus:1.15.1")
    implementation("io.opentelemetry:opentelemetry-api:1.38.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.38.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.38.0")
    implementation("com.stripe:stripe-java:24.14.0")
    implementation("com.github.bastiaanjansen:otp-java:2.1.0")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    runtimeOnly("org.postgresql:postgresql:42.7.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
    testImplementation("org.testcontainers:postgresql:1.19.7")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.73.0"
        }
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {
            it.plugins {
                id("grpc")
            }
        }
    }
}

sourceSets["main"].java.srcDirs(
    "build/generated/source/proto/main/java",
    "build/generated/source/proto/main/grpc"
)

sourceSets {
    main {
        proto.srcDir("../../protos")
    }
}

tasks.named<SpotBugsTask>("spotbugsMain") {
    dependsOn(tasks.named("compileJava"))
    classes = files(
        fileTree(project.layout.buildDirectory.dir("classes/java/main")) {
            exclude("**/proto/**")
        }
    )
}

tasks.named<SpotBugsTask>("spotbugsTest") {
    dependsOn(tasks.named("compileTestJava"))
    classes = files(
        fileTree(project.layout.buildDirectory.dir("classes/java/test")) {
            exclude("**/proto/**")
        }
    )
}

