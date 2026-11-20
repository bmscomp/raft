package raft.state

/** Pure, value-based domain & configuration validation error algebra (does not
  * extend `Throwable` and allocates no stack traces).
  */
enum ConfigError:
  case EmptyNodeId
  case OversizedNodeId(lengthBytes: Int, maxBytes: Int)
  case ControlCharacterInNodeId(raw: String)
  case EmptyGroupId
  case NegativeTerm(value: Long)
  case NegativeLogIndex(value: Long)
  case EmptyVoterSet(context: String)
  case InvalidPositiveInt(field: String, actual: Int)
  case InvalidDuration(field: String, detail: String)
  case InvalidIntervalOrdering(field: String, detail: String)
  case InvalidField(field: String, detail: String)

  def message: String = this match
    case EmptyNodeId =>
      "NodeId must be non-empty"
    case OversizedNodeId(len, max) =>
      s"NodeId UTF-8 byte length ($len) exceeds maximum ($max)"
    case ControlCharacterInNodeId(raw) =>
      s"NodeId contains control characters: '$raw'"
    case EmptyGroupId =>
      "GroupId must be non-empty"
    case NegativeTerm(v) =>
      s"Term must be >= 0, got $v"
    case NegativeLogIndex(v) =>
      s"LogIndex must be >= 0, got $v"
    case EmptyVoterSet(ctx) =>
      s"$ctx must contain at least one voting member"
    case InvalidPositiveInt(field, actual) =>
      s"$field must be > 0, got $actual"
    case InvalidDuration(field, detail) =>
      s"$field is invalid: $detail"
    case InvalidIntervalOrdering(field, detail) =>
      s"$field ordering is invalid: $detail"
    case InvalidField(field, detail) =>
      s"$field is invalid: $detail"

/** Zero-dependency error-accumulating applicative functor (`ValidatedNel`-like)
  * for validating composite configurations without throwing exceptions on the
  * first invalid field.
  */
enum Validated[+E, +A]:
  case Valid(value: A)
  case Invalid(errors: Vector[E])

  def isValid: Boolean = this match
    case Valid(_)   => true
    case Invalid(_) => false

  def isInvalid: Boolean = !isValid

  def map[B](fn: A => B): Validated[E, B] = this match
    case Valid(a)      => Valid(fn(a))
    case Invalid(errs) => Invalid(errs)

  def flatMap[EE >: E, B](fn: A => Validated[EE, B]): Validated[EE, B] =
    this match
      case Valid(a)      => fn(a)
      case Invalid(errs) => Invalid(errs)

  def zip[EE >: E, B](that: Validated[EE, B]): Validated[EE, (A, B)] =
    (this, that) match
      case (Valid(a), Valid(b))       => Valid((a, b))
      case (Invalid(e1), Invalid(e2)) => Invalid(e1 ++ e2)
      case (Invalid(e1), _)           => Invalid(e1)
      case (_, Invalid(e2))           => Invalid(e2)

  def toEither: Either[Vector[E], A] = this match
    case Valid(a)      => Right(a)
    case Invalid(errs) => Left(errs)

object Validated:
  def valid[A](value: A): Validated[Nothing, A] = Valid(value)

  def invalid[E](error: E): Validated[E, Nothing] = Invalid(Vector(error))

  def cond[E, A](test: Boolean, value: => A, error: => E): Validated[E, A] =
    if test then Valid(value) else Invalid(Vector(error))

  def check[E](test: Boolean, error: => E): Validated[E, Unit] =
    if test then Valid(()) else Invalid(Vector(error))

  def fromEither[E, A](either: Either[E, A]): Validated[E, A] = either match
    case Right(a) => Valid(a)
    case Left(e)  => Invalid(Vector(e))

  def mapN[E, A, B, C](
      va: Validated[E, A],
      vb: Validated[E, B]
  )(fn: (A, B) => C): Validated[E, C] =
    va.zip(vb).map { case (a, b) => fn(a, b) }

  def sequence_[E](checks: Seq[Validated[E, Unit]]): Validated[E, Unit] =
    checks.foldLeft(valid(()): Validated[E, Unit]) { (acc, c) =>
      acc.zip(c).map(_ => ())
    }
