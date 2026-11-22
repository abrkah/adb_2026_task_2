#!/bin/bash
# Effect of every single optimization (8 threads, continuous stream).
# a) baseline + one switch ON     b) optimized + one switch OFF
# Usage: scripts/ablation.sh [repeats] [threads] [seconds]
cd "$(dirname "$0")/.." || exit 1
R=${1:-3}; T=${2:-8}; D=${3:-10}
CSV=results/ablation.csv
CP=out:lib/postgresql-42.2.18.jar
javac -d out src/*.java || exit 1
mkdir -p results/logs

run() {   # label, options...  (one run; repeats are interleaved, see below)
  local label=$1; shift
  java -cp $CP Benchmark threads=$T duration=$D warmup=2 label="$label" csv=$CSV "$@" \
    > /dev/null 2> "results/logs/ablation-$label-$r.log" || { echo "FAILED $label"; exit 1; }
  grep RESULT "results/logs/ablation-$label-$r.log"
}

# Repeats are interleaved (round-robin over all variants), so slow drift of the
# machine during the ~15 minutes cannot favour or penalize a single variant.
for r in $(seq 1 "$R"); do
  run baseline           preset=baseline
  run base+out           preset=baseline out=buffered
  run base+stmtCache     preset=baseline stmtCache=true
  run base+batchWrites   preset=baseline batchWrites=true
  run base+autocommit1   preset=baseline autocommitSingle=true
  run base+pipeline      preset=baseline pipeline=true
  run base+syncOff       preset=baseline syncCommit=off
  run base+fillfactor70  preset=baseline fillfactor=70
  run base+avTune        preset=baseline avTune=true
  run base-gate          preset=baseline gate=false
  run optimized          preset=optimized
  run opt-pipeline       preset=optimized pipeline=false
  run opt-syncOn         preset=optimized syncCommit=on
  run opt-out            preset=optimized out=sync
  run opt+avTune         preset=optimized avTune=true
  run opt+fillfactor70   preset=optimized fillfactor=70
  run opt-gate           preset=optimized gate=false
  run opt+shareScans     preset=optimized shareScans=true
  run opt+shareScans4    preset=optimized shareScans=true scanLanes=4
  run opt+shareScans8    preset=optimized shareScans=true scanLanes=8
done
