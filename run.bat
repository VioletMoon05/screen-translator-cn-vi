@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist target\screen-translator-1.0.0.jar (echo Chua build. Hay chay setup.bat truoc. & exit /b 1)
java -jar target\screen-translator-1.0.0.jar
