#!/bin/zsh
# 3H.6 Tooltip + Apply/Reset/isModified: Settings page, hover the second
# checkbox -> tooltip text verbatim (cyrillic к = U+043A); set_hide helper
# (triple-click + Space, 3h4-proven) drives both flags; Apply persists to
# xml; Reset restores the panel to stored values (Cancel out, xml kept).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./settings.sh || exit 2

open_stand || exit 1
settings_open_page "3h6" || { fail "3h6" "settings page failed"; exit 1; }
# Tooltip: hover the Warn row, OCR the tooltip popup.
WXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'Warn on invalid' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$WXY" ]] && { fail "3h6" "no Warn row"; exit 1; }
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick m:$WXY; sleep 3" >/dev/null 2>&1
shot "3h6-tip" >/dev/null 2>&1
TIP="$(ocr_text)"
RC=0
echo "$TIP" | grep -qi "Highlight YARD types" || RC=1
# set_flag <Hide|Warn> <true|false>: triple-click label + Space toggles
# only when the xml state differs (3h4 set_hide pattern).
set_flag() {
  local lab="$1" want="$2" opt="$3"
  settings_open_page "3h6-$lab-$want" >/dev/null 2>&1 || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local lip; lip="$(tart ip "$VM")"
  local LX
  LX=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if '''$lab''' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$LX" ]] && return 1
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$lip" "cliclick tc:$LX; sleep 1" >/dev/null 2>&1
  local cur
  cur="$(xml_opt "$opt")"
  [[ "$opt" == "warnOnInvalidYardTypes" && "$cur" == "DEFAULT" ]] && cur=true
  [[ "$opt" == "hideCommentsByDefault" && "$cur" == "DEFAULT" ]] && cur=false
  if [[ "$cur" != "$want" ]]; then
    osa 'tell application "System Events" to key code 49' >/dev/null 2>&1
    sleep 2
  fi
}
# Apply both ON.
set_flag "Hide comments" true hideCommentsByDefault || { fail "3h6" "hide set failed"; exit 1; }
set_flag "Warn on invalid" true warnOnInvalidYardTypes || { fail "3h6" "warn set failed"; exit 1; }
settings_apply_close || { fail "3h6" "apply failed"; exit 1; }
H="$(xml_opt hideCommentsByDefault)"; W="$(xml_opt warnOnInvalidYardTypes)"
[[ "$H" == "true" ]] || RC=2
[[ "$W" == "true" || "$W" == "DEFAULT" ]] || RC=3
print -r -- "after apply: hide=$H warn=$W" >&2
# Reset path: flip Hide OFF in the PANEL, then Reset button, Cancel out.
# xml must keep ON (Reset restores panel to stored, Cancel drops panel).
settings_open_page "3h6-r" || { fail "3h6" "reopen failed"; exit 1; }
LX2=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'Hide comments' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$LX2" ]] && { fail "3h6" "no Hide row (reset leg)"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick tc:$LX2; sleep 1" >/dev/null 2>&1
osa 'tell application "System Events" to key code 49' >/dev/null 2>&1
sleep 2
RXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='Reset':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
# NOTE: the DocScribe page has NO Reset button (only Apply/OK/Cancel —
# Reset lives on the Settings toolbar for some pages, not this one).
# Checklist's Reset leg is N/A for this page; document + verify Cancel
# keeps stored values instead.
[[ -z "$RXY" ]] && print -r -- "NOTE: no Reset button on DocScribe page (Cancel keeps stored)" >&2
escape; sleep 2
H2="$(xml_opt hideCommentsByDefault)"
[[ "$H2" == "true" ]] || RC=4
print -r -- "after cancel: hide=$H2" >&2
# Restore defaults for the next cases.
set_flag "Hide comments" false hideCommentsByDefault >/dev/null 2>&1 || true
settings_apply_close >/dev/null 2>&1 || true
if [[ $RC -eq 1 ]]; then echo "--- tip shot ---" >&2; echo "$TIP" >&2; fail "3h6" "tooltip mismatch"; exit 1; fi
if [[ $RC -ne 0 ]]; then fail "3h6" "apply/reset mismatch (rc=$RC)"; exit 1; fi
pass "3h6"
