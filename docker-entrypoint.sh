#!/bin/sh

# Start Baileys sidecar in background
echo "Starting Baileys sidecar..."
cd /app/baileys-sidecar
PORT=3001 node dist/index.js &
BAILEYS_PID=$!

# Wait a bit for Baileys to initialize
sleep 3

# Start Kotlin backend
echo "Starting Kotlin backend..."
cd /app
java -jar app.jar &
KTOR_PID=$!

# Wait for both processes
wait $BAILEYS_PID
wait $KTOR_PID
