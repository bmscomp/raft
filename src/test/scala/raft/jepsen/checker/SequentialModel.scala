package raft.jepsen.checker

/** A sequential specification against which operation histories are checked.
  *
  * The model defines the "correct" behavior of a single-threaded version of the
  * system. The linearizability checker tries to find a total ordering of
  * concurrent operations that is consistent with this model.
  */
trait SequentialModel:
  type State

  /** The initial state of the model. */
  def init: State

  /** Apply an operation to the model state, returning the new state and the
    * expected return value.
    *
    * @param state
    *   current model state
    * @param function
    *   the operation type
    * @param key
    *   the key operated on
    * @param value
    *   the input value (for writes/CAS)
    * @return
    *   (newState, expectedOutput) — None output means "any value is acceptable"
    */
  def step(
      state: State,
      function: OpFunction,
      key: String,
      value: Option[Any]
  ): (State, Option[Any])

  /** Check whether an observed output matches the expected output. Default:
    * exact equality, with None meaning "any value accepted".
    */
  def matches(expected: Option[Any], observed: Option[Any]): Boolean =
    expected.isEmpty || expected == observed

/** Single-value register model: supports Read, Write, CAS on a per-key basis.
  */
object RegisterModel extends SequentialModel:
  type State = Map[String, Any]

  def init: State = Map.empty

  def step(
      state: State,
      function: OpFunction,
      key: String,
      value: Option[Any]
  ): (State, Option[Any]) =
    function match
      case OpFunction.Read =>
        (state, Some(state.get(key)))
      case OpFunction.Write =>
        (state.updated(key, value.get), Some("ok"))
      case OpFunction.CAS =>
        val (expected, newVal) = value.get.asInstanceOf[(Any, Any)]
        if state.get(key).contains(expected) then
          (state.updated(key, newVal), Some("ok"))
        else (state, Some("failed"))

/** Multi-key KV model matching KVStateMachine semantics. */
object KVModel extends SequentialModel:
  type State = Map[String, String]

  def init: State = Map.empty

  def step(
      state: State,
      function: OpFunction,
      key: String,
      value: Option[Any]
  ): (State, Option[Any]) =
    function match
      case OpFunction.Read =>
        (state, Some(state.get(key)))
      case OpFunction.Write =>
        val v = value.get.asInstanceOf[String]
        (state.updated(key, v), Some("ok"))
      case OpFunction.CAS =>
        val (expected, newVal) = value.get.asInstanceOf[(String, String)]
        if state.get(key).contains(expected) then
          (state.updated(key, newVal), Some("ok"))
        else (state, Some("failed"))

/** Monotonic counter model: write increments, read returns current value. */
object CounterModel extends SequentialModel:
  type State = Map[String, Long]

  def init: State = Map.empty

  def step(
      state: State,
      function: OpFunction,
      key: String,
      value: Option[Any]
  ): (State, Option[Any]) =
    function match
      case OpFunction.Read =>
        (state, Some(state.getOrElse(key, 0L)))
      case OpFunction.Write =>
        val current = state.getOrElse(key, 0L)
        (state.updated(key, current + 1), Some(current + 1))
      case OpFunction.CAS =>
        val (expected, newVal) = value.get.asInstanceOf[(Long, Long)]
        val current = state.getOrElse(key, 0L)
        if current == expected then (state.updated(key, newVal), Some("ok"))
        else (state, Some("failed"))
