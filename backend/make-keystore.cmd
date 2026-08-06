@echo off
REM ===========================================================================
REM  Creates the self-signed TLS certificate the backend needs to serve HTTPS.
REM
REM  Run this ONCE, from the backend/ folder:
REM      make-keystore.cmd
REM
REM  It writes src\main\resources\keystore.p12, which is gitignored — a private
REM  key is a secret and never belongs in version control, not even a throwaway
REM  development one.
REM
REM  keytool ships with the JDK. If "keytool is not recognized" appears below,
REM  set JAVA_HOME to your JDK folder first, e.g.
REM      set JAVA_HOME=C:\Program Files\Java\jdk-26.0.2
REM ===========================================================================

setlocal

if defined JAVA_HOME (
    set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
) else (
    set "KEYTOOL=keytool"
)

set "OUT=src\main\resources\keystore.p12"
set "STOREPASS=changeit"

if exist "%OUT%" (
    echo %OUT% already exists. Delete it first if you want a fresh certificate.
    exit /b 0
)

"%KEYTOOL%" -genkeypair ^
    -alias cookiedemo ^
    -keyalg RSA ^
    -keysize 2048 ^
    -storetype PKCS12 ^
    -keystore "%OUT%" ^
    -validity 365 ^
    -storepass "%STOREPASS%" ^
    -dname "CN=localhost, OU=Dev, O=CookieShookie, L=., ST=., C=IN" ^
    -ext "SAN=dns:localhost,ip:127.0.0.1"

if errorlevel 1 (
    echo.
    echo Failed to create the keystore. See the message above.
    exit /b 1
)

echo.
echo Created %OUT%  ^(password: %STOREPASS%^)
echo The backend will now start on https://localhost:8443
endlocal
