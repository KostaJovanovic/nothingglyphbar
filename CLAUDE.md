# nothing-app

Glyph Bar app for the Nothing Phone (4a). See `README.md` for the hardware and
SDK details; `build.cmd` / `build.ps1` build the APK.

## Committing

`save.bat` is the only way to commit. **Never run `git commit` or `git push`
directly.**

- `save.bat quick "message"` stages everything, commits, and pushes the current
  branch to its upstream, with no menu or prompts. `save.bat quick-commit
  "message"` does the same without pushing.
- From Git Bash: `./save.bat quick "message"` (not `cmd //c "save.bat quick \"message\""`, which mangles the quotes into the message); from PowerShell: `.\save.bat quick "message"`.
- Both exit non-zero on failure. They never fetch, merge, or force-push; if a
  push is rejected, stop and tell the user instead of working around it.
- Plain `save.bat` (menu) and `save.bat save|commit|push|pull` are the user's
  interactive modes - they prompt and pause, so don't run them from a terminal.
- The local branch is `master`, tracking `origin/main`; `save.bat` pushes to
  that configured upstream, so don't push by branch name.
- Never add `Co-Authored-By`, "Generated with Claude Code", or any other
  Claude/AI attribution to commit messages or PR descriptions.
