/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.async.perftest;

import org.HdrHistogram.Histogram;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.async.DefaultAsyncEventRouter;
import org.apache.logging.log4j.core.async.EventRoute;
import org.apache.logging.log4j.core.util.Constants;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Latency test showing both service time and response time.
 * <p>Service time = time to perform the desired operation, response time = service time + queueing time.</p>
 */
// RUN
// java -XX:+UnlockDiagnosticVMOptions -verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintTenuringDistribution
// -XX:+PrintGCApplicationConcurrentTime -XX:+PrintGCApplicationStoppedTime -XX:GuaranteedSafepointInterval=500000
// -XX:CompileCommand=dontinline,org.apache.logging.log4j.core.async.perftest.NoOpIdleStrategy::idle
// -cp HdrHistogram-2.1.8.jar:disruptor-3.3.4.jar:log4j-api-2.6-SNAPSHOT.jar:log4j-core-2.6-SNAPSHOT.jar:log4j-core-2.6-SNAPSHOT-tests.jar
// -DAsyncLogger.WaitStrategy=busyspin -DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
// -Dlog4j2.enable.threadlocals=true -Dlog4j2.enable.direct.encoders=true
//  -Xms1G -Xmx1G org.apache.logging.log4j.core.async.perftest.ResponseTimeTest 1 100000
//
// RUN recording in Java Flight Recorder:
// %JAVA_HOME%\bin\java -XX:+UnlockCommercialFeatures -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+FlightRecorder -XX:StartFlightRecording=duration=10m,filename=replayStats-2.6-latency.jfr -verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintTenuringDistribution -XX:+PrintGCApplicationConcurrentTime -XX:+PrintGCApplicationStoppedTime -XX:CompileCommand=dontinline,org.apache.logging.log4j.core.async.perftest.NoOpIdleStrategy::idle -DAsyncLogger.WaitStrategy=yield  -Dorg.apache.logging.log4j.simplelog.StatusLogger.level=TRACE -cp .;HdrHistogram-2.1.8.jar;disruptor-3.3.4.jar;log4j-api-2.6-SNAPSHOT.jar;log4j-core-2.6-SNAPSHOT.jar;log4j-core-2.6-SNAPSHOT-tests.jar org.apache.logging.log4j.core.async.perftest.ResponseTimeTest 1 50000
public class ResponseTimeTest {
    private static final String LATENCY_MSG = new String(new char[64]);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Please specify thread count and target throughput (msg/sec)");
            return;
        }
        final int threadCount = Integer.parseInt(args[0]);
        final double loadMessagesPerSec = Double.parseDouble(args[1]);

        // print to console if ringbuffer is full
        System.setProperty("log4j2.AsyncEventRouter", PrintingDefaultAsyncEventRouter.class.getName());
        System.setProperty("AsyncLogger.RingBufferSize", String.valueOf(256 * 1024));
        //System.setProperty("Log4jContextSelector", AsyncLoggerContextSelector.class.getName());
        System.setProperty("log4j.configurationFile", "perf3PlainNoLoc.xml");
        if (System.getProperty("AsyncLogger.WaitStrategy") == null) {
            System.setProperty("AsyncLogger.WaitStrategy", "Yield");
        }

        Logger logger = LogManager.getLogger();
        logger.info("Starting..."); // initializes Log4j
        Thread.sleep(100);

        final int requiredProcessors = threadCount + 1 + 1; // producers + 1 consumer + 1 for OS
        final IdleStrategy idleStrategy = Runtime.getRuntime().availableProcessors() > requiredProcessors
                ? new NoOpIdleStrategy()
                : new YieldIdleStrategy();

        System.out.printf("%d threads, load is %,f msg/sec, using %s%n", threadCount, loadMessagesPerSec,
                idleStrategy.getClass().getSimpleName());

        // Warmup: run as many iterations of 50,000 calls to logger.log as we can in 1 minute
        final long WARMUP_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(1);
        List<Histogram> warmupServiceTmHistograms = new ArrayList<>(threadCount);
        List<Histogram> warmupResponseTmHistograms = new ArrayList<>(threadCount);

        final int WARMUP_COUNT = 50000 / threadCount;
        final CountDownLatch warmupLatch = new CountDownLatch(threadCount + 1);
        Thread[] warmup = createLatencyTest(logger, WARMUP_DURATION_MILLIS, WARMUP_COUNT, warmupLatch, loadMessagesPerSec,
                idleStrategy, warmupServiceTmHistograms, warmupResponseTmHistograms, threadCount);

        List<Histogram> serviceTmHistograms = new ArrayList<>(threadCount);
        List<Histogram> responseTmHistograms = new ArrayList<>(threadCount);

        final long TEST_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(4);
        final int COUNT = (1000 * 1000) / threadCount;
        final CountDownLatch actualLatch = new CountDownLatch(threadCount + 1);
        Thread[] actual = createLatencyTest(logger, TEST_DURATION_MILLIS, COUNT, actualLatch, loadMessagesPerSec, idleStrategy,
                serviceTmHistograms, responseTmHistograms, threadCount);

        warmupLatch.countDown();
        await(warmup);
        System.out.println("Warmup done.");
        if (!Constants.ENABLE_DIRECT_ENCODERS || !Constants.ENABLE_THREADLOCALS) {
            System.gc();
            Thread.sleep(5000);
        }
        System.out.println("Starting measured run.");

        // Actual test: run as many iterations of 5,000,000 calls to logger.log as we can in 4 minutes.
        long start = System.currentTimeMillis();
        actualLatch.countDown(); // start the actual test threads
        await(actual);
        long end = System.currentTimeMillis();

        // ... and report the results
        final Histogram resultServiceTm = createResultHistogram(serviceTmHistograms, start, end);
        resultServiceTm.outputPercentileDistribution(System.out, 1000.0);
        writeToFile("s", resultServiceTm, (int) (loadMessagesPerSec / 1000), 1000.0);

        final Histogram resultResponseTm = createResultHistogram(responseTmHistograms, start, end);
        resultResponseTm.outputPercentileDistribution(System.out, 1000.0);
        writeToFile("r", resultResponseTm, (int) (loadMessagesPerSec / 1000), 1000.0);

        System.out.println("Test duration: " + (end - start) / 1000.0 + " seconds");
    }

    private static void writeToFile(final String suffix, final Histogram hist, final int thousandMsgPerSec,
            final double scale) throws IOException {
        try (PrintStream pout = new PrintStream(new FileOutputStream(thousandMsgPerSec + "k" + suffix))) {
            hist.outputPercentileDistribution(pout, scale);
        }
    }

    private static Histogram createResultHistogram(final List<Histogram> list, final long start, final long end) {
        final Histogram result = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        result.setStartTimeStamp(start);
        result.setEndTimeStamp(end);
        for (final Histogram hist : list) {
            result.add(hist);
        }
        return result;
    }

    public static Thread[] createLatencyTest(final Logger logger, final long durationMillis, final int samples,
            final CountDownLatch latch, final double loadMessagesPerSec, final IdleStrategy idleStrategy,
            final List<Histogram> serviceTmHistograms, final List<Histogram> responseTmHistograms,
            final int threadCount) throws InterruptedException {

        final Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final Histogram serviceTmHist = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
            final Histogram responseTmHist = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
            serviceTmHistograms.add(serviceTmHist);
            responseTmHistograms.add(responseTmHist);

            final Pacer pacer = new Pacer(loadMessagesPerSec, idleStrategy);
            threads[i] = new Thread("latencytest-" + i) {
                @Override
                public void run() {
                    latch.countDown();
                    try {
                        latch.await(); // wait until all threads are ready to go
                    } catch (InterruptedException e) {
                        interrupt();
                        return;
                    }
                    final long endTimeMillis = System.currentTimeMillis() + durationMillis;
                    do {
                        runLatencyTest(samples, logger, serviceTmHist, responseTmHist, pacer);
                    } while (System.currentTimeMillis() < endTimeMillis);
                }
            };
            threads[i].start();
        }
        return threads;
    }

    private static void await(final Thread[] threads) throws InterruptedException {
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }
    }

    private static void runLatencyTest(final int samples, final Logger logger, final Histogram serviceTmHist,
            final Histogram responseTmHist, final Pacer pacer) {

        pacer.setInitialStartTime(System.nanoTime());
        for (int i = 0; i < samples; i++) {
            final long expectedStartTimeNanos = pacer.expectedNextOperationNanoTime();
            pacer.acquire(1);
            final long actualStartTime = System.nanoTime();
            logger.info(LATENCY_MSG);
            final long doneTime = System.nanoTime();
            serviceTmHist.recordValue(doneTime - actualStartTime);
            responseTmHist.recordValue(doneTime - expectedStartTimeNanos);
        }
    }

    public static class PrintingDefaultAsyncEventRouter extends DefaultAsyncEventRouter {
        @Override
        public EventRoute getRoute(long backgroundThreadId, Level level) {
            System.out.print('!');
            return super.getRoute(backgroundThreadId, level);
        }
    }

    /**
     * Pacer determines the pace at which measurements are taken. Sample usage:
     *
     * <pre>
     * - each thread has a Pacer instance
     * - at start of test, call pacer.setInitialStartTime(System.nanoTime());
     * - loop:
     *   - store result of pacer.expectedNextOperationNanoTime() as expectedStartTime
     *   - pacer.acquire(1);
     *   - before the measured operation: store System.nanoTime() as actualStartTime
     *   - perform the measured operation
     *   - store System.nanoTime() as doneTime
     *   - serviceTimeHistogram.recordValue(doneTime - actualStartTime);
     *   - responseTimeHistogram.recordValue(doneTime - expectedStartTime);
     * </pre>
     * <p>
     * Borrowed with permission from Gil Tene's Cassandra stress test:
     * https://github.com/LatencyUtils/cassandra-stress2/blob/trunk/tools/stress/src/org/apache/cassandra/stress/StressAction.java#L374
     * </p>
     */
    static class Pacer {
        private long initialStartTime;
        private double throughputInUnitsPerNsec;
        private long unitsCompleted;

        private boolean caughtUp = true;
        private long catchUpStartTime;
        private long unitsCompletedAtCatchUpStart;
        private double catchUpThroughputInUnitsPerNsec;
        private double catchUpRateMultiple;
        private IdleStrategy idleStrategy;

        public Pacer(double unitsPerSec, IdleStrategy idleStrategy) {
            this(unitsPerSec, 3.0, idleStrategy); // Default to catching up at 3x the set throughput
        }

        public Pacer(double unitsPerSec, double catchUpRateMultiple, IdleStrategy idleStrategy) {
            this.idleStrategy = idleStrategy;
            setThroughout(unitsPerSec);
            setCatchupRateMultiple(catchUpRateMultiple);
            initialStartTime = System.nanoTime();
        }

        public void setInitialStartTime(long initialStartTime) {
            this.initialStartTime = initialStartTime;
        }

        public void setThroughout(double unitsPerSec) {
            throughputInUnitsPerNsec = unitsPerSec / 1000000000.0;
            catchUpThroughputInUnitsPerNsec = catchUpRateMultiple * throughputInUnitsPerNsec;
        }

        public void setCatchupRateMultiple(double multiple) {
            catchUpRateMultiple = multiple;
            catchUpThroughputInUnitsPerNsec = catchUpRateMultiple * throughputInUnitsPerNsec;
        }

        /**
         * @return the time for the next operation
         */
        public long expectedNextOperationNanoTime() {
            return initialStartTime + (long) (unitsCompleted / throughputInUnitsPerNsec);
        }

        public long nsecToNextOperation() {

            long now = System.nanoTime();

            long nextStartTime = expectedNextOperationNanoTime();

            boolean sendNow = true;

            if (nextStartTime > now) {
                // We are on pace. Indicate caught_up and don't send now.}
                caughtUp = true;
                sendNow = false;
            } else {
                // We are behind
                if (caughtUp) {
                    // This is the first fall-behind since we were last caught up
                    caughtUp = false;
                    catchUpStartTime = now;
                    unitsCompletedAtCatchUpStart = unitsCompleted;
                }

                // Figure out if it's time to send, per catch up throughput:
                long unitsCompletedSinceCatchUpStart =
                        unitsCompleted - unitsCompletedAtCatchUpStart;

                nextStartTime = catchUpStartTime +
                        (long) (unitsCompletedSinceCatchUpStart / catchUpThroughputInUnitsPerNsec);

                if (nextStartTime > now) {
                    // Not yet time to send, even at catch-up throughout:
                    sendNow = false;
                }
            }

            return sendNow ? 0 : (nextStartTime - now);
        }

        /**
         * Will wait for next operation time. After this the expectedNextOperationNanoTime() will move forward.
         * @param unitCount
         */
        public void acquire(long unitCount) {
            long nsecToNextOperation = nsecToNextOperation();
            if (nsecToNextOperation > 0) {
                sleepNs(nsecToNextOperation);
            }
            unitsCompleted += unitCount;
        }

        private void sleepNs(long ns) {
            long now = System.nanoTime();
            long deadline = now + ns;
            while ((now = System.nanoTime()) < deadline) {
                idleStrategy.idle();
            }
        }
    }
}
