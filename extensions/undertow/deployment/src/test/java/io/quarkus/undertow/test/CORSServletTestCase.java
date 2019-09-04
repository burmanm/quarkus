package io.quarkus.undertow.test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.core.Is.is;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class CORSServletTestCase {

    @RegisterExtension
    static QuarkusUnitTest runner = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(TestServlet.class)
                    .addAsResource("cors-config.properties", "application.properties"));

    @Test
    @DisplayName("Handles a preflight CORS request correctly")
    public void corsPreflightTestServlet() {
        String origin = "http://custom.origin.quarkus";
        String methods = "GET,POST";
        String headers = "X-Custom";
        given().header("Origin", origin)
                .header("Access-Control-Request-Method", methods)
                .header("Access-Control-Request-Headers", headers)
                .when()
                .options("/test").then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", origin)
                .header("Access-Control-Allow-Methods", methods)
                .header("Access-Control-Allow-Headers", headers);
    }

    @Test
    @DisplayName("Handles a direct CORS request correctly")
    public void corsNoPreflightTestServlet() {
        String origin = "http://custom.origin.quarkus";
        String methods = "GET,POST";
        String headers = "X-Custom";
        given().header("Origin", origin)
                .header("Access-Control-Request-Method", methods)
                .header("Access-Control-Request-Headers", headers)
                .when()
                .log().headers()
                .get("/test").then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", origin)
                .header("Access-Control-Allow-Methods", methods)
                .header("Access-Control-Allow-Headers", headers)
                .body(is("test servlet"));
    }

}