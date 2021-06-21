package io.jenkins.plugins.remotingopentelemetry.engine;

import io.jenkins.plugins.remotingopentelemetry.engine.listener.RootListener;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Controls collecting and providing telemetry data.
 * One agent cannot run more than one MonitoringEngine thread.
 */
public final class MonitoringEngine extends Thread {
    /**
     * Launches the Monitoring engine.
     * The current engine will be killed if exists.
     * @throws InterruptedException
     */
    static public synchronized void launch(EngineConfiguration config) throws InterruptedException {
        Thread current = MonitoringEngine.current();
        if (current != null) {
            current.interrupt();
            current.join();
        }
        current = new MonitoringEngine(config);
        current.start();
    }

    /**
     * Terminates the current Monitoring engine if exists.
     * @throws InterruptedException
     */
    static public void terminate() throws InterruptedException {
        Thread current = MonitoringEngine.current();
        if (current == null) return;
        current.interrupt();
        current.join();
    }

    /**
     * @return {@code true} if {@link MonitoringEngine} is running.
     */
    static public boolean isRunning() {
        return current() != null;
    }

    /**
     * Returns running {@link MonitoringEngine}.
     *
     * We cannot cast the return value to {@link MonitoringEngine} because
     * the classloader of running {@link MonitoringEngine} can be different
     * from that of {@link MonitoringEngine} we want to cast into.
     * @return null if there is no running {@link MonitoringEngine}
     */
    @Nullable
    static private Thread current() {
        ThreadGroup currentThreadGroup = Thread.currentThread().getThreadGroup();
        Thread[] siblingThreads = new Thread[currentThreadGroup.activeCount()];
        currentThreadGroup.enumerate(siblingThreads);
        Thread[] monitoringThreads =  Stream.of(siblingThreads)
                .filter(MonitoringEngine::isMonitoringEngine)
                .toArray(Thread[]::new);
        if (monitoringThreads.length < 1)  return null;
        return monitoringThreads[0];
    }

    /**
     * @param thread thread to check
     * @return {@code true} if given thread is {@link MonitoringEngine}
     */
    static private boolean isMonitoringEngine(Thread thread){
        // Checked by the canonical name in case the different classloaders are used.
        return Objects.equals(
                thread.getClass().getCanonicalName(),
                MonitoringEngine.class.getCanonicalName()
        );
    }

    @Nonnull
    private final String THREAD_NAME = "Monitoring Engine";

    @Nonnull
    private final RootListener rootListener = new RootListener();

    /**
     * Disable the instantiation outside this class.
     */
    private MonitoringEngine (EngineConfiguration config) {
        setDaemon(true);
        setName(THREAD_NAME);

        SpanExporter exporter = RemotingSpanExporterProvider.create(config);
        Resource resource = RemotingResourceProvider.create();
        SpanProcessor processor = SimpleSpanProcessor.create(exporter);
        OpenTelemetryProxy.build(processor, resource);
    }

    @Override
    public void start() {
        rootListener.preStartMonitoringEngine();
        super.start();
    }

    @Override
    public void run() {
        Exception exception = null;
        try {
            block();
        } catch (InterruptedException e) {
            exception = e;
        } finally {
            rootListener.onTerminateMonitoringEngine(exception);
            OpenTelemetryProxy.clean();
        }
    }

    /**
     * Blocks the current thread forever
     * @throws InterruptedException
     */
    private void block() throws InterruptedException {
        while(true) {
            Thread.sleep(Long.MAX_VALUE); // 292 million years
        }
    }
}