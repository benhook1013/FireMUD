plugins {
    id("io.gatling.gradle") version "3.14.9.8"
}

dependencies {
    implementation("io.gatling:gatling-core:3.14.9")
    implementation("io.gatling:gatling-http:3.14.9")
}

// keep simulations under src/gatling
