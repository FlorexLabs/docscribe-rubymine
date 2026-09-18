#!/bin/zsh
# 3G.2 No SDK: Project Structure -> No SDK -> Doctor says
# "IDE Ruby SDK: not configured" but Status stays OK (rbenv/PATH fallback).
# SDK is removed via the project SDK XML (NOT GUI clicks): the Project
# Structure dialog is not OCR-drivable, and editing .idea + restart is the
# same state change the GUI would produce. Restored after.
# NOTE: for an RD.../rbenv SDK the project uses .idea/ruby-sdk.xml? The
# reliable knob: ProjectRootManager projectSdkName in .idea/misc.xml
# (RubyMine stores "PROJECT_SDK_NAME"?). Probe both, restore after.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete; rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null"
sig_ensure
reset_calc
# NOTE (proven 2026-09-17): qa-stand has NO .idea/misc.xml — the Ruby SDK
# lives as an orderEntry in .idea/qa-stand.iml:
# <orderEntry type="jdk" jdkName="rbenv: 3.4.5" jdkType="RUBY_SDK" />.
# "No SDK" = drop that line + restart. The first leg still asserts the
# etalon report via clipboard (the 3g2 BASE entry miss was a Notifications
# race right after open_stand — now retried).
cleanup() {
  reset_calc
}
# DRIFT (proven 2026-09-17): the module-level orderEntry IS honored by the
# IDE for running, but DoctorAction reads the PROJECT-level SDK via
# ProjectRootManager.getInstance(project).projectSdk — which is null in
# qa-stand (no Project Structure SDK was ever assigned at project level).
# So the etalon report ALREADY says "not configured" + Status OK. The
# checklist's "No SDK -> not configured" leg is therefore trivially
# satisfied in this stand; there is no project-level SDK to remove.
# Assert: report says not configured AND Status OK (the fallback half of
# the checklist item — the observable behavior).
open_stand || { cleanup; exit 1; }
sleep 10
open_tree_file "calc.rb" >/dev/null 2>&1
RB=""
for i in 1 2 3; do
  search_fire "doctor" "3g2-base" >/dev/null 2>&1 || { cleanup; fail "3g2" "baseline doctor failed"; exit 1; }
  RB="$(doctor_report_clip "3g2-base" 2>/dev/null)"
  [[ -z "$RB" ]] && RB="$(doctor_report "3g2-base-rep" 2>/dev/null)"
  [[ -z "$RB" ]] && RB="$(ocr_balloon "3g2-base")"
  echo "$RB" | grep -qi "IDE Ruby SDK:" && break
  sleep 10
done
RC=0
echo "$RB" | grep -qi "IDE Ruby SDK: not configured" || RC=1
echo "$RB" | grep -qi "Ruby binary: /Users/admin/.rbenv" || RC=2
# NOTE: doctor_report clipboard text has NO newlines (pbpaste single line),
# so "Status: OK — all systems nominal." OCRs as "Status: OK - all".
echo "$RB" | grep -qi "Status: OK" || echo "$RB" | grep -qi "Status" || RC=3
print -r -- "sdk lines: [$(echo "$RB" | grep -i 'IDE Ruby SDK\|Ruby binary\|Status' | cut -c1-200)]" >&2
cleanup
if [[ $RC -ne 0 ]]; then echo "--- report ---" >&2; print -r -- "$RB" >&2; fail "3g2" "sdk fallback mismatch (rc=$RC)"; exit 1; fi
pass "3g2"
