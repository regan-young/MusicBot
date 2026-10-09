# syntax=docker/dockerfile:1

# Build: compile and run the tests, producing the shaded jar.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode package && cp target/*-All.jar /JMusicBot.jar

# Run: config.txt, serversettings.json and Playlists/ live in the /musicbot
# volume, and the bot resolves them relative to its working directory.
FROM eclipse-temurin:25-jre
COPY --from=build /JMusicBot.jar /opt/jmusicbot/JMusicBot.jar
WORKDIR /musicbot
# jdave loads native voice-encryption libraries
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-Dnogui=true", "-jar", "/opt/jmusicbot/JMusicBot.jar"]
