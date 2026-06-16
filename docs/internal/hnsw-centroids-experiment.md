# HNSW Centroid Graph Experiment — Status & Handoff

**Branch:** `hnsw-centroids-graph` | **Worktree:** `~/Projects/es-hnsw-centroids-graph`

## What Was Built

`ESNextDiskBBQVectorsFormat` (IVF/DiskBBQ) normally selects posting lists by brute-force scanning all centroids. This experiment replaces that scan with two connected ideas:

1. **Centroid HNSW graph** ("head index"): centroids are indexed into an HNSW graph at write time and searched at query time. The graph uses the configured similarity (Euclidean, cosine, or MIP), making routing correct for the query metric.

2. **SPANN-style overspill** (AIR/SRAIR): each vector is assigned to up to `N` centroids instead of one. Secondary selection uses the AIR criterion from RAIRS (SIGMOD 2026): prefer a secondary whose residual points *opposite* the primary residual (the far side of the vector, where boundary queries route). For MIP the rule switches to SOAR (orthogonal residual, ScaNN). Primaries are always kept; replicas are trimmed by distance when postings exceed a cap.

**Benchmarked result (dbpedia-entity, force-merged single segment, cosine):** +47–60% QPS at matched recall vs. old `ivf-384-16`, recall ceiling extended from 0.85 → 0.93. Multi-segment production comparison is a pending task.

---

## New Parameters (KnnIndexTester / TestConfiguration)

All parameters are experiment-only; none are reachable from user/mapper config.

### Centroid graph (index routing)

| JSON key | Default | Description |
|---|---|---|
| `index_centroids_in_graph` | `false` | Enables the centroid HNSW graph; all other params in this table require it. |
| `centroid_hnsw_m` | `16` | HNSW M (max neighbors per node) for the centroid graph built at index time. |
| `centroid_hnsw_beam_width` | `100` | efConstruction for the centroid graph (build quality). |
| `centroid_graph_ef_search` | `-1` | efSearch at query time; `-1` derives from `visitRatio`. |

### SPANN overspill (multi-replica posting assignment)

Requires `index_centroids_in_graph: true` and `replica_count > 1`.

| JSON key | Default | Description |
|---|---|---|
| `replica_count` | `1` (disabled) | Max centroids a vector may be assigned to; `1` means single-assignment. |
| `internal_result_num` | `64` | Head-index search depth (candidate pool size) per vector during assignment. |
| `rng_factor` | `1.0` | Reused as the AIR amplification λ; larger = more aggressive secondary acceptance. Recommended: `0.75`. |
| `closure_epsilon` | `-1` (disabled) | Unused; retained for tester compatibility. |
| `max_posting_factor` | `-1` (disabled) | Trims postings to `max_posting_factor × vectors_per_cluster`. Recommended: `1.5`. |

### Results columns added

`replica_count` and `lambda` are emitted in both console headers and the CSV, allowing lambda/replica sweeps over a shared index without rebuilding.

---

## Key Changes and Why

- **`ESNextDiskBBQVectorsFormat`** — new constructor overload for graph + overspill params; defaults keep all existing constructors unchanged; no production path reaches the new code.

- **`ESNextDiskBBQVectorsWriter`** — `writeCentroidsWithGraph` path: builds a multi-level HNSW graph over centroids using `CentroidGraphIO`, computes SPANN replicas via `SpannOverspill.assign`, writes a 1-byte `searchCentroidBudget` marker in meta, and logs replication factor pre/post-trim. Merge: added early-return full-rebuild for graph mode before the new `TieredMergeStrategy` path (which cannot parse graph-mode centroid layout).

- **`ESNextDiskBBQVectorsReader`** — `maxVectorsToVisit` overridden to return `Long.MAX_VALUE` when `searchCentroidBudget` (the budget counts distinct centroids visited, not raw vectors — required because replicated postings inflate the vector count making the original budget formula stall after few heads). `FixedBitSet seen` added to `MemorySegmentPostingsVisitor` to dedup docids across replicated postings.

- **`IVFVectorsReader`** — added `maxVectorsToVisit(entry, visitRatio, numVectors)` override hook so the next-format reader can substitute a different budget strategy.

- **`CentroidGraphIO`** — all graph build methods now take `VectorSimilarityFunction`; diversity prune and neighbor sort use `dist = -similarity.compare(a, b)`. Correct for MIP; non-regressive for cosine/Euclidean (identical edge ordering).

- **`SpannOverspill`** — `assign(...)` takes `VectorSimilarityFunction`; the build-time head-index graph and candidate search score via `similarity.compare`. Secondary rule: AIR for Euclidean/cosine, SOAR for MIP. `candidateSq` computed directly via `ESVectorUtil.squareDistance` (not recovered from scorer). Three-distance identity used for inter-centroid dot products (no full vector loads).

- **`ESNextDiskBBQVectorsWriter.trimPostingToCap`** — static helper; primaries are never dropped even when posting exceeds `trimCap` (`keepCount = max(primaryCount, trimCap)`); replicas trimmed by ascending distance.

---

## Key Decisions and Pivots

**RNG → AIR/SRAIR.** Initial secondary assignment used Vamana RNG pruning (`rngFactor`). At cluster sizes of ~128 vectors (vs SPTAG's ~5), RNG at any useful factor pruned nearly all secondaries or accepted all (distance concentration in high dimensions). Pivoted to AIR/SRAIR from the RAIRS paper (SIGMOD 2026): mathematically correct for Euclidean redundant assignment, self-calibrating threshold, no new sensitive parameter.

**Vector-count budget → centroid-count budget.** With replication, the existing `2.0 × visitRatio × numVectors` budget was relative to inflated posting sizes, causing search to exhaust the budget after visiting far fewer centroid heads than intended. Switched to a head-count budget (`Long.MAX_VALUE` on vector cap, count centroids directly), gated by the `searchCentroidBudget` persisted flag.

**Primary-safe trim.** Original trim kept the `trimCap` nearest members regardless of role. On imbalanced clusters with more primaries than `trimCap`, primaries were silently dropped — recall loss with no warning. Fixed: `keepCount = max(primaryCount, trimCap)`; only replicas trimmed.

**Metric-aware graph and secondary selection.** Building the centroid graph and selecting secondaries with Euclidean distance when the query metric is MIP would cause routing to favor geometrically-near centroids, not score-near ones. Both the persisted graph (`CentroidGraphIO`) and the build-time assignment graph (`SpannOverspill`) now use the configured similarity. Residuals and partitioning stay Euclidean (k-means correctness). Noted caveat: candidate pools feeding the MIP graph are still Euclidean-sourced (clustering neighborhoods); if MIP benchmarks underperform, the lever is recomputing IP candidate pools.

**`closureEpsilon` disabled bug.** The sentinel value `closureEpsilon = -1` was being used in a threshold formula as `(1 + eps)² = 0`, setting the SRAIR acceptance threshold to zero and rejecting all secondaries. Fixed with `closureEnabled = closureEpsilon >= 0` guard. `closureEpsilon` is now unused in the AIR path (retained only for tester backward compat).

**Lambda sweep finding.** Swept λ ∈ {0.25, 0.5, 0.75, 1.0} on quora (force-merged) and dbpedia (multi-segment): **λ = 0.75** was the clear winner for dbpedia k=10 (+60% QPS at matched recall vs λ=0.5). λ=1.0 over-assigned and degraded index efficiency.

---

## Pending Work

- **Multi-segment baseline comparison**: run `replica_count=1` cgraph and old `ivf-384-16` on the same 18-segment dbpedia configuration to get a clean production QPS delta.
- **MIP dataset benchmark**: code is ready; user has a MIP dataset. Validate AIR→SOAR switchover and metric-aware graph routing.
- **Fine λ sweep**: {0.6, 0.75, 0.85} on dbpedia to pin the optimum between 0.75 and 1.0.
- **Dedup score policy**: current dedup keeps first-seen score per docid; keeping the best score would require a score map instead of `FixedBitSet` — not yet addressed.
- **Double graph build**: `SpannOverspill.assign` builds its own HNSW graph over centroids; the writer also builds one via `CentroidGraphIO`. These could share the on-heap graph (marked `TODO` in source).
