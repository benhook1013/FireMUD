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
        register("firemudSqlPostgresConventions") {
            id = "net.firedevops.firemud.sql-postgres-conventions"
            implementationClass = "net.firedevops.firemud.FiremudSqlPostgresConventionsPlugin"
        }
        register("firemudJooqConventions") {
            id = "net.firedevops.firemud.jooq-conventions"
            implementationClass = "net.firedevops.firemud.FiremudJooqConventionsPlugin"
        }
        register("firemudRedisConventions") {
            id = "net.firedevops.firemud.redis-conventions"
            implementationClass = "net.firedevops.firemud.FiremudRedisConventionsPlugin"
        }
        register("firemudStatefulServiceConventions") {
            id = "net.firedevops.firemud.stateful-service-conventions"
            implementationClass = "net.firedevops.firemud.FiremudStatefulServiceConventionsPlugin"
        }
        register("firemudSecuredStatefulServiceConventions") {
            id = "net.firedevops.firemud.secured-stateful-service-conventions"
            implementationClass = "net.firedevops.firemud.FiremudSecuredStatefulServiceConventionsPlugin"
        }
        register("firemudSecuredSqlAopServiceConventions") {
            id = "net.firedevops.firemud.secured-sql-aop-service-conventions"
            implementationClass = "net.firedevops.firemud.FiremudSecuredSqlAopServiceConventionsPlugin"
        }
        register("firemudJwtConventions") {
            id = "net.firedevops.firemud.jwt-conventions"
            implementationClass = "net.firedevops.firemud.FiremudJwtConventionsPlugin"
        }
        register("firemudOpenApiConventions") {
            id = "net.firedevops.firemud.openapi-conventions"
            implementationClass = "net.firedevops.firemud.FiremudOpenApiConventionsPlugin"
        }
        register("firemudAopConventions") {
            id = "net.firedevops.firemud.aop-conventions"
            implementationClass = "net.firedevops.firemud.FiremudAopConventionsPlugin"
        }
        register("firemudTemporalConventions") {
            id = "net.firedevops.firemud.temporal-conventions"
            implementationClass = "net.firedevops.firemud.FiremudTemporalConventionsPlugin"
        }
    }
}
