#!/bin/zsh
# ws.sh — workspace-check helpers (3D/3F). The only RBS log oracle is the
# collectRubyFiles line (proven 2026-09-16: RPC params and CLI flags are
# NEVER logged — buildExecuteParams/buildBatchParams/buildUpdateTypesParams
# and rpcCall have zero log statements).
#   collectRubyFiles root=... include=... exclude=... rbs=<bool>
#   hasCollection=<bool> rbsHash=...

# open_project_dir <guest-dir> — front window shows the project.
# Reuse if a window already exists; else nohup launch + poll (3b8 pattern:
# foreground CLI hangs without GUI session — NEVER foreground).
open_project_dir() {
  local dir="$1" base="${1:t}"
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  escape; sleep 2
  local WINNOW
  WINNOW="$(osa 'tell application "System Events" to tell process "rubymine" to return name of every window' 2>/dev/null)"
  if echo "$WINNOW" | grep -qi "$base"; then
    activate || return 1
    shot "ws-reused"
  else
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
      "nohup \$HOME/bin/rubymine \"\$HOME/$dir\" >/dev/null 2>&1 &" >/dev/null 2>&1
    local OPD=0 i
    for i in $(seq 1 18); do
      sleep 5
      activate >/dev/null 2>&1
      shot "ws-open-dlg"
      if ocr_text | grep -qi "Open Project"; then OPD=1; break; fi
      if ocr_text | grep -qi "$base"; then OPD=2; break; fi
    done
    [[ $OPD -eq 0 ]] && { echo "ws: no Open Project dialog for $dir" >&2; return 1; }
    if [[ $OPD -eq 1 ]]; then
      local NXY
      NXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='New Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
      [[ -z "$NXY" ]] && { echo "ws: no New Window button" >&2; return 1; }
      ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$NXY" >/dev/null 2>&1
      sleep 20
      activate || return 1
    fi
  fi
  shot "ws-trust"
  if ocr_text | grep -qi "Trust Project"; then
    click_text "$LAST_SHOT" "Trust Project" || return 1
    sleep 25
    activate || return 1
  fi
  front_window_has "$base" || { echo "ws: front window is not $base" >&2; return 1; }
}

# fire_workspace <tag> — Search Everywhere -> Check Entire Workspace.
# Pre-kills guest Terminal (respawn steals front mid-search; proven
# 2026-09-16: abort with front=Terminal).
fire_workspace() {
  gssh 'pkill -9 -x Terminal 2>/dev/null; pkill -9 -x man 2>/dev/null; pkill -9 -x less 2>/dev/null' >/dev/null 2>&1
  sleep 2
  search_type "Check Entire Workspace" || return 1
  sleep 2
  shot "$1-palette"
  ocr_text | grep -qi "Check Entire Workspace" || { echo "ws: no action row" >&2; return 1; }
  search_enter
  shot "$1"
}

# ws_flags <mark> <root-substr> — last collectRubyFiles line for root.
ws_flags() {
  local mark="$1" root="$2"
  gssh "awk 'NR>$mark' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'collectRubyFiles' | grep -F "$root" | tail -n 1
}
