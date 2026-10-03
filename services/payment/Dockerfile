FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin payment
USER 10001
COPY --from=build /src/target/payment-service-exec.jar /app/payment-service.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/payment-service.jar"]
