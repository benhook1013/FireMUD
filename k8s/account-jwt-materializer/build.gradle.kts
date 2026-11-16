plugins {
    application
}

dependencies {
    implementation(project(":common-platform-core"))
    implementation(libs.protobuf.java)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.spring.boot.starter.test)
}

application {
    mainClass.set("net.firedevops.firemud.accountjwtmaterializer.MaterializerMain")
}

tasks.jar {
    archiveFileName.set("account-jwt-materializer.jar")
}
