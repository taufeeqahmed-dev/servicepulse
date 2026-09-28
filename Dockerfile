# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy@sha256:6adefddd4a20bceef702cedb0b03952fcd7691f9c7ccffe27014992abc0b46bd AS build
WORKDIR /build

COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties
COPY --chmod=0755 mvnw ./
COPY pom.xml ./
COPY src/ src/
# CI runs clean verify first; image packaging does not repeat the tests.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813504bf4129be2d09d7 AS runtime
WORKDIR /app

RUN groupadd --gid 10001 servicepulse \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin servicepulse \
    && mkdir -p /app/data \
    && chown servicepulse:servicepulse /app/data

COPY --from=build /build/target/*.jar /app/servicepulse.jar
ENV SPRING_DATASOURCE_URL="jdbc:h2:file:/app/data/servicepulse;DB_CLOSE_ON_EXIT=FALSE"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/servicepulse.jar"]
