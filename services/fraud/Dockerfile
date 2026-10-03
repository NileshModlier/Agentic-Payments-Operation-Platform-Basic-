FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin fraud
USER 10001
COPY --from=build /src/target/fraud-service-exec.jar /app/fraud-service.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/fraud-service.jar"]
