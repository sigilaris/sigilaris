#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: scripts/hotstuff-memory-retention-sample.sh <pid> <output-dir> [--heap-dump]

Collects JVM memory evidence for HotStuff source/sink retention analysis:
  - jcmd VM.command_line
  - jcmd GC.heap_info
  - jcmd GC.class_histogram -all
  - jstat -gcutil
  - optional live heap dump for dominator/reference-path analysis

The class histogram can still pause the target JVM, but -all avoids the
pre-histogram full GC used by the default live-object histogram. Run the
optional heap dump only during an approved maintenance window.
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage
  exit 2
fi

pid="$1"
out_dir="$2"
heap_dump="${3:-}"

if [[ "$heap_dump" != "" && "$heap_dump" != "--heap-dump" ]]; then
  usage
  exit 2
fi

mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd -P)"
timestamp="$(date -u +"%Y%m%dT%H%M%SZ")"
prefix="$out_dir/hotstuff-memory-$pid-$timestamp"

jcmd "$pid" VM.command_line > "$prefix.vm-command-line.txt"
jcmd "$pid" GC.heap_info > "$prefix.gc-heap-info.txt"
jcmd "$pid" GC.class_histogram -all > "$prefix.class-histogram.txt"
jstat -gcutil "$pid" 1000 10 > "$prefix.jstat-gcutil.txt"

if [[ "$heap_dump" == "--heap-dump" ]]; then
  jcmd "$pid" GC.heap_dump "$prefix.live.hprof"
fi

cat <<EOF
Wrote:
  $prefix.vm-command-line.txt
  $prefix.gc-heap-info.txt
  $prefix.class-histogram.txt
  $prefix.jstat-gcutil.txt
EOF

if [[ "$heap_dump" == "--heap-dump" ]]; then
  echo "  $prefix.live.hprof"
fi
