#!/bin/zsh
# 3C.6 No Update Types for untyped options: RBS-проект, cfg.rb с методом
# setup(options) без доков -> лампочка предлагает только прямой YARD-фикс,
# пункта про обновление типов НЕТ.
# Oracle: лог offenses>=1 + popup OCR (есть Apply safe fix, нет Update types).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'cat > ~/qa-stand/cfg.rb <<EOF
class Cfg
  def setup(options)
    @o = options
  end
end
EOF'
cleanup() { gssh 'rm -f ~/qa-stand/cfg.rb'; }
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
open_tree_file "cfg.rb" || { cleanup; fail "3c6" "no cfg.rb in tree"; exit 1; }
# Gate: annotator pass on cfg.rb with >=1 offense.
hit_cfg() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/cfg.rb' | grep -q 'offenses=[1-9]'
}
for i in $(seq 1 12); do
  sleep 5
  if hit_cfg; then break; fi
done
hit_cfg || { cleanup; fail "3c6" "no cfg.rb offenses pass"; exit 1; }
open_intention "3c6" "setup" || { cleanup; fail "3c6" "no intention popup"; exit 1; }
IT="$(ocr_text)"
echo "$IT" | grep -qi "Apply safe fix" || { echo "--- popup ---" >&2; echo "$IT" >&2; cleanup; fail "3c6" "no safe-fix row"; exit 1; }
echo "$IT" | grep -qi "Update types" && { echo "--- popup ---" >&2; echo "$IT" >&2; cleanup; fail "3c6" "Update-types row present"; exit 1; }
cleanup
pass "3c6"
