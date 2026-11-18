package raft.jepsen.checker

import scala.util.boundary
import scala.util.boundary.break

/** Result of a linearizability check. */
case class CheckResult(
    linearizable: Boolean,
    witness: Option[Vector[Operation]],
    violation: Option[Violation],
    stats: CheckStats
)

/** A linearizability violation: the minimal sub-history demonstrating the
  * issue.
  */
case class Violation(
    description: String,
    involvedOps: Vector[Operation]
)

/** Statistics from a linearizability check run. */
case class CheckStats(
    opsChecked: Int,
    keysChecked: Int,
    exploredStates: Long,
    durationMs: Long
)

/** WGL-based linearizability checker with P-compositionality optimization.
  *
  * The checker exploits P-compositionality: a history H is linearizable iff for
  * every key k, the sub-history H|k is linearizable. This reduces the
  * NP-complete problem to independent per-key checks with much smaller search
  * spaces.
  *
  * Algorithm overview (per key):
  *   1. Build concurrent pairs: (invocation, completion) for each operation
  *   2. Identify "linearization points" — operations whose real-time order
  *      constrains placement
  *   3. DFS over possible linearization orders, pruning when the sequential
  *      model rejects
  *   4. If a valid linearization is found, the history is linearizable for that
  *      key
  *   5. If no valid linearization exists, extract a minimal violation
  */
object LinearizabilityChecker:

  private val DefaultTimeoutMs = 10_000L

  /** Check whether a history is linearizable against a model.
    *
    * Uses P-compositionality: checks each key independently, then combines.
    */
  def check(
      history: History,
      model: SequentialModel,
      timeoutMs: Long = DefaultTimeoutMs
  ): CheckResult =
    val startTime = System.currentTimeMillis()
    // Retain indeterminate (Info) mutations as open-ended invocations while stripping Info reads
    val cleaned = history.prepareForChecking
    val keys = cleaned.keys

    var totalExplored = 0L
    var allWitness = Vector.empty[Operation]

    boundary:
      for key <- keys do
        val keyHistory = cleaned.forKey(key)
        val result = checkSingleKey(keyHistory, model, timeoutMs)
        totalExplored += result.stats.exploredStates

        if !result.linearizable then
          val elapsed = System.currentTimeMillis() - startTime
          break(
            CheckResult(
              linearizable = false,
              witness = None,
              violation = result.violation,
              stats = CheckStats(
                opsChecked = cleaned.invocations.size,
                keysChecked = keys.size,
                exploredStates = totalExplored,
                durationMs = elapsed
              )
            )
          )

        result.witness.foreach(w => allWitness = allWitness ++ w)

      val elapsed = System.currentTimeMillis() - startTime
      CheckResult(
        linearizable = true,
        witness = Some(allWitness),
        violation = None,
        stats = CheckStats(
          opsChecked = cleaned.invocations.size,
          keysChecked = keys.size,
          exploredStates = totalExplored,
          durationMs = elapsed
        )
      )

  /** Check linearizability for a single key's sub-history. */
  private def checkSingleKey(
      history: History,
      model: SequentialModel,
      timeoutMs: Long
  ): CheckResult =
    val startTime = System.currentTimeMillis()
    val pairs = history.pairs
    val definiteCount = pairs.count(_._2.isDefined)
    if definiteCount == 0 then
      return CheckResult(true, Some(Vector.empty), None, CheckStats(0, 1, 0, 0))

    val deadline = startTime + timeoutMs
    var explored = 0L

    // Build the set of "entry" objects with real-time constraints
    case class Entry(
        inv: Operation,
        comp: Option[Operation],
        invTime: Long,
        compTime: Option[Long]
    )

    val entries = pairs.map { case (inv, comp) =>
      Entry(inv, comp, inv.timestamp, comp.map(_.timestamp))
    }.toArray

    // DFS: try to linearize all definite entries (using indeterminate Info mutations only if/when needed)
    def dfs(
        remaining: Array[Entry],
        modelState: model.State,
        linearized: Vector[Operation]
    ): Option[Vector[Operation]] =
      if System.currentTimeMillis() > deadline then return None
      if remaining.isEmpty || remaining.forall(_.comp.isEmpty) then
        return Some(linearized)

      explored += 1

      // An entry can be linearized next if no un-linearized definite entry
      // has a completion time strictly before this entry's invocation time
      // (real-time precedence constraint)
      val rawCandidates = remaining.indices.filter { i =>
        val candidate = remaining(i)
        remaining.indices.forall { j =>
          if i == j then true
          else
            val other = remaining(j)
            other.compTime.forall(_ >= candidate.invTime)
        }
      }
      val (definiteCands, infoCands) =
        rawCandidates.partition(i => remaining(i).comp.isDefined)
      val candidates = definiteCands ++ infoCands

      candidates.iterator
        .map { idx =>
          val entry = remaining(idx)
          val (newState, expectedOutput) = model.step(
            modelState,
            entry.inv.function,
            entry.inv.key,
            entry.inv.inputValue
          )

          // Indeterminate (Info) entries have no observed completion output and only need to be taken when they mutate state
          val worthwhile = entry.comp.isDefined || newState != modelState
          val outputMatches = entry.comp match
            case None       => true
            case Some(comp) => model.matches(expectedOutput, comp.outputValue)

          if worthwhile && outputMatches then
            val newRemaining = remaining.indices
              .filter(_ != idx)
              .map(remaining)
              .toArray
            dfs(newRemaining, newState, linearized :+ entry.inv)
          else None
        }
        .collectFirst { case Some(witness) => witness }

    val result = dfs(entries, model.init, Vector.empty)
    val elapsed = System.currentTimeMillis() - startTime

    result match
      case Some(witness) =>
        CheckResult(
          linearizable = true,
          witness = Some(witness),
          violation = None,
          stats = CheckStats(pairs.size, 1, explored, elapsed)
        )
      case None =>
        CheckResult(
          linearizable = false,
          witness = None,
          violation = Some(
            Violation(
              description =
                s"No valid linearization found for key '${history.operations.headOption.map(_.key).getOrElse("?")}' " +
                  s"after exploring $explored states in ${elapsed}ms",
              involvedOps = history.invocations
            )
          ),
          stats = CheckStats(pairs.size, 1, explored, elapsed)
        )
