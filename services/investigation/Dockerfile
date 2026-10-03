FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin investigation
USER 10001
COPY --from=build /src/target/investigation-service-exec.jar /app/investigation-service.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/investigation-service.jar"]
