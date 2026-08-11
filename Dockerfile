# Multi-stage: the JDK and the Maven cache never reach the runtime image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not re-download them.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI against real MySQL and Redis, which no image build has.
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd -r -u 1001 -m spring
COPY --from=build /build/target/*.jar /app/app.jar
USER spring

# Overridden by the platform when it injects its own port; the app reads SERVER_PORT.
ENV SERVER_PORT=8080
EXPOSE 8080

# Percentage, not a fixed heap: the container memory limit is the only number known here.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar"]
