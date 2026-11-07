# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system animequiz \
    && useradd --system --gid animequiz --home-dir /app --shell /usr/sbin/nologin animequiz \
    && mkdir -p /app/logs /app/storage \
    && chown -R animequiz:animequiz /app

COPY --chown=animequiz:animequiz --from=build /workspace/target/AnimeQuiz-*.jar /app/app.jar

USER animequiz
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
