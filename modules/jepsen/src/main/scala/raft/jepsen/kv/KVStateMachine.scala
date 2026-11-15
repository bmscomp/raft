package raft.jepsen.kv

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import fs2.Stream
import raft.spi.StateMachine
import raft.state.{Log, LogId}

import java.nio.charset.StandardCharsets

/** Self-contained linearizable key-value command model for the Jepsen harness.
  */
enum KVCommand:
  case Put(key: String, value: String)
  case Delete(key: String)
  case Get(key: String)
  case CAS(key: String, expected: String, newValue: String)

object KVCommand:
  private val SEP = "\t"

  def encode(cmd: KVCommand): Array[Byte] = cmd match
    case KVCommand.Put(k, v) =>
      s"PUT$SEP$k$SEP$v".getBytes(StandardCharsets.UTF_8)
    case KVCommand.Delete(k) => s"DEL$SEP$k".getBytes(StandardCharsets.UTF_8)
    case KVCommand.Get(k)    => s"GET$SEP$k".getBytes(StandardCharsets.UTF_8)
    case KVCommand.CAS(k, expected, newVal) =>
      s"CAS$SEP$k$SEP$expected$SEP$newVal".getBytes(StandardCharsets.UTF_8)

  def decode(data: Array[Byte]): Either[String, KVCommand] =
    val str = new String(data, StandardCharsets.UTF_8)
    str.split(SEP, -1).toList match
      case "PUT" :: key :: value :: Nil => Right(KVCommand.Put(key, value))
      case "DEL" :: key :: Nil          => Right(KVCommand.Delete(key))
      case "GET" :: key :: Nil          => Right(KVCommand.Get(key))
      case "CAS" :: key :: expected :: newVal :: Nil =>
        Right(KVCommand.CAS(key, expected, newVal))
      case _ => Left(s"Invalid command: $str")

enum KVResult:
  case Ok
  case Value(v: Option[String])
  case CasOk
  case CasFailed(currentValue: Option[String])
  case Error(msg: String)

final class KVStateMachine[F[_]: Sync](
    store: Ref[F, Map[String, String]],
    results: Ref[F, Map[LogId, KVResult]]
) extends StateMachine[F, KVResult]:

  def apply(log: Log): F[KVResult] =
    val computeResult: F[KVResult] = KVCommand.decode(log.data) match
      case Right(KVCommand.Put(key, value)) =>
        store.update(_.updated(key, value)).as(KVResult.Ok)
      case Right(KVCommand.Delete(key)) =>
        store.update(_ - key).as(KVResult.Ok)
      case Right(KVCommand.Get(key)) =>
        store.get.map(m => KVResult.Value(m.get(key)))
      case Right(KVCommand.CAS(key, expected, newValue)) =>
        store.modify { m =>
          m.get(key) match
            case Some(v) if v == expected =>
              (m.updated(key, newValue), KVResult.CasOk)
            case current =>
              (m, KVResult.CasFailed(current))
        }
      case Left(err) =>
        Sync[F].pure(KVResult.Error(err))

    val logId = LogId.fromLog(log)
    computeResult.flatTap(res => results.update(_.updated(logId, res)))

  def snapshot: F[Stream[F, Byte]] =
    store.get.map { m =>
      Stream
        .emits(
          m.map { case (k, v) => s"$k=$v" }
            .mkString("\n")
            .getBytes(StandardCharsets.UTF_8)
        )
        .covary[F]
    }

  def restore(data: Stream[F, Byte]): F[Unit] =
    data.compile.to(Array).flatMap { bytes =>
      val entries = new String(bytes, StandardCharsets.UTF_8)
        .split("\n")
        .flatMap { line =>
          line.split("=", 2) match
            case Array(k, v) if k.nonEmpty => Some(k -> v)
            case _                         => None
        }
        .toMap
      store.set(entries) *> results.set(Map.empty)
    }

  def get(key: String): F[Option[String]] =
    store.get.map(_.get(key))

  def getResult(logId: LogId): F[Option[KVResult]] =
    results.get.map(_.get(logId))

object KVStateMachine:
  def apply[F[_]: Sync]: F[KVStateMachine[F]] =
    for
      store <- Ref.of[F, Map[String, String]](Map.empty)
      results <- Ref.of[F, Map[LogId, KVResult]](Map.empty)
    yield new KVStateMachine[F](store, results)
