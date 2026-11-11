#!/bin/bash
# Usage: claude-tab-title.sh busy|idle — Claude Code UserPromptSubmit (busy) / Stop (idle) hook: sets the terminal tab title to ⏳|✓ <branch>
# Claude Code's own title stays the same while working → set CLAUDE_CODE_DISABLE_TERMINAL_TITLE=1 and write it here
cat >/dev/null
b=$(git branch --show-current 2>/dev/null); b=${b:-$(basename "$PWD")}
[ "$1" = busy ] && t="⏳ $b" || t="✓ $b"
if [ -n "$TMUX_PANE" ]; then
  # tmux forwards the pane title to the outer terminal with set-titles on, set-titles-string "#T"
  tmux select-pane -t "$TMUX_PANE" -T "$t" 2>/dev/null
else
  printf '\e]0;%s\a' "$t" > /dev/tty 2>/dev/null
fi
exit 0
