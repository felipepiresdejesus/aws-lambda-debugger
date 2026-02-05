#!/bin/bash
cd "$(dirname "$0")"
export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null)
echo "Using JAVA_HOME: $JAVA_HOME"
echo "Starting Gradle build..."
./gradlew build --no-daemon --stacktrace 2>&1
echo "Build completed with exit code: $?"
