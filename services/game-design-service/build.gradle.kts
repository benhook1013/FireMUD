
import org.gradle.api.attributes.LibraryElements

apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-sql-aop-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
    id("net.firedevops.firemud.temporal-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.gamedesign.jooq")
}

dependencies {
    compileOnly(libs.spotbugs.annotations)
    implementation(libs.aws.sdk.s3)
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(project(":world-management-service")) {
        attributes {
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class.java, LibraryElements.CLASSES),
            )
        }
    }
}
