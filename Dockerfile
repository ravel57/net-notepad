# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY frontend/package.json frontend/package-lock.json frontend/build.mjs frontend/
COPY frontend/src frontend/src
COPY src/main src/main
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.npm \
    ./gradlew bootJar --no-daemon \
    && java -Djarmode=tools -jar build/libs/net-notepad-*.jar extract --layers --launcher --destination build/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
