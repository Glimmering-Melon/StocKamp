@echo off
echo Dang khoi dong News Service...
cd news-service
venv\Scripts\uvicorn.exe main:app --host 0.0.0.0 --port 8000
pause