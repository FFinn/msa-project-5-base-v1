FROM maven:3.9.11-eclipse-temurin-11 AS build

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:11-jre

WORKDIR /app
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

COPY --from=build /workspace/target/task4-spring-batch-poc.jar /app/app.jar
RUN chown -R 10001:10001 /app

USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
