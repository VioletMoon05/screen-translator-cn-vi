@echo off
chcp 65001 >nul
cd /d "%~dp0"
call python\.venv\Scripts\python.exe -m pip install pyinstaller
call python\.venv\Scripts\python.exe -m PyInstaller --noconfirm --onedir --name server ^
  --collect-all rapidocr_onnxruntime --collect-all onnxruntime --collect-all deep_translator ^
  --distpath backend --workpath build\pyi --specpath build\pyi python\server.py
echo Backend dong goi tai backend\server\server.exe (Java se tu dung ban nay neu co).
