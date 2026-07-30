# syntax=docker/dockerfile:1

FROM gradle:9.0.0-jdk21 AS build

USER root

RUN mkdir -p /home/gradle/project \
    && chown -R gradle:gradle /home/gradle/project

WORKDIR /home/gradle/project

COPY --chown=gradle:gradle \
    settings.gradle.kts \
    build.gradle.kts \
    gradle.properties \
    ./

COPY --chown=gradle:gradle src ./src

USER gradle

RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 \
    gradle \
    --no-daemon \
    --console=plain \
    --project-cache-dir=/home/gradle/.gradle/project-cache \
    clean \
    installDist


FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system app \
    && useradd \
        --system \
        --gid app \
        --home-dir /app \
        app

WORKDIR /app

COPY \
    --from=build \
    --chown=app:app \
    /home/gradle/project/build/install/mail-to-telegram-bot/ \
    /app/

RUN mkdir -p /app/data/attachments \
    && chown -R app:app /app

USER app

ENTRYPOINT ["/app/bin/mail-to-telegram-bot"]