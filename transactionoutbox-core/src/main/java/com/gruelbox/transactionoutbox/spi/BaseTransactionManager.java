package com.gruelbox.transactionoutbox.spi;

import com.gruelbox.transactionoutbox.Invocation;
import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.TransactionalInvocation;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Base type for transaction management in a {@code TransactionOutbox}. Provides the minimal SPI
 * surface required to allow {@code TransactionOutbox} to work and is not intended to be a
 * client-side API. However, subtypes may extend this to provide client support.
 *
 * @param <CN> The type which the associated {@link Persistor} implementation will use to interact
 *     with the data store.
 * @param <TX> The transaction type.
 */
public interface BaseTransactionManager<CN, TX extends BaseTransaction<CN>> {

  /**
   * Should do any work necessary to start a (new) transaction, call {@code work} and then either
   * commit on success or rollback on failure, flushing and closing any resources prior to a commit
   * and firing post commit hooks immediately afterwards.
   *
   * @param <T> The type returned.
   * @param work Code which must be called while the transaction is active.
   * @return The result of {@code work}.
   */
  <T> CompletableFuture<T> transactionally(Function<TX, CompletableFuture<T>> work);

  /**
   * All transaction managers need to be able to take a method call at the time it is scheduled and
   * determine the {@link BaseTransaction} to use to pass to {@link Persistor} and save the request.
   * They can do this either by examining some current application state or by parsing the method
   * and arguments.
   *
   * @param method The method called.
   * @param args The method arguments.
   * @return The extracted transaction and any modifications to the method and arguments.
   */
  TransactionalInvocation extractTransaction(Method method, Object[] args);

  /**
   * Makes any modifications to an invocation at runtime necessary to inject the current transaction
   * or transaction context.
   *
   * @param invocation The invocation.
   * @param transaction The transaction that the invocation will be run in.
   * @return The modified invocation.
   */
  Invocation injectTransaction(Invocation invocation, TX transaction);
}