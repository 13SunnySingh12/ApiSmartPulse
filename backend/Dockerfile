# Local/CI image only — never pushed to a registry.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -q -B -ntp -DskipTests package

FROM eclipse-temurin:24-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /src/target/apismartpulse-backend-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
