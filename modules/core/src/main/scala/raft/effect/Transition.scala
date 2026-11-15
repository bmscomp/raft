package raft.effect

import raft.state.NodeState

/** Atomic result of a pure RAFT state transition: `(newState, effects)`. */
final case class Transition(
    state: NodeState,
    effects: List[Effect]
)

object Transition:
  /** Create a transition with no side effects. */
  def pure(state: NodeState): Transition =
    Transition(state, Nil)

  /** Create a transition with a single side effect. */
  def withEffect(state: NodeState, effect: Effect): Transition =
    Transition(state, List(effect))

  /** Create a transition with multiple ordered side effects. */
  def withEffects(state: NodeState, effects: Effect*): Transition =
    Transition(state, effects.toList)
