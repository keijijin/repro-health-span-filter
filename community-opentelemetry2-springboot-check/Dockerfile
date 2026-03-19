FROM maven:3.9.9-eclipse-temurin-21 AS build-app
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ARG OTEL_JAVAAGENT_VERSION=1.28.0
RUN set -eux; \
    curl -fSL -o /app/opentelemetry-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar"

COPY --from=build-app /workspace/target/community-opentelemetry2-springboot-check-0.0.1-SNAPSHOT.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS=""
ENTRYPOINT ["sh", "-c", "java ${JAVA_TOOL_OPTIONS} -jar /app/app.jar"]
