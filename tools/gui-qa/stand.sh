#!/bin/zsh
# Shared: open qa-stand as the front project window (foreground CLI, This
# Window dialog, Trust), open calc.rb from the tree, assert front window.
# Leaves: qa-stand front, calc.rb open with cursor in editor.
# Sourced by cases (NOT executed): `source ../stand.sh` after lib.sh+menu.sh.
# open_stand — front window shows qa-stand with calc.rb open.
# LAUNCH RULE (proven 2026-09-15): foreground `rubymine` CLI from ssh dies
# headless ("Unable to detect graphics environment" -> IDE SHUTDOWN);
# relaunch ONLY via `nohup open -a RubyMine &` (GUI session). CLI is fine
# for opening files into a LIVE IDE, never for (re)starting it.
open_stand() {
# Reuse the live window when it already shows qa-stand (foreground CLI
# spawns "Open Project" dialogs otherwise; proven 2026-09-15: 3b2 left a
# qa-nogem2 window front and open_stand opened a THIRD window).
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
local cur ip
ip="$(tart ip "$VM")"
cur=$(osa 'tell application "System Events" to tell process "rubymine" to return name of window 1' 2>/dev/null)
if echo "$cur" | grep -qi "qa-stand"; then
  activate || return 1
else
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=60 admin@"$ip" \
  '$HOME/bin/rubymine "$HOME/qa-stand"' >/dev/null 2>&1
sleep 5
activate
shot "stand-open-dlg"
if ocr_text | grep -qi "Open Project"; then
  XY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='This Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -n "$XY" ]] && { ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$XY" >/dev/null 2>&1; sleep 20; activate; }
fi
shot "stand-trust"
if ocr_text | grep -qi "Trust Project"; then
  click_text "$LAST_SHOT" "Trust Project" || exit 1
  sleep 25
  activate
fi
front_window_has "qa-stand" || { fail "stand" "front window is not qa-stand"; exit 1; }
fi # end fresh-open branch (reuse path skips straight to tree)

# open calc.rb from tree (cursor must be in editor, not tree).
# Preconditions: NO modal dialog may be open — a leftover Settings window
# covers the IDE and its tree OCR matches the dialog's own rows (proven
# 2026-09-15: stand-tree showed the DocScribe settings page, no calc.rb).
# Close stray dialogs first (Escape x2 is harmless when none is open).
escape
escape
shot "stand-tree"
TXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if 'calc.rb' in o['text'] and o['x'] < 600]
rows.sort(key=lambda o: o['y'])
if rows:
    o = rows[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
[[ -z "$TXY" ]] && { fail "stand" "no calc.rb in tree"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$TXY" >/dev/null 2>&1
sleep 8
activate
# Verify calc.rb is the ACTIVE editor (stray tabs from attached-tab mode
# linger: qa-nogem/foo.rb etc. Double-click focuses calc.rb, but assert it:
# breadcrumb `calc.rb >` + method body text. NOT `def add` — it scrolls off
# the viewport when the file is long (proven 2026-09-15: only `sub` visible).
shot "stand-editor"
ET="$(ocr_text)"
echo "$ET" | grep -q "calc.rb" || { fail "stand" "editor is not calc.rb"; exit 1; }
# Body proof must accept ANY calc.rb content (add/sub/mul/show/x.to_s):
# cases rewrite calc.rb freely (3c4 uses show, 3b3 mul, base add/sub).
echo "$ET" | grep -qE "a [-+*] b|def sub|def mul|def show|x\.to_s" || { fail "stand" "editor has no calc body"; exit 1; }
echo "stand ok qa-stand/calc.rb"
}
