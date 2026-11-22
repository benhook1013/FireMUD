plugins {
    id("io.gatling.gradle") version "3.16.1"
}

dependencies {
    implementation("io.gatling:gatling-core:3.16.1")
    implementation("io.gatling:gatling-http:3.16.1")
}

// keep simulations under src/gatling
