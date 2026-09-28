# ServicePulse

A small Java 21 and Spring Boot portfolio project, ready to grow into a service
monitoring application. This starter has one application endpoint:

```http
GET /health
```

It returns HTTP `200 OK`, content type `text/plain`, and the exact body `UP`.
This is a fixed response confirming that the app can serve a request; it does
not check any external service or report database health.

## Requirements

- **JDK 21**. Set your IDE's project SDK and Maven runner JDK to 21. For terminal
  use, point `JAVA_HOME` at your JDK 21 folder and put its `bin` folder on `PATH`.
- Internet access for the first build, which downloads Maven and dependencies.
- Maven is supplied by the checked-in wrapper; a separate installation is optional.

Check the Java version with `java -version`. The build also reports its Java
installation when you run `.\mvnw.cmd -version` on Windows or `sh mvnw -version`
on macOS/Linux.

## Open in your IDE

1. Extract the ZIP if needed, then open the `servicepulse` folder or its `pom.xml`.
2. Import it as a Maven project and let the dependencies finish downloading.
3. Select JDK 21 for both the project and Maven runner.
4. Run `com.example.servicepulse.ServicePulseApplication`.

IntelliJ IDEA, Eclipse, and VS Code with Java support can import this Maven project.
The commands below should be run from the folder containing `pom.xml`.

## Run and test

**Windows PowerShell:**

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

**macOS/Linux:**

```sh
sh mvnw test
sh mvnw spring-boot:run
```

Once the application starts, open <http://localhost:8080/health> in a browser.
To see the HTTP status and response headers from a second terminal:

```powershell
curl.exe -i http://localhost:8080/health
```

Use `curl -i http://localhost:8080/health` on macOS/Linux. Stop the application
with `Ctrl+C`.

If port 8080 is in use, run
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"`
on Windows, or `sh mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`
on macOS/Linux, then visit <http://localhost:8081/health>.

The single test starts the Spring application context, including JPA and H2,
and uses MockMvc to check the endpoint's status, content type, and exact body.
MockMvc exercises Spring's request handling without opening a server port.

## Build an executable JAR

```powershell
.\mvnw.cmd clean verify
java -jar target/servicepulse-0.0.1-SNAPSHOT.jar
```

On macOS/Linux, replace `.\mvnw.cmd` with `sh mvnw`. `clean verify` runs the test
and builds the executable JAR. Generated files live in the ignored `target/` folder.

## Files and packages

```text
servicepulse/
|-- .gitattributes
|-- .gitignore
|-- .mvn/wrapper/maven-wrapper.properties
|-- mvnw
|-- mvnw.cmd
|-- pom.xml
|-- README.md
`-- src/
    |-- main/
    |   |-- java/com/example/servicepulse/
    |   |   |-- ServicePulseApplication.java
    |   |   `-- health/HealthController.java
    |   `-- resources/application.properties
    `-- test/java/com/example/servicepulse/health/HealthControllerTest.java
```

| File | Purpose |
| --- | --- |
| `pom.xml` | Pins Spring Boot 4.1.1, targets Java 21, declares dependencies, and configures executable JAR packaging. |
| `ServicePulseApplication.java` | Starts Spring Boot. Its root package lets Spring discover components in subpackages. |
| `health/HealthController.java` | Maps `GET /health` to the plain text response `UP`. |
| `HealthControllerTest.java` | Verifies the endpoint through Spring's request handling with JUnit and MockMvc. |
| `application.properties` | Names the app and configures an in-memory H2 database. Schema generation and open-in-view are disabled. |
| `mvnw`, `mvnw.cmd` | Official Maven wrapper scripts for Unix-like systems and Windows. |
| `.mvn/wrapper/maven-wrapper.properties` | Pins the Maven version downloaded by the wrapper. |
| `.gitignore` | Excludes build output, IDE settings, logs, local database files, and environment files. |
| `.gitattributes` | Keeps appropriate line endings for the wrapper scripts. |
| `README.md` | Setup, usage, and an explanation of the starter. |

Code is grouped by feature under `com.example.servicepulse`, starting with
`health`. Add future feature packages alongside it when needed.

## Dependencies

Spring Boot manages compatible dependency versions through its Maven parent.

| Dependency | Why it is here |
| --- | --- |
| `spring-boot-starter-webmvc` | Spring Web MVC and embedded Tomcat for serving HTTP requests. This is the Spring MVC web starter for Spring Boot 4. |
| `spring-boot-starter-data-jpa` | Spring Data repositories and Hibernate for future persistence work. No entities or repositories are implemented yet. |
| `h2` (runtime) | An in-memory development database requiring no separate database installation. |
| `spring-boot-starter-webmvc-test` (test) | Spring MVC testing support and Spring Boot's test starter, including JUnit Jupiter and MockMvc. |

H2 uses the local development username `sa` with an empty password. Its contents
disappear when the process stops. This starter has no H2 browser console, tables,
or saved monitoring data.

## Scope and next step

This first milestone deliberately ends at a running application and a tested
`/health` endpoint. It does not implement service registration, outgoing HTTP
checks, history, a scheduler, retries, alerts, a dashboard, Docker, or CI.

After running the test and reading the controller, define the fields and API
contract for registering one service before adding the next feature.

## Official references

- [Spring Boot documentation](https://docs.spring.io/spring-boot/)
- [Spring Boot testing](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Maven wrapper](https://maven.apache.org/tools/wrapper/)
- [Eclipse Temurin JDK downloads](https://adoptium.net/temurin/releases/?version=21)
