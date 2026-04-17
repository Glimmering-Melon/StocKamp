@echo off
echo Service...
cd news-service
venv\Scripts\uvicorn.exe main:app --host 0.0.0.0 --port 8000
pause