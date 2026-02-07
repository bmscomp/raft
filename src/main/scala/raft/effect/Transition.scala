package raft.effect

import raft.state.NodeState

/** Result of a pure RAFT state transition.
  *
  * Every function in [[raft.logic.RaftLogic]] returns a `Transition`, pairing
  * the new [[NodeState]] with a list of [[Effect]]s that the runtime must
  * execute. This ensures that state changes and their resulting side effects
  * are atomic from the perspective of the pure consensus logic.
  *
  * @param state
  *   the new node state after the transition
  * @param effects
  *   ordered list of side effects to execute (messaging, persistence, etc.)
  * @see
  *   [[raft.logic.RaftLogic]] for the functions that produce transitions
  * @see
  *   [[Effect]] for the hierarchy of possible side effects
  */
final case class Transition(
    state: NodeState,
    effects: List[Effect]
)

/** Factory methods for constructing [[Transition]] instances.
  *
  * These helpers reduce boilerplate when building transitions in
  * [[raft.logic.RaftLogic]] handlers.
  */
object Transition:
  /** Create a transition with no effects (state change only).
    *
    * @param state
    *   the new node state
    * @return
    *   a transition with an empty effect list
    */
  def pure(state: NodeState): Transition =
    Transition(state, Nil)

  /** Create a transition with a single effect.
    *
    * @param state
    *   the new node state
    * @param effect
    *   the single side effect to execute
    * @return
    *   a transition containing the given effect
    */
  def withEffect(state: NodeState, effect: Effect): Transition =
    Transition(state, List(effect))

  /** Create a transition with multiple effects.
    *
    * @param state
    *   the new node state
    * @param effects
    *   the side effects to execute, in order
    * @return
    *   a transition containing all given effects
    */
  def withEffects(state: NodeState, effects: Effect*): Transition =
    Transition(state, effects.toList)
