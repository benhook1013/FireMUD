plugins {
    java
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    group = "net.fire-devops.firemud"
    version = "0.1.0-SNAPSHOT"

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.9.3")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
