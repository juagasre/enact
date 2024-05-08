package eu.assistiot.semantic_repo.core.datamodel

import com.typesafe.scalalogging.LazyLogging
import org.mongodb.scala._
import org.mongodb.scala.model.{ Filters, Updates }

import scala.util.{Try, Success, Failure}

/**
 * MongoDB transaction handling. To be extended by MongoModel.
 */
trait MongoTransactions extends LazyLogging:
  def mongoClient: MongoClient

  private def commitAndRetry(observable: SingleObservable[Void]): SingleObservable[Void] =
    observable.recoverWith({
      case e: MongoException if e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) =>
        logger.warn("UnknownTransactionCommitResult, retrying commit operation ...")
        commitAndRetry(observable)
    })

  private def runTransactionAndRetry(observable: SingleObservable[Void]): SingleObservable[Void] =
    observable.recoverWith({
      case e: MongoException if e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) =>
        logger.warn(s"TransientTransactionError, aborting transaction and retrying ... $e")
        runTransactionAndRetry(observable)
    })

  /**
   * Wraps a series of MongoDB operations in a transaction that will be retried if any transient errors occur.
   *
   * @param inner Function returning a SingleObservable[Unit]. The transaction will only be committed when this
   *              observable returns successfully. If it throws an exception, that will be propagated to the result
   *              of the entire transaction.
   * @param transactionOptions Transaction options or None. Default: empty options.
   * @return Observable indicating whether the transaction was committed successfully.
   */
  def transaction(inner: ClientSession => SingleObservable[Unit], transactionOptions: Option[TransactionOptions]):
  SingleObservable[Void] =
    val sessionObservable = mongoClient.startSession()
    val doUpdateObservable = sessionObservable flatMap { clientSession =>
      clientSession.startTransaction(
        transactionOptions getOrElse { TransactionOptions.builder().build() }
      )
      inner(clientSession) map { _ => clientSession }
    }
    val commitTransactionObservable =
      doUpdateObservable.flatMap(clientSession => clientSession.commitTransaction())
    val commitAndRetryObservable = commitAndRetry(commitTransactionObservable)
    runTransactionAndRetry(commitAndRetryObservable)
