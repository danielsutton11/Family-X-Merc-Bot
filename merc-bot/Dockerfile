# ---- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests=false

# ---- run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/merc-bot.jar app.jar
# Capped heap keeps Railway RAM (and cost) down: see scope doc 10.7.
ENV JAVA_OPTS="-Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
