# =============================================================================
#  Two stages: one that has Maven and the JDK, one that ships.
# =============================================================================
#  The build stage is ~800MB of toolchain. None of it is needed to RUN a jar,
#  and every megabyte of it that reached production would be more software to
#  patch, more attack surface, and a slower cold start on a free tier that
#  spins down between visits. So the jar is copied into a clean JRE image and
#  the toolchain is thrown away.
# =============================================================================

# ---- stage 1: build ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the POM alone first, and resolve dependencies against it.
#
# This is the one Docker trick worth knowing: layers are cached by what went
# into them, so as long as pom.xml has not changed, this ~2-minute download is
# reused and a source-only change rebuilds in seconds. Copying everything at
# once would invalidate the cache on every edit to any .java file.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# Tests are skipped HERE, not skipped generally. They run in CI and on your
# machine, against a real database, before anything reaches this file. Running
# them again inside a deploy build would double the deploy time to re-prove
# something already proved, and would make a flaky test an outage.
RUN mvn -B -q clean package -DskipTests

# ---- stage 2: run -----------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# A non-root user. If anything ever escapes the JVM, it lands as somebody with
# no permissions rather than as root inside the container.
RUN useradd --system --create-home --uid 10001 spring
USER spring

COPY --from=build /build/target/*.jar app.jar

# -----------------------------------------------------------------------------
#  Defaults for a container, overridable by the platform's env vars
# -----------------------------------------------------------------------------
#  SSL off: the platform's load balancer terminates real, CA-signed HTTPS and
#  speaks plain http to this process over its internal network. The app has no
#  certificate and needs none. The browser's connection is still HTTPS, which
#  is what the Secure cookie attribute cares about.
#  The JVM flags are sized for a SMALL free instance (Koyeb's free tier is
#  512 MB), and the number is lower than it looks like it should be on purpose.
#
#  MaxRAMPercentage governs the HEAP only. A Spring Boot process also needs
#  metaspace, code cache, thread stacks and direct buffers on top of it —
#  realistically another ~150 MB here. At 75% of 512 MB the heap alone is
#  384 MB, and 384 + 150 is over the limit, so the container gets OOM-killed
#  under load rather than at startup: the worst kind of failure, because it
#  looks like a crash bug instead of a memory setting.
#
#  60% leaves roughly 300 MB of heap and comfortable room for the rest. Raise it
#  if you move to a bigger instance.
#
#  SerialGC because this instance has a fraction of a CPU. G1 runs concurrent
#  collector threads that need cores this container does not have, and it costs
#  memory to hold its region bookkeeping. The JVM would very likely pick Serial
#  on its own at this size; saying so explicitly means a change of base image
#  cannot silently flip it.
ENV SERVER_SSL_ENABLED=false \
    H2_CONSOLE_ENABLED=false \
    APP_COOKIE_SECURE=true \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"

# Documentation only — the platform injects the real port as PORT, and
# application.properties reads it.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
