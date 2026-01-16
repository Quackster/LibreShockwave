#!/bin/bash
# Compile and serve the LibreShockwave web player

echo "Building project..."
./gradlew :runtime:compileJava --quiet
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "Starting web server at http://localhost:8080"
echo "Press Ctrl+C to stop"
echo ""

cd runtime/src/main/resources/player
python3 -m http.server 8080
