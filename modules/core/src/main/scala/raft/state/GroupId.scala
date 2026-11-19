package raft.state

/** Type-safe identifier for a Raft consensus group. */
opaque type GroupId = String

object GroupId:
  def apply(value: String): GroupId = value

  /** Validate that a group identifier is non-empty. */
  def validate(raw: String): Validated[ConfigError, GroupId] =
    Validated.cond(raw.nonEmpty, raw, ConfigError.EmptyGroupId)

  extension (g: GroupId) def value: String = g

  given Ordering[GroupId] = Ordering.String
