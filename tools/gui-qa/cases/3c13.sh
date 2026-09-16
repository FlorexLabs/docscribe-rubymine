#!/bin/zsh
# 3C.13 Parse-error file.
# DRIFT (proven 2026-09-16, gem 1.6.2): the checklist fixture
#   def foo(
#     puts "x"
#   end
# parses fine (Prism recovery) -> MissingDocBlock WARNING, no error path.
# A truly unparseable file (`{{{`) yields severity fatal +
# cop Docscribe/ProcessingError (NOT Docscribe/Error as the checklist
# names it). Plugin code consequences (read, not executed):
# - isError matches only "Docscribe/Error" -> ProcessingError annotation
#   gets severity ERROR (red, correct color) but WITH a fix attached
#   (checklist says no fix) and IS cached (checklist says no cache+retry;
#   isErrorOutput only matches "Docscribe/Error").
# This case runs BOTH fixtures through the GUI log and asserts the
# checklist expectations; EXPECTED outcome is documented drift (fail).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'printf "def foo(\n  puts \\"x\\"\nend\n" > ~/qa-stand/broken.rb'
cleanup() { gssh 'rm -f ~/qa-stand/broken.rb'; reset_calc; }
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
open_tree_file "broken.rb" || { cleanup; fail "3c13" "no broken.rb in tree"; exit 1; }
sleep 25
A1="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/broken.rb' | tail -n 1)"
NOCACHE1="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'not caching error output for /Users/admin/qa-stand/broken.rb' | wc -l | tr -d ' ')"
# Fixture 2: unparseable (three open braces via octal — literal braces
# confuse zsh parsing even quoted).
gssh 'printf "\173\173\173\n" > ~/qa-stand/broken.rb; sleep 15'
M1="$(log_mark)"
rewrite_run '~/qa-stand/broken.rb'
sleep 5
A2="$(gssh "awk 'NR>$M1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/broken.rb' | tail -n 1)"
NOCACHE2="$(gssh "awk 'NR>$M1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'not caching error output for /Users/admin/qa-stand/broken.rb' | wc -l | tr -d ' ')"
# Retry probe: poke again, expect a NEW start (no cache) per checklist.
M2="$(log_mark)"
rewrite_run '~/qa-stand/broken.rb'
sleep 10
NSTART="$(gssh "awk 'NR>$M2' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'doAnnotate start file=/Users/admin/qa-stand/broken.rb' | wc -l | tr -d ' ')"
cleanup
{
  print -r -- "fixture1(checklist def foo(): apply=[${A1:-<none>}] not-caching-lines=$NOCACHE1"
  print -r -- "fixture2(triple-brace): apply=[${A2:-<none>}] not-caching-lines=$NOCACHE2 retry-starts=$NSTART"
} >&2
echo "$A1" | grep -q "offenses=" || { fail "3c13" "no pass at all for checklist fixture"; exit 1; }
# Checklist demands the ERROR path for fixture 1; gem gives a warning.
# Fail with the drift note (the mechanism itself — passes run — works).
fail "3c13" "checklist fixture parses, no error path (drift, see note)"
exit 1
