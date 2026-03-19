plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "firemud"

include("common-library")
project(":common-library").projectDir = File("services/common-library")

include("common-platform-core")
project(":common-platform-core").projectDir = File("services/common-platform-core")

include("common-security")
project(":common-security").projectDir = File("services/common-security")

include("common-data-runtime")
project(":common-data-runtime").projectDir = File("services/common-data-runtime")

include("common-test-support")
project(":common-test-support").projectDir = File("services/common-test-support")

include("common-saga")
project(":common-saga").projectDir = File("services/common-saga")

include("account-service")
project(":account-service").projectDir = File("services/account-service")

include("automation-scripting-service")
project(":automation-scripting-service").projectDir = File("services/automation-scripting-service")

include("entity-management-service")
project(":entity-management-service").projectDir = File("services/entity-management-service")

include("game-design-service")
project(":game-design-service").projectDir = File("services/game-design-service")

include("game-logic-service")
project(":game-logic-service").projectDir = File("services/game-logic-service")

include("game-session-service")
project(":game-session-service").projectDir = File("services/game-session-service")

include("logging-admin-service")
project(":logging-admin-service").projectDir = File("services/logging-admin-service")

include("social-groups-service")
project(":social-groups-service").projectDir = File("services/social-groups-service")

include("spring-cloud-gateway")
project(":spring-cloud-gateway").projectDir = File("services/spring-cloud-gateway")

include("tcp-proxy-service")
project(":tcp-proxy-service").projectDir = File("services/tcp-proxy-service")

include("world-management-service")
project(":world-management-service").projectDir = File("services/world-management-service")

val includeLoadTesting = providers.gradleProperty("includeLoadTesting")
    .map(String::toBooleanStrictOrNull)
    .orElse(true)
    .get()

if (includeLoadTesting) {
    include("load-testing")
    project(":load-testing").projectDir = File("dev-tools/load-testing")
}
