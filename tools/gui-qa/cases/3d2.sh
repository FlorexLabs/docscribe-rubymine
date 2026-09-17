#!/bin/zsh
# 3D.2 Explicit rbs.enabled:false wins over sig files.
# Oracle: collectRubyFiles log line rbs=false (then restored rbs=true).
# Parser tolerance (quotes/case) = framework (parseRbsEnabled tests).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ws.sh || exit 2

gssh 'cp ~/qa-stand/docscribe.yml ~/qa-stand/docscribe.yml.bak && printf "rbs:\n  enabled: false\n" > ~/qa-stand/docscribe.yml && cat ~/qa-stand/docscribe.yml'
cleanup() { gssh 'mv ~/qa-stand/docscribe.yml.bak ~/qa-stand/docscribe.yml 2>/dev/null; cd ~/qa-stand && bundle install --quiet 2>/dev/null'; reset_calc; }
open_project_dir "qa-stand" || { cleanup; exit 1; }
# Terminal respawns steal front mid-search (proven 2026-09-16: second fire
# aborted with front=Terminal). Pre-kill before each fire.
kill_terminal >/dev/null 2>&1
sleep 2
M0="$(log_mark)"
fire_workspace "3d2-off" || { cleanup; fail "3d2" "workspace fire failed"; exit 1; }
sleep 10
L="$(ws_flags "$M0" "/Users/admin/qa-stand")"
echo "$L" | grep -q "rbs=false" || { echo "--- collectRubyFiles ---" >&2; echo "${L:-<none>}" >&2; cleanup; fail "3d2" "explicit false ignored"; exit 1; }
cleanup
# Sanity: restored yml reports rbs=true again.
open_project_dir "qa-stand" || exit 1
kill_terminal >/dev/null 2>&1
sleep 2
M1="$(log_mark)"
fire_workspace "3d2-on" || { fail "3d2" "second fire failed"; exit 1; }
sleep 10
L2="$(ws_flags "$M1" "/Users/admin/qa-stand")"
echo "$L2" | grep -q "rbs=true" || { echo "--- collectRubyFiles ---" >&2; echo "${L2:-<none>}" >&2; fail "3d2" "restore did not re-enable"; exit 1; }
pass "3d2"
