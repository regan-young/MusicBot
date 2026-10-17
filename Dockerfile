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
# Smart Turn v3.2 end-of-turn model for ?interact (pipecat-ai/smart-turn-v3, BSD-2),
# pinned to a revision and checksum; see smartturnmodel in reference.conf
ADD --chmod=644 --checksum=sha256:2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f \
    https://huggingface.co/pipecat-ai/smart-turn-v3/resolve/f766f81d3cfdf7737ac64aad813d91bbfd56bf93/smart-turn-v3.2-cpu.onnx \
    /opt/jmusicbot/smart-turn-v3.2-cpu.onnx
COPY --from=build /JMusicBot.jar /opt/jmusicbot/JMusicBot.jar
WORKDIR /musicbot
# jdave loads native voice-encryption libraries
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-Dnogui=true", "-jar", "/opt/jmusicbot/JMusicBot.jar"]
