@echo off
echo Starting backend on http://localhost:8000 ...
echo Open the dashboard at: http://localhost:8000/dashboard
echo.
cd backend
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
