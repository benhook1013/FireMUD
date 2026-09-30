plugins {
    id("io.gatling.gradle") version "3.16.0"
}

dependencies {
    implementation("io.gatling:gatling-core:3.16.0")
    implementation("io.gatling:gatling-http:3.16.0")
}

// keep simulations under src/gatling
