import com.google.protobuf.gradle.*
plugins {
    id("org.springframework.boot") version "3.5.3"
    id("org.flywaydb.flyway") version "11.10.1"
    id("com.google.protobuf")
}

dependencies {
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    implementation("org.springframework.boot:spring-boot-starter:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-actuator:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.3")
    runtimeOnly("org.postgresql:postgresql:42.7.7")
    implementation("org.flywaydb:flyway-core:9.22.3")
    implementation(project(":common-library"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.61.0"
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
