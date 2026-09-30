@echo off
setlocal disabledelayedexpansion
cd /d "%~dp0"

rem Git helper for nothing-app, modeled on homeapps' save.bat (git part only, no deploy).
rem
rem   save.bat                          menu
rem   save.bat save                     add + commit, then ask before pushing
rem   save.bat commit                   add + commit, no push
rem   save.bat push                     push the current branch to its upstream
rem   save.bat pull                     git pull (current branch, from its upstream)
rem   save.bat quick "message"          add + commit + push, no menu, no prompts
rem   save.bat quick-commit "message"   add + commit, no push, no menu, no prompts
rem
rem quick / quick-commit never prompt, pause, fetch, merge or force-push. If the
rem push fails they print the error and exit 1. An empty message becomes "update".
rem Exit code: 0 on success, 1 on any failure.

rem Read the arguments before delayed expansion is on: with it on, every "!" in
rem the commit message would be lost.
set "ACTION=%~1"
set "MSG=%~2"
setlocal enabledelayedexpansion

set "PROJECT=nothing-app"
set "DEFMSG=update"
set "COMMIT_ONLY=0"
set "QUICK=0"
set "SAVE_ERROR=0"
call :resolvebranch

if /i "%ACTION%"=="quick"        (set "QUICK=1" & goto checkrepo)
if /i "%ACTION%"=="quick-commit" (set "QUICK=1" & set "COMMIT_ONLY=1" & goto checkrepo)
if /i "%ACTION%"=="save"         goto checkrepo
if /i "%ACTION%"=="commit"       (set "COMMIT_ONLY=1" & goto checkrepo)
if /i "%ACTION%"=="push"         goto pushonly
if /i "%ACTION%"=="pull"         goto pull
if not "%ACTION%"=="" goto usage

:menu
echo.
echo === %PROJECT% ===
echo.
echo   1  save      add + commit, then ask before pushing
echo   2  commit    add + commit, no push
echo   3  push      push the current branch to its upstream
echo   4  pull      git pull
echo   5  quit
echo.
set "CHOICE="
set /p CHOICE=select [1-5]:
if "%CHOICE%"=="1" goto checkrepo
if "%CHOICE%"=="2" (set "COMMIT_ONLY=1" & goto checkrepo)
if "%CHOICE%"=="3" goto pushonly
if "%CHOICE%"=="4" goto pull
if "%CHOICE%"=="5" exit /b 0
echo [err]  invalid choice
goto menu


:usage
echo [err]  unknown action "%ACTION%"
echo        use: save.bat [save^|commit^|push^|pull^|quick "message"^|quick-commit "message"]
exit /b 1


:checkrepo
if exist ".git" goto save
echo [err]  no git repository here
set "SAVE_ERROR=1"
goto end


:save
echo.
echo === git: save ===
echo.

rem An unfinished merge must never reach "git add ." - it would stage the
rem conflict markers and record the conflict as settled.
set "UNMERGED="
for /f "delims=" %%u in ('git diff --name-only --diff-filter=U 2^>nul') do set "UNMERGED=1"
if defined UNMERGED (
  echo [err]  unresolved merge conflicts - resolve these first:
  git diff --name-only --diff-filter=U
  set "SAVE_ERROR=1"
  goto end
)

echo [git]  stage
git add .
if errorlevel 1 (
  echo [err]  git add failed
  set "SAVE_ERROR=1"
  goto end
)
git diff --cached --quiet
if errorlevel 1 goto commit
echo [git]  nothing new to commit
if "%COMMIT_ONLY%"=="1" goto end
goto askpush

:commit
git status --short
rem quick modes already have their message (captured above, before delayed
rem expansion); the menu asks for one.
if "%QUICK%"=="1" goto writemsg
echo.
set "MSG="
set /p "MSG=commit message [%DEFMSG%]: "
:writemsg
if not defined MSG set "MSG=%DEFMSG%"
rem The message never goes through a command line: PowerShell writes it from
rem the environment to a UTF-8 file (no BOM) and git reads the file.
set "MSGFILE=%TEMP%\%PROJECT%-save-message.txt"
powershell -NoProfile -Command "[IO.File]::WriteAllText($env:MSGFILE, $env:MSG)"
if errorlevel 1 (
  echo [err]  could not write the commit message file
  set "SAVE_ERROR=1"
  goto end
)
git commit -F "%MSGFILE%"
if errorlevel 1 (
  del "%MSGFILE%" >nul 2>nul
  echo [err]  git commit failed
  set "SAVE_ERROR=1"
  goto end
)
del "%MSGFILE%" >nul 2>nul
call :resolvebranch
if "%COMMIT_ONLY%"=="1" (
  echo.
  echo [git]  committed locally, not pushed
  goto end
)

:askpush
if "%QUICK%"=="1" goto push
echo.
set "DOPUSH="
set /p DOPUSH=push now? (y/n):
if /i not "%DOPUSH%"=="y" (
  echo [git]  not pushed
  goto end
)
goto push


:pushonly
echo.
echo === git: push ===

rem Push the checked-out branch to its configured upstream (which may have a
rem different name, e.g. master tracking origin/main). Never fetches, merges or
rem forces; a rejected push is reported and left for the user.
:push
echo.
if "%BRANCH%"=="" (
  echo [err]  no branch checked out ^(detached HEAD?^) - not pushing
  set "SAVE_ERROR=1"
  goto end
)
set "UPSTREAM="
for /f "delims=" %%u in ('git rev-parse --abbrev-ref --symbolic-full-name "@{u}" 2^>nul') do set "UPSTREAM=%%u"
if defined UPSTREAM goto pushupstream
if "%REMOTE%"=="" (
  echo [err]  no remote configured - nothing pushed
  set "SAVE_ERROR=1"
  goto end
)
echo [git]  %BRANCH% has no upstream yet - pushing to %REMOTE%/%BRANCH% and tracking it
git push -u %REMOTE% %BRANCH%
goto pushresult
:pushupstream
echo [git]  push %BRANCH% to %UPSTREAM%
git -c push.default=upstream push
:pushresult
if not errorlevel 1 (
  echo [git]  pushed
  goto end
)
echo.
echo [err]  push failed - see git's message above. Any commit is saved locally.
echo        If the remote has commits you don't, run "save.bat pull", then push again.
set "SAVE_ERROR=1"
goto end


:pull
echo.
echo === git: pull ===
echo.
if "%BRANCH%"=="" (
  echo [err]  no branch checked out
  set "SAVE_ERROR=1"
  goto end
)
git rev-parse --abbrev-ref "@{u}" >nul 2>nul
if errorlevel 1 (
  echo [err]  %BRANCH% has no upstream to pull from
  set "SAVE_ERROR=1"
  goto end
)
git pull
if errorlevel 1 set "SAVE_ERROR=1"
goto end


rem The checked-out branch into %BRANCH% (empty with no repo or a detached HEAD),
rem and the remote to use into %REMOTE%: the branch's upstream, else origin, else
rem the first remote. Empty when there is none.
:resolvebranch
set "BRANCH="
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%b"
if /i "%BRANCH%"=="HEAD" set "BRANCH="
set "REMOTE="
if not "%BRANCH%"=="" for /f "delims=" %%r in ('git config branch.%BRANCH%.remote 2^>nul') do set "REMOTE=%%r"
if not "%REMOTE%"=="" exit /b 0
git remote get-url origin >nul 2>nul
if not errorlevel 1 (set "REMOTE=origin" & exit /b 0)
for /f "delims=" %%r in ('git remote 2^>nul') do if "!REMOTE!"=="" set "REMOTE=%%r"
exit /b 0


:end
echo.
if "%QUICK%"=="1" exit /b %SAVE_ERROR%
pause
exit /b %SAVE_ERROR%
