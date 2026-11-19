# ---- frontend build ----
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

# ---- backend build ----
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY --from=frontend /app/src/main/resources/static ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S busalert && adduser -S busalert -G busalert
WORKDIR /app
COPY --from=backend /app/target/ligan-bas-*.jar ./app.jar
RUN mkdir -p /app/data && chown -R busalert:busalert /app
USER busalert
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx384m", "-jar", "app.jar"]
