#!/bin/zsh
# settings.sh — JetBrains Settings (Tools > DocScribe) helpers.
# Laws (proven 2026-09-15/16):
# - NEVER type into the Settings search box: focus/echo states are
#   unpredictable (speed-search glue "DocScribeDocScribe", stale filters).
#   Navigate the TREE by clicks only.
# - Checkbox square coords are DYNAMIC (dialog moves with IDE window):
#   CX = label_x/2 - 23 (@2x->logical minus square offset).
# - xml truth table: option ABSENT (or file missing) == DEFAULT. Code
#   default warnOnInvalidYardTypes=true, hideCommentsByDefault=false.
#   IntelliJ skips default-valued options on serialize, so Apply(true)
#   may DELETE the option line instead of writing value="true".
# - Always Escape-close the dialog (leftover window breaks next Cmd+,
#   search filter, and open_stand tree shots).

# xml_opt <name> — prints true|false|DEFAULT for a DocScribe setting.
# NOTE: the file is options/docscribe-settings.xml (NOT the config root —
# corrected 2026-09-18: it appears only after the first non-default Apply;
# before that every read is DEFAULT, which is also correct).
# NOTE: NEVER parse with `tr -d 'value="'` — tr strips CHARACTERS, so
# "false" becomes "fs" and "true" becomes "tr" (proven 2026-09-16: every
# settings_want verification failed while clicks+Apply actually worked).
xml_opt() {
  local X V
  X="$(gssh 'cat ~/Library/Application\ Support/JetBrains/RubyMine2026.2/options/docscribe-settings.xml 2>/dev/null')"
  if [[ -z "$X" ]]; then echo DEFAULT; return 0; fi
  V="$(print -r -- "$X" | sed -n "s/.*name=\"$1\" value=\"\([a-z]*\)\".*/\1/p")"
  if [[ -z "$V" ]]; then echo DEFAULT; else echo "$V"; fi
}

# settings_open_page <tag> — leave with DocScribe page visible (Warn row).
# STRATEGY (proven 2026-09-16): the dialog reopens with a STALE filter AND
# stale selection; typing anything makes it worse. NEVER type: go straight
# to the tree — Tools node (expand if collapsed) -> DocScribe child.
# Fresh-boot dialog (empty filter, search focus) also ends here correctly:
# tree nodes are always visible regardless of the filter.
settings_open_page() {
  activate || return 1
  front_is_rubymine || { echo "settings: not front" >&2; return 1; }
  osa 'tell application "System Events" to keystroke "," using {command down}' >/dev/null 2>&1
  sleep 5
  shot "$1-page"
  ocr_text | grep -qi "Warn on invalid YARD" && return 0
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  _tree_click_docs() { # $1=shot-tag — single click DocScribe node, check page
    local TN
    TN=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if o['text'].strip()=='DocScribe' and o['x'] < 600 and o['y'] > 600]
rows.sort(key=lambda o: o['y'])
if rows:
    o = rows[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
    [[ -z "$TN" ]] && return 1
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$TN; sleep 2" >/dev/null 2>&1
    shot "$1"
    ocr_text | grep -qi "Warn on invalid YARD"
  }
  _tree_click_docs "$1-tree" && return 0
  _tools_expand "$1"
}

# _tools_expand <tag> — clear filter X, expand Tools, open DocScribe child.
# Factored out: the flow after a fresh Tree miss.
_tools_expand() {
  local XX
  XX=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='X' and o['y'] < 600:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -n "$XX" ]] && ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$XX; sleep 2" >/dev/null 2>&1
  shot "$1-cleared"
  ocr_text | grep -qi "Warn on invalid YARD" && return 0
  # Dialog gone? (X click hit the project tree behind a closed dialog.)
  # Reopen and skip straight to the tree click.
  if ! ocr_text | grep -qi "Settings"; then
    osa 'tell application "System Events" to keystroke "," using {command down}' >/dev/null 2>&1
    sleep 5
    shot "$1-reopened"
  fi
  local TL
  TL=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
cands = [o for o in d if 'Tools' in o['text'] and o['x'] < 600 and o['y'] > 400 and len(o['text']) < 20]
cands.sort(key=lambda o: o['x'])
if cands:
    o = cands[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
else:
    for o in d:
        if 'Tools' in o['text'] and o['x'] < 600 and o['y'] > 400 and len(o['text']) < 20:
            print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
            break
")
  [[ -z "$TL" ]] && { echo "settings: no Tools row" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$TL; sleep 2" >/dev/null 2>&1
  shot "$1-expanded"
  ocr_text | grep -qi "Warn on invalid YARD" && return 0
  _tree_click_docs "$1-tree2" && return 0
  # Single click may only SELECT without opening (already-selected row =
  # no-op). Retry: double-click the node.
  local TN3
  TN3=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if o['text'].strip()=='DocScribe' and o['x'] < 600 and o['y'] > 600]
rows.sort(key=lambda o: o['y'])
if rows:
    o = rows[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
  if [[ -n "$TN3" ]]; then
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$TN3; sleep 2" >/dev/null 2>&1
    shot "$1-tree-dc"
    ocr_text | grep -qi "Warn on invalid YARD" && return 0
  fi
  { echo "settings: page not visible" >&2; return 1; }
}

# settings_checkbox_square <label-substr> <tag> — click the checkbox square
# left of the label row. Page must already be visible. No Apply.
# GEOMETRY (measured 2026-09-18): label OCR x=700@2x => logical 350.
# Clicks at x<=290 go to the TREE (page jumps to Appearance/Keymap —
# proven: label disappeared after x=290..240 sweep); x=300/310 stay on
# the page but hit label TEXT (no-op). The 16px checkbox center is at
# logical x≈310... but WHICH of 300/310 is the square is unresolvable
# via OCR (checkbox glyph has no text row). Strategy: click x=310, then
# VERIFY the click LANDED on the page (label still visible); if the page
# jumped, Escape out and fail loudly instead of clicking blind.
settings_checkbox_square() {
  local lab="$1" tag="$2"
  shot "$tag-before"
  local LY
  LY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if '''$lab''' in o['text']]
if rows:
    o = rows[0]
    print(int((o['y']+o['h']/2)/2))
")
  [[ -z "$LY" ]] && { echo "settings: no row $lab" >&2; return 1; }
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:310,$LY; sleep 2" >/dev/null 2>&1
  shot "$tag-toggled"
  # Landed? The label row must STILL be visible (else we hit the tree).
  ocr_text | grep -qi "$lab" || { echo "settings: click missed page (tree jump?)" >&2; return 1; }
}

# settings_apply_close — click Apply (dynamic coords), Escape-close dialog,
# VERIFY closure via window list (a slow Apply eats the first Escape;
# proven 2026-09-16: dialog survived into open_tree_file, tree shot showed
# the Settings tree instead of the project tree).
settings_apply_close() {
  local AXY
  AXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='Apply':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$AXY" ]] && { echo "settings: no Apply button" >&2; return 1; }
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$AXY; sleep 5" >/dev/null 2>&1
  local i W
  for i in 1 2 3; do
    escape
    sleep 2
    W=$(osa 'tell application "System Events" to tell process "rubymine" to return name of every window' 2>/dev/null)
    echo "$W" | grep -qi "Settings" || return 0
  done
  echo "settings: dialog won't close" >&2; return 1
}

# settings_want <true|false> <tag> — drive warnOnInvalidYardTypes to want,
# verifying via xml (DEFAULT==true). Max 3 open/click/apply rounds.
# settings_want <true|false> <tag> — drive warnOnInvalidYardTypes to want,
# verifying via xml (DEFAULT==true). Max 3 open/click/apply rounds.
# TOGGLE PATH (proven 2026-09-18 for Hide; same dialog): triple-click the
# LABEL (focuses the checkbox) + Space toggles. The old
# settings_checkbox_square (fixed x=310 click) hits label text = no-op.
settings_want() {
  local want="$1" tag="$2" i V
  for i in 1 2 3; do
    V="$(xml_opt warnOnInvalidYardTypes)"
    [[ "$V" == "DEFAULT" ]] && V=true
    [[ "$V" == "$want" ]] && return 0
    settings_open_page "$tag-$i" || return 1
    unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
    local ip; ip="$(tart ip "$VM")"
    local LX
    LX=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'Warn on invalid' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
    [[ -z "$LX" ]] && return 1
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick tc:$LX; sleep 1" >/dev/null 2>&1
    osa 'tell application "System Events" to key code 49' >/dev/null 2>&1
    sleep 2
    settings_apply_close || return 1
  done
  V="$(xml_opt warnOnInvalidYardTypes)"
  [[ "$V" == "DEFAULT" ]] && V=true
  [[ "$V" == "$want" ]]
}
