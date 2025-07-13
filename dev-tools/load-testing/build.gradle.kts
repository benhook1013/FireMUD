plugins {
    id("io.gatling.gradle") version "3.10.5"
}

dependencies {
    implementation("io.gatling:gatling-core:3.10.5")
    implementation("io.gatling:gatling-http:3.10.5")
}

// keep simulations under src/gatling
