# ParkEase API image. Built by Render (see ../render.yaml) and can be built the same way anywhere:
#   docker build -t parkease-api backend
#   docker run --rm -p 8080:8080 --env-file backend/.env -e SPRING_PROFILES_ACTIVE=prod parkease-api
# Build context is the backend/ directory.

# ---- build ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first: this layer is only rebuilt when the pom or the wrapper changes, not on every source edit.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Tests need a PostgreSQL database; CI runs them, so the image build only packages.
RUN ./mvnw -B -q -DskipTests package && cp target/*.jar /workspace/app.jar

# ---- runtime -------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Unprivileged user. /app/uploads is where local-disk uploads go when CLOUDINARY_URL is not set (ephemeral on Render).
RUN groupadd --system --gid 10001 parkease \
    && useradd --system --uid 10001 --gid parkease --home-dir /app --shell /usr/sbin/nologin parkease \
    && mkdir -p /app/uploads \
    && chown -R parkease:parkease /app
COPY --from=build --chown=parkease:parkease /workspace/app.jar /app/app.jar
USER parkease

# Small instances (Render free: 512 MB): cap the heap at 75% of the container limit, one GC thread, small stacks,
# and let the platform restart the process rather than limp on after an out-of-memory error.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"
# Render injects PORT; the app reads it (server.port=${PORT:8080}).
ENV PORT=8080
EXPOSE 8080

# Needs only bash and coreutils, which the base image has. Render uses its own health check (/actuator/health).
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && timeout 4 cat <&3 | grep -q "\"status\":\"UP\""'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
