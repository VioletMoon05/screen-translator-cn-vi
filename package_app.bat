@echo off
chcp 65001 >nul
cd /d "%~dp0"
call mvn -q -DskipTests package || exit /b 1
call build_backend_exe.bat || exit /b 1
rem Tao thu muc ung dung (kem JRE) bang jpackage (JDK 17+)
jpackage --type app-image --name ScreenTranslatorCNVI --input target --main-jar screen-translator-1.0.0.jar ^
  --main-class vn.st.Main --dest dist --java-options "-Dfile.encoding=UTF-8"
xcopy /E /I /Y backend dist\ScreenTranslatorCNVI\backend
echo Xong: dist\ScreenTranslatorCNVI\ScreenTranslatorCNVI.exe
