FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin settlement
USER 10001
COPY --from=build /src/target/settlement-service-exec.jar /app/settlement-service.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/settlement-service.jar"]
