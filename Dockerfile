FROM maven:3.9.9-eclipse-temurin-21 AS build-app
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM maven:3.9.9-eclipse-temurin-21 AS build-extension
WORKDIR /workspace-ext
COPY agent-extension/pom.xml .
COPY agent-extension/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ARG OTEL_JAVAAGENT_VERSION=1.28.0
RUN set -eux; \
    curl -fSL -o /app/opentelemetry-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar"

COPY --from=build-app /workspace/target/repro-health-span-filter-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=build-extension /workspace-ext/target/actuator-drop-extension-1.0.0.jar /app/actuator-drop-extension.jar

ENV JAVA_TOOL_OPTIONS=""
ENTRYPOINT ["sh", "-c", "java ${JAVA_TOOL_OPTIONS} -jar /app/app.jar"]
