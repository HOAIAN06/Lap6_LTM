@echo off
setlocal
pushd "%~dp0"
if not exist "target\server-client-chat-app.jar" (
    call mvnw.cmd -DskipTests package
    if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -jar "target\server-client-chat-app.jar" client
) else (
    java -jar "target\server-client-chat-app.jar" client
)
popd
