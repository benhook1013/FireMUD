plugins {
    id("io.gatling.gradle") version "3.15.0"
}

dependencies {
    implementation("io.gatling:gatling-core:3.14.9")
    implementation("io.gatling:gatling-http:3.14.9")
}

// keep simulations under src/gatling
