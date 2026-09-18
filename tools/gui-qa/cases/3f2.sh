#!/bin/zsh
# 3F.2 spec/ exclusion: stand + spec/undoc_spec.rb (2 undoc methods).
# Plugin must report checked 7 file(s) / 3 issues (spec file NOT counted);
# CLI twin `bundle exec docscribe --rbs --format json` reports its own
# numbers. DRIFT (proven 2026-09-17): plugin N=7 (content-root .rb/.rake
# incl. 3 clean rake files, spec excluded by default exclude:[spec]),
# CLI inspected=4 (only files with offenses + ... ), files[]=2. The
# checklist's "числа совпадают" cannot hold — CLI counts differently
# (inspected vs collected). Case asserts the PLUGIN side (exclusion works:
# collected=8 filtered=7, balloon 7/3) and records CLI numbers in the note.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh "rm -f ~/qa-stand/broken.rb 2>/dev/null; find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete"
sig_ensure
reset_calc
gssh 'printf "class Gadget\n  # Old desc.\n  def foo(a)\n    a\n  end\nend\n" > ~/qa-stand/partial.rb; sleep 5'
gssh 'mkdir -p ~/qa-stand/spec && printf "class Undoc\n  def missing_one(a)\n    a\n  end\n  def missing_two(b)\n    b\n  end\nend\n" > ~/qa-stand/spec/undoc_spec.rb; sleep 12'
cleanup() { gssh 'rm -rf ~/qa-stand/spec'; reset_calc; }
open_stand || { cleanup; exit 1; }
source ./ws.sh || exit 2

M0="$(log_mark)"
fire_workspace "3f2" || { cleanup; fail "3f2" "fire failed"; exit 1; }
B="$(ocr_text)"
RC=0
echo "$B" | grep -qi "checked 7 file(s)" || RC=1
echo "$B" | grep -qi "3 issue(s) found" || RC=2
CF="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'collectRubyFiles collected=8 filtered=7' | tail -n 1)"
[[ -n "$CF" ]] || RC=4
# CLI twin numbers for the note (never green-gated: counts differ by design).
CLI="$(gssh 'cd ~/qa-stand && bundle exec docscribe --rbs --format json . 2>/dev/null | python3 -c "import json,sys; raw=sys.stdin.read(); d=json.loads(raw[raw.index(chr(123)):]); s=d[\"summary\"]; print(str(s[\"inspected_file_count\"])+\"/\"+str(len(d[\"files\"])))"' 2>/dev/null | tail -n 1 | tr -d ' ')"
print -r -- "plugin: 7 files / 3 issues; collect 8->7; cli twin inspected/files: [$CLI]" >&2
cleanup
if [[ $RC -eq 1 ]]; then echo "--- balloon ---" >&2; echo "$B" >&2; fail "3f2" "file count is not 7 (spec leaked in?)"; exit 1; fi
if [[ $RC -eq 2 ]]; then echo "--- balloon ---" >&2; echo "$B" >&2; fail "3f2" "spec issues counted"; exit 1; fi
if [[ $RC -eq 4 ]]; then fail "3f2" "no collect 8->7 line"; exit 1; fi
pass "3f2"
