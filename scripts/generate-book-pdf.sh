#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BOOK_DIR="$PROJECT_ROOT/docs/book"
TUTORIALS_DIR="$PROJECT_ROOT/tutorials"
OUTPUT_DIR="$PROJECT_ROOT/build"

mkdir -p "$OUTPUT_DIR"

BOOK_CHAPTERS=(
  "$BOOK_DIR/01-why-consensus.md"
  "$BOOK_DIR/02-raft-fundamentals.md"
  "$BOOK_DIR/03-raft-advanced-theory.md"
  "$BOOK_DIR/04-safety-proofs-implemented.md"
  "$BOOK_DIR/05-design-philosophy.md"
  "$BOOK_DIR/06-core-api.md"
  "$BOOK_DIR/07-spi-layer.md"
  "$BOOK_DIR/08-getting-started.md"
  "$BOOK_DIR/09-log-replication-practice.md"
  "$BOOK_DIR/10-performance-vs-safety.md"
  "$BOOK_DIR/11-failure-modes.md"
  "$BOOK_DIR/12-case-distributed-kv.md"
  "$BOOK_DIR/13-case-distributed-lock.md"
  "$BOOK_DIR/14-case-distributed-counter.md"
  "$BOOK_DIR/15-case-distributed-transactions.md"
  "$BOOK_DIR/16-state-of-the-art.md"
  "$BOOK_DIR/17-troubleshooting.md"
  "$BOOK_DIR/18-end-to-end-integration.md"
  "$BOOK_DIR/19-property-based-testing.md"
  "$BOOK_DIR/20-jepsen-verification.md"
  "$BOOK_DIR/21-multi-raft-groups.md"
  "$BOOK_DIR/22-sans-io-rawnode-ready.md"
  "$BOOK_DIR/23-host-system-integration.md"
  "$BOOK_DIR/24-deterministic-simulation-testing.md"
  "$BOOK_DIR/appendix-a-quick-reference.md"
  "$BOOK_DIR/appendix-b-examples-catalog.md"
)

TUTORIAL_FILES=(
  "$TUTORIALS_DIR/01-configuration-service.md"
  "$TUTORIALS_DIR/02-leader-election-microservices.md"
  "$TUTORIALS_DIR/03-replicated-task-queue.md"
  "$TUTORIALS_DIR/04-distributed-rate-limiter.md"
  "$TUTORIALS_DIR/05-cluster-membership.md"
  "$TUTORIALS_DIR/06-event-sourcing.md"
  "$TUTORIALS_DIR/07-distributed-lease-service.md"
  "$TUTORIALS_DIR/08-service-registry.md"
  "$TUTORIALS_DIR/09-sharded-counter-multi-raft.md"
  "$TUTORIALS_DIR/10-client-sessions.md"
  "$TUTORIALS_DIR/11-snapshot-compaction.md"
  "$TUTORIALS_DIR/12-chaos-testing.md"
  "$TUTORIALS_DIR/13-observability.md"
  "$TUTORIALS_DIR/14-multi-datacenter.md"
  "$TUTORIALS_DIR/15-batching-and-pipelining.md"
  "$TUTORIALS_DIR/16-cross-shard-2pc-ledger.md"
)

PANDOC_OPTS=(
  --toc
  --toc-depth=2
  --highlight-style=tango
  --pdf-engine=xelatex
  -V geometry:margin=1in
  -V fontsize=11pt
  -V documentclass=report
  -V colorlinks=true
  -V linkcolor=blue
  -V urlcolor=blue
  -V toccolor=black
  -V mainfont="Arial Unicode MS"
  -V monofont="Menlo"
  -V mathfont="Arial Unicode MS"
  -V linestretch=1.15
)

case "${1:-all}" in
  book)
    echo "Generating book PDF (24 chapters + 2 appendices)..."
    pandoc "${PANDOC_OPTS[@]}" \
      --metadata title="Raft Consensus: From Theory to Production" \
      --metadata subtitle="A Functional Scala 3 Implementation" \
      --metadata author="The Raft Library Contributors" \
      "${BOOK_CHAPTERS[@]}" \
      -o "$OUTPUT_DIR/raft-book.pdf"
    echo "Created: $OUTPUT_DIR/raft-book.pdf"
    ;;

  tutorials)
    echo "Generating tutorials PDF (16 tutorials)..."
    pandoc "${PANDOC_OPTS[@]}" \
      --metadata title="Raft Tutorials" \
      --metadata subtitle="Real-World Distributed Systems with Scala 3" \
      --metadata author="The Raft Library Contributors" \
      "$TUTORIALS_DIR/README.md" \
      "${TUTORIAL_FILES[@]}" \
      -o "$OUTPUT_DIR/raft-tutorials.pdf"
    echo "Created: $OUTPUT_DIR/raft-tutorials.pdf"
    ;;

  all)
    echo "Generating complete PDF (book + tutorials)..."

    # Create a part separator for tutorials
    TUTORIALS_SEPARATOR=$(mktemp)
    printf '\n\n\\newpage\n\n# Part IX — Tutorials\n\n' > "$TUTORIALS_SEPARATOR"

    pandoc "${PANDOC_OPTS[@]}" \
      --metadata title="Raft Consensus: From Theory to Production" \
      --metadata subtitle="A Complete Guide with Tutorials" \
      --metadata author="The Raft Library Contributors" \
      "${BOOK_CHAPTERS[@]}" \
      "$TUTORIALS_SEPARATOR" \
      "${TUTORIAL_FILES[@]}" \
      -o "$OUTPUT_DIR/raft-complete.pdf"

    rm -f "$TUTORIALS_SEPARATOR"
    echo "Created: $OUTPUT_DIR/raft-complete.pdf"
    ;;

  *)
    echo "Usage: $0 [book|tutorials|all]"
    echo "  book       - Generate book PDF only (24 chapters + 2 appendices)"
    echo "  tutorials  - Generate tutorials PDF only (16 tutorials)"
    echo "  all        - Generate combined PDF (default)"
    exit 1
    ;;
esac

echo "Done."
