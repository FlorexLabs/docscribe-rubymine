#!/bin/zsh
# Fixture writer with IDE-visible change guarantee.
# reset_calc (in menu.sh) stays as-is (2-undoc base fixture).
# write_calc <tag> — write stdin to guest calc.rb + sleep 12 so the
# annotator's next pass sees a NEW modificationStamp (fast rewrites of
# similar bytes can reuse stamps and report stale content — proven
# 2026-09-15: 3c5 show-fixture invisible until unrelated later pass).
write_calc() {
  # NOTE: gssh runs `ssh host "$@"` — "$@" becomes ONE remote command line,
  # so stdin redirection works, but ONLY if gssh is the direct consumer.
  # `print ... | write_calc` puts print's stdout into write_calc's stdin,
  # which gssh forwards to ssh -> remote cat. Do NOT wrap: any intermediate
  # pipe stage breaks the chain (proven 2026-09-15: fixture silently empty).
  gssh "cat > ~/qa-stand/calc.rb; sleep 12"
}
