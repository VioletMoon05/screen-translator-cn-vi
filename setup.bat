@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo === 1/3 Tao moi truong Python (venv) ===
where py >nul 2>nul && (py -3.11 -m venv python\.venv || py -3 -m venv python\.venv) || python -m venv python\.venv
if errorlevel 1 (echo Khong tao duoc venv. Hay cai Python 3.11 va tick "Add to PATH". & exit /b 1)
call python\.venv\Scripts\python.exe -m pip install --upgrade pip
call python\.venv\Scripts\python.exe -m pip install -r python\requirements.txt
if errorlevel 1 (echo Cai thu vien Python that bai. & exit /b 1)
echo === 2/3 Kiem thu backend OCR ===
python\.venv\Scripts\python.exe python\selftest.py
echo === 3/3 Bien dich Java ===
call mvn -q -DskipTests package
if errorlevel 1 (echo Bien dich Java that bai. Kiem tra JDK 17+ va Maven. & exit /b 1)
echo Xong! Chay run.bat de mo ung dung.
