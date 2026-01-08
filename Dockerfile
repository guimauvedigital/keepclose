# Multi-stage Dockerfile for KeepClose
# Stage 1: Build Kotlin backend
FROM gradle:8.5-jdk17 AS kotlin-builder

WORKDIR /app

# Copy Gradle files
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy source code
COPY src ./src

# Build the application
RUN gradle shadowJar --no-daemon

# Stage 2: Build Node.js Baileys sidecar
FROM node:20-alpine AS node-builder

WORKDIR /app

# Copy package files
COPY baileys-sidecar/package*.json ./

# Install all dependencies (including devDependencies for building)
RUN npm ci

# Copy source code
COPY baileys-sidecar/tsconfig.json ./
COPY baileys-sidecar/src ./src

# Build TypeScript
RUN npm run build

# Remove devDependencies after build
RUN npm prune --production

# Stage 3: Final runtime image
FROM eclipse-temurin:21-jre-alpine

# Install Node.js and ffmpeg
RUN apk add --no-cache nodejs npm ffmpeg

WORKDIR /app

# Copy Kotlin app from builder
COPY --from=kotlin-builder /app/build/libs/*-all.jar ./app.jar

# Copy Node.js app from builder
COPY --from=node-builder /app/node_modules ./baileys-sidecar/node_modules
COPY --from=node-builder /app/dist ./baileys-sidecar/dist
COPY --from=node-builder /app/package.json ./baileys-sidecar/

# Create data directories
RUN mkdir -p /data/auth_info /data/audio /data/audio/temp && \
    chmod -R 777 /data

# Expose ports
EXPOSE 8080 3001

# Copy startup script
COPY docker-entrypoint.sh ./
RUN chmod +x docker-entrypoint.sh

ENTRYPOINT ["./docker-entrypoint.sh"]
