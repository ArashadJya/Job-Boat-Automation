@echo off
setlocal
cd /d "%~dp0"
echo Building ResumeJobHunter (first run may take a minute)...
call mvn -q -DskipTests package
if errorlevel 1 ( echo Build failed. & pause & exit /b 1 )
echo Running Job Hunter...
java -jar target\ResumeJobHunter-4.0-jar-with-dependencies.jar %*
pause
