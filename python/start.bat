@echo off
chcp 65001 > nul
cd /d %~dp0
python bandsaw_monitor.py run
pause
