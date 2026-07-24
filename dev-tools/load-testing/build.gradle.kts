plugins {
    id("io.gatling.gradle") version "3.15.1.2"
}

dependencies {
    implementation("io.gatling:gatling-core:3.15.1")
    implementation("io.gatling:gatling-http:3.15.1")
}

// keep simulations under src/gatling
