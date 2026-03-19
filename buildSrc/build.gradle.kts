plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        register("firemudServiceConventions") {
            id = "net.firedevops.firemud.service-conventions"
            implementationClass = "net.firedevops.firemud.FiremudServiceConventionsPlugin"
        }
    }
}
