@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\DunkinPOS.java || pause
java -Dfile.encoding=UTF-8 -cp out DunkinPOS
