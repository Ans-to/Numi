#!/usr/bin/env bash
# Surface build diagnostics in Checks API annotations (also available when log CDN access is blocked).
set -uo pipefail
log=$(mktemp)
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  python3 - "$log" <<'PY'
import sys
text = open(sys.argv[1]).read()
# Kotlin/compiler diagnostics often precede the stack trace.
lines = text.splitlines()
errors = [line for line in lines if any(x in line for x in ['e: ', 'error:', 'Error:', 'What went wrong', 'Could not', 'failed', 'Exception', 'FAILURE'])]
summary = '\n'.join(errors)[:20000] or text[-12000:]
print('::error::' + summary.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
PY
fi
rm -f "$log"
exit "$status"
