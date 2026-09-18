#!/bin/zsh
# 3D.3 Collection lock flag: present -> hasCollection=true, absent -> false.
# Third leg (lock alone must not enable RBS): code assertion — shouldUseRbs
# never consults hasCollection (only explicit/sig/lock/gemfile). A live
# signal-free project is impossible (any docscribe bundle has transitive
# rbs in the lock via parser), so no GUI leg exists; framework
# hasCollection test + code read cover it.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ws.sh || exit 2

open_project_dir "qa-stand" || exit 1
gssh 'printf "dummy: true\n" > ~/qa-stand/rbs_collection.lock.yaml; ls ~/qa-stand/rbs_collection.lock.yaml'
M0="$(log_mark)"
fire_workspace "3d3-with" || { fail "3d3" "workspace fire failed"; exit 1; }
sleep 10
L="$(ws_flags "$M0" "/Users/admin/qa-stand")"
echo "$L" | grep -q "hasCollection=true" || { echo "--- with ---" >&2; echo "${L:-<none>}" >&2; fail "3d3" "lock not detected"; exit 1; }
gssh 'rm -f ~/qa-stand/rbs_collection.lock.yaml'
M1="$(log_mark)"
fire_workspace "3d3-without" || { fail "3d3" "second fire failed"; exit 1; }
sleep 10
L2="$(ws_flags "$M1" "/Users/admin/qa-stand")"
echo "$L2" | grep -q "hasCollection=false" || { echo "--- without ---" >&2; echo "${L2:-<none>}" >&2; fail "3d3" "lock removal not detected"; exit 1; }
pass "3d3"
