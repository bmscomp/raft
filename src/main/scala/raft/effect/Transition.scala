package raft.effect

import raft.state.NodeState

/**
 * Result of a pure RAFT state transition.
 * 
 * Contains the new state and a list of effects to execute.
 * This is the core return type of all logic functions.
 * 
 * @param state new node state after transition
 * @param effects side effects to execute (in order)
 */
final case class Transition(
  state: NodeState,
  effects: List[Effect]
)

object Transition:
  /**
   * Create transition with no effects (state unchanged or internal change only).
   */
  def pure(state: NodeState): Transition =
    Transition(state, Nil)
  
  /**
   * Create transition with single effect.
   */
  def withEffect(state: NodeState, effect: Effect): Transition =
    Transition(state, List(effect))
  
  /**
   * Create transition with multiple effects.
   */
  def withEffects(state: NodeState, effects: Effect*): Transition =
    Transition(state, effects.toList)
