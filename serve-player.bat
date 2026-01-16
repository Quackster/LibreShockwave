@echo off
REM Compile and serve the LibreShockwave web player

echo Building project...
call gradlew.bat :runtime:compileJava --quiet
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b 1
)

echo.
echo Starting web server at http://localhost:8080
echo Press Ctrl+C to stop
echo.

cd runtime\src\main\resources\player
python -m http.server 8080
