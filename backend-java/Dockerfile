# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# application.properties в git не хранится — берём пример, значения переопределяются переменными окружения
RUN cp src/main/resources/application.properties.example src/main/resources/application.properties \
 && mvn -q -B package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*
# WORKDIR /app/backend => "../storage/" (путь загрузки в коде) = /app/storage (общий volume с воркером)
WORKDIR /app/backend
COPY --from=build /build/target/*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
