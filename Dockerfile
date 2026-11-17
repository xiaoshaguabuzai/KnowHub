# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :server:installDist

# ---------- runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/server/build/install/server /app
RUN useradd -r -u 10001 knowhub \
    && mkdir -p /data \
    && chown -R knowhub:knowhub /app /data

ENV KNOWHUB_HOST=0.0.0.0 \
    KNOWHUB_DATA_DIR=/data

USER knowhub
EXPOSE 8080
VOLUME ["/data"]

CMD ["bin/server"]
