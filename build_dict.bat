@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Dang dung tu dien lon offline (can mang lan dau, ~45 MB)...
python\.venv\Scripts\python.exe python\build_dict.py %*
echo.
echo Xong. Khoi dong lai ung dung de nap tu dien. (Dung thu muc co san:  build_dict.bat --from-dir D:\thu_muc_vp)
pause
