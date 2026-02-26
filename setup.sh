#!/bin/bash
# Shopping Mall - Setup Script
# This script initializes the Gradle wrapper for the project.

echo "=== Shopping Mall Project Setup ==="
echo ""

# Check Java
if ! command -v java &> /dev/null; then
    echo "[ERROR] Java is required. Please install JDK 17 or later."
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "[OK] Java $JAVA_VER detected"

# Check if Gradle is available
if command -v gradle &> /dev/null; then
    echo "[OK] Gradle found, generating wrapper..."
    gradle wrapper --gradle-version=8.12
elif [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "[OK] Gradle wrapper JAR already exists"
else
    echo "[INFO] Gradle not found. Downloading wrapper JAR..."
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.12.0/gradle/wrapper/gradle-wrapper.jar"
    mkdir -p gradle/wrapper
    if command -v curl &> /dev/null; then
        curl -sL "$WRAPPER_URL" -o gradle/wrapper/gradle-wrapper.jar
    elif command -v wget &> /dev/null; then
        wget -q "$WRAPPER_URL" -O gradle/wrapper/gradle-wrapper.jar
    else
        echo "[WARN] Cannot download automatically."
        echo "  Please install Gradle and run: gradle wrapper --gradle-version=8.12"
        echo "  Or download gradle-wrapper.jar manually."
    fi
fi

# Make gradlew executable
chmod +x gradlew 2>/dev/null

echo ""
echo "[INFO] Setup complete! Run the application with:"
echo "  ./gradlew bootRun"
echo ""
echo "[INFO] Make sure PostgreSQL is running with database 'shopping_mall_db'"
echo "  Default connection: localhost:5432/shopping_mall_db (postgres/4321)"
echo ""
