FROM gradle:8.14.3-jdk21 AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src src

RUN chmod +x gradlew
RUN ./gradlew --no-daemon quarkusBuild

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/build/quarkus-app/ /app/

EXPOSE 8080

ENV QUARKUS_HTTP_HOST=0.0.0.0

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
