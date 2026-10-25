# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build
ARG MODULE=orders-service
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common/ common/
COPY orders-service/ orders-service/
COPY worker-service/ worker-service/
COPY rabbit-admin-service/ rabbit-admin-service/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -pl "$MODULE" -am clean verify && cp "$MODULE/target/$MODULE-0.0.1-SNAPSHOT.jar" /build/app.jar

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /build/app.jar ./app.jar
USER app
EXPOSE 8080 8081 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
