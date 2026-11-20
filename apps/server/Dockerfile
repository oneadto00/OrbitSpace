FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY orbit-kernel orbit-kernel
COPY orbit-platform orbit-platform
COPY orbit-security orbit-security
COPY orbit-mod-identity orbit-mod-identity
COPY orbit-mod-space orbit-mod-space
COPY orbit-mod-chat orbit-mod-chat
COPY orbit-mod-gitproject orbit-mod-gitproject
COPY orbit-mod-friendship orbit-mod-friendship
COPY orbit-mod-file orbit-mod-file
COPY orbit-mod-integration orbit-mod-integration
COPY orbit-mod-document orbit-mod-document
COPY orbit-app orbit-app
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/orbit-app/target/orbit-app.jar /app/orbit-app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/orbit-app.jar"]
