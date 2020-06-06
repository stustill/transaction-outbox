package com.gruelbox.transactionoutbox.acceptance;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ea.async.Async;
import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.Submitter;
import com.gruelbox.transactionoutbox.ThrowingRunnable;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.TransactionOutboxEntry;
import com.gruelbox.transactionoutbox.TransactionOutboxListener;
import com.gruelbox.transactionoutbox.Utils;
import com.gruelbox.transactionoutbox.spi.BaseTransaction;
import com.gruelbox.transactionoutbox.spi.BaseTransactionManager;
import com.gruelbox.transactionoutbox.sql.Dialect;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Generalised, non-blocking acceptance tests which all combinations of transaction manager,
 * persistor and dialect need to conform, regardless of implementation.
 *
 * <p>There's a SQL assumption built in here
 *
 * @param <CN> The connection type.
 * @param <TX> The transaction type.
 * @param <TM> The transaction manager type
 */
@Slf4j
abstract class AbstractAcceptanceTest<
    CN, TX extends BaseTransaction<CN>, TM extends BaseTransactionManager<CN, ? extends TX>> {

  static {
    Async.init();
  }

  private final ExecutorService unreliablePool =
      new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));

  protected abstract Dialect dialect();

  protected abstract Persistor<CN, TX> createPersistor();

  protected abstract TM createTxManager();

  protected abstract CompletableFuture<Void> scheduleWithTx(
      TransactionOutbox outbox, TX tx, int arg1, String arg2);

  protected abstract CompletableFuture<Void> scheduleWithCtx(
      TransactionOutbox outbox, Object context, int arg1, String arg2);

  protected abstract CompletableFuture<?> prepareDataStore();

  protected abstract CompletableFuture<?> cleanDataStore();

  protected abstract CompletableFuture<?> incrementRecordCount(Object txOrContext);

  protected abstract CompletableFuture<Long> countRecords(TX tx);

  private static Persistor<?, ?> staticPersistor;
  private static BaseTransactionManager<?, ?> staticTxManager;
  protected Persistor<CN, TX> persistor;
  protected TM txManager;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void beforeEach() {
    if (staticPersistor == null) {
      staticPersistor = createPersistor();
      staticTxManager = createTxManager();
      persistor = (Persistor<CN, TX>) staticPersistor;
      txManager = (TM) staticTxManager;
      Utils.join(prepareDataStore());
    } else {
      persistor = (Persistor<CN, TX>) staticPersistor;
      txManager = (TM) staticTxManager;
    }
  }

  private TransactionOutbox.TransactionOutboxBuilder builder() {
    return TransactionOutbox.builder().transactionManager(txManager).persistor(persistor);
  }

  @Test
  final void testSimple() {
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch chainCompleted = new CountDownLatch(1);
    TransactionOutbox outbox =
        builder()
            .instantiator(new LoggingInstantiator())
            .listener(new LatchListener(latch))
            .build();
    Utils.join(cleanDataStore());

    Utils.join(
        txManager
            .transactionally(
                tx -> scheduleWithTx(outbox, tx, 3, "Whee").thenRun(() -> assertNotFired(latch)))
            .thenRun(() -> assertFired(latch))
            .thenRun(chainCompleted::countDown));

    assertFired(chainCompleted);
  }

  @Test
  final void testSimpleWithDatabaseAccessViaTransaction() {
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch chainCompleted = new CountDownLatch(1);
    TransactionOutbox outbox =
        builder()
            .instantiator(new InsertingInstantiator(this::incrementRecordCount))
            .listener(new LatchListener(latch))
            .build();
    Utils.join(cleanDataStore());

    Utils.join(
        txManager
            .transactionally(
                tx ->
                    scheduleWithTx(outbox, tx, 3, "Whee")
                        .thenRun(() -> assertNotFired(latch))
                        .thenCompose(__ -> countRecords(tx))
                        .thenApply(
                            count -> {
                              assertEquals(0, count);
                              return count;
                            }))
            .thenRun(() -> assertFired(latch))
            .thenRun(chainCompleted::countDown));

    assertFired(chainCompleted);

    Long rowCount = Utils.join(txManager.transactionally(this::countRecords));
    assertEquals(1, rowCount);
  }

  @Test
  final void testSimpleWithDatabaseAccessViaContext() {
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch chainCompleted = new CountDownLatch(1);
    TransactionOutbox outbox =
        builder()
            .instantiator(new InsertingInstantiator(this::incrementRecordCount))
            .listener(new LatchListener(latch))
            .build();
    Utils.join(cleanDataStore());

    Utils.join(
        txManager
            .transactionally(
                tx ->
                    scheduleWithCtx(outbox, tx.context(), 3, "Whee")
                        .thenRun(() -> assertNotFired(latch))
                        .thenCompose(__ -> countRecords(tx))
                        .thenApply(
                            count -> {
                              assertEquals(0, count);
                              return count;
                            }))
            .thenRun(() -> assertFired(latch))
            .thenRun(chainCompleted::countDown));

    assertFired(chainCompleted);

    Long rowCount = Utils.join(txManager.transactionally(this::countRecords));
    assertEquals(1, rowCount);
  }

  @Test
  final void testBlackAndWhitelistViaTxn() throws Exception {
    CountDownLatch successLatch = new CountDownLatch(1);
    CountDownLatch blacklistLatch = new CountDownLatch(1);
    LatchListener latchListener = new LatchListener(successLatch, blacklistLatch);
    AtomicInteger attempts = new AtomicInteger();
    TransactionOutbox outbox =
        builder()
            .instantiator(new FailingInstantiator(attempts))
            .attemptFrequency(Duration.ofMillis(500))
            .listener(latchListener)
            .blacklistAfterAttempts(2)
            .build();
    Utils.join(cleanDataStore());

    withRunningFlusher(
        outbox,
        () ->
            Utils.join(
                txManager
                    .transactionally(tx -> scheduleWithTx(outbox, tx, 3, "Whee"))
                    .thenRun(() -> assertFired(blacklistLatch))
                    .thenCompose(
                        __ ->
                            txManager.transactionally(
                                tx ->
                                    outbox.whitelistAsync(
                                        latchListener.getBlacklisted().getId(), tx)))
                    .thenRun(() -> assertFired(successLatch))));
  }

  @Test
  final void testBlackAndWhitelistViaContext() throws Exception {
    CountDownLatch successLatch = new CountDownLatch(1);
    CountDownLatch blacklistLatch = new CountDownLatch(1);
    LatchListener latchListener = new LatchListener(successLatch, blacklistLatch);
    AtomicInteger attempts = new AtomicInteger();
    TransactionOutbox outbox =
        builder()
            .instantiator(new FailingInstantiator(attempts))
            .attemptFrequency(Duration.ofMillis(500))
            .listener(latchListener)
            .blacklistAfterAttempts(2)
            .build();
    Utils.join(cleanDataStore());

    withRunningFlusher(
        outbox,
        () ->
            Utils.join(
                txManager
                    .transactionally(tx -> scheduleWithCtx(outbox, tx.context(), 3, "Whee"))
                    .thenRun(() -> assertFired(blacklistLatch))
                    .thenCompose(
                        __ ->
                            txManager.transactionally(
                                tx ->
                                    outbox.whitelistAsync(
                                        latchListener.getBlacklisted().getId(),
                                        (Object) tx.context())))
                    .thenRun(() -> assertFired(successLatch))));
  }

  /** Hammers high-volume, frequently failing tasks to ensure that they all get run. */
  @Test
  final void testHighVolumeUnreliableAsync() throws Exception {
    int count = 10;

    CountDownLatch latch = new CountDownLatch(count * 10);
    ConcurrentHashMap<Integer, Integer> results = new ConcurrentHashMap<>();
    ConcurrentHashMap<Integer, Integer> duplicates = new ConcurrentHashMap<>();

    TransactionOutbox outbox =
        builder()
            .instantiator(new RandomFailingInstantiator())
            .submitter(Submitter.withExecutor(unreliablePool))
            .attemptFrequency(Duration.ofMillis(500))
            .flushBatchSize(1000)
            .listener(
                new TransactionOutboxListener() {
                  @Override
                  public void success(TransactionOutboxEntry entry) {
                    Integer i = (Integer) entry.getInvocation().getArgs()[0];
                    if (results.putIfAbsent(i, i) != null) {
                      duplicates.put(i, i);
                    }
                    latch.countDown();
                  }
                })
            .build();
    Utils.join(cleanDataStore());

    withRunningFlusher(
        outbox,
        () -> {
          IntStream.range(0, count)
              .parallel()
              .forEach(
                  i ->
                      txManager.transactionally(
                          tx ->
                              CompletableFuture.allOf(
                                  IntStream.range(0, 10)
                                      .mapToObj(
                                          j ->
                                              scheduleWithCtx(
                                                  outbox, tx.context(), i * 10 + j, "Whee"))
                                      .toArray(CompletableFuture[]::new))));
          assertFired(latch, 30);
        });

    assertThat(
        "Should never get duplicates running to full completion", duplicates.keySet(), empty());
    assertThat(
        "Only got: " + results.keySet(),
        results.keySet(),
        containsInAnyOrder(IntStream.range(0, count * 10).boxed().toArray()));
  }

  protected void withRunningFlusher(TransactionOutbox outbox, ThrowingRunnable runnable)
      throws Exception {
    Disposable background =
        Flux.interval(Duration.ofMillis(250))
            .flatMap(__ -> Mono.fromFuture(outbox::flushAsync))
            .doOnError(e -> log.error("Error in poller", e))
            .subscribe();
    try {
      runnable.run();
    } finally {
      background.dispose();
    }
  }

  protected void assertFired(CountDownLatch latch) {
    assertFired(latch, 6);
  }

  protected void assertFired(CountDownLatch latch, int seconds) {
    try {
      assertTrue(latch.await(seconds, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  protected void assertNotFired(CountDownLatch latch) {
    try {
      assertFalse(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      fail("Interrupted");
    }
  }
}