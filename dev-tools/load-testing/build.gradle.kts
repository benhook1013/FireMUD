plugins {
    id("io.gatling.gradle") version "3.15.0.8"
}

dependencies {
    implementation("io.gatling:gatling-core:3.15.0")
    implementation("io.gatling:gatling-http:3.15.0")
}

// keep simulations under src/gatling
