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

# Matches Render's default; a platform-injected PORT still wins over this.
ENV SERVER_PORT=10000
EXPOSE 10000

# Sized for a 512Mi container: heap, metaspace and thread stacks must all fit, not just the heap.
# SerialGC because G1's region bookkeeping is wasted overhead on a heap this small.
ENV JAVA_OPTS="-Xmx256m -Xms128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
