plugins {
    id("io.gatling.gradle") version "3.14.3"
}

dependencies {
    implementation("io.gatling:gatling-core:3.14.3")
    implementation("io.gatling:gatling-http:3.14.3")
}

// keep simulations under src/gatling
