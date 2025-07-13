import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.http.HttpDsl.*
import io.gatling.javaapi.core.Simulation

class BasicSimulation : Simulation() {
    private val httpProtocol = http
        .baseUrl("http://localhost:8080")
        .acceptHeader("application/json")

    private val scn = scenario("Ping Load")
        .exec(http("ping").get("/ping"))

    init {
        setUp(
            scn.injectOpen(constantUsersPerSec(10.0).during(10))
        ).protocols(httpProtocol)
    }
}
