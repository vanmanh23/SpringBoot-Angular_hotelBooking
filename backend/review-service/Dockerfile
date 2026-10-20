# Build context: backend/ (see docker-compose.yml)
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY common-lib common-lib
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY hotel-service/pom.xml hotel-service/pom.xml
COPY booking-service/pom.xml booking-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY review-service/pom.xml review-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY coverage-report/pom.xml coverage-report/pom.xml
COPY review-service/src review-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl review-service -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app && apk add --no-cache wget
USER app
WORKDIR /app
COPY --from=build /src/review-service/target/review-service-*.jar app.jar
EXPOSE 8085
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8085/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
