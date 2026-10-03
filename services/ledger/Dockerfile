FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin ledger
USER 10001
COPY --from=build /src/target/ledger-service-exec.jar /app/ledger-service.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/ledger-service.jar"]
