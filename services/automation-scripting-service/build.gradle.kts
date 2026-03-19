
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.service-conventions")
    id("net.firedevops.firemud.jpa-postgres-conventions")
    id("net.firedevops.firemud.redis-conventions")
    id("net.firedevops.firemud.jwt-conventions")
}

dependencies {
}
