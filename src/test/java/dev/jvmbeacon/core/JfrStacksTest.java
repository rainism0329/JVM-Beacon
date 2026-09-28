package dev.jvmbeacon.core;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static dev.jvmbeacon.core.JfrStacks.*;

class JfrStacksTest {
    @TempDir Path directory;
    private final SampledThread one = new SampledThread(1, 42, "worker"), two = new SampledThread(2, 42, "worker");
    private static Frame frame(long id, String name) { return new Frame(id, "fixture.Work", name, "()V", 12); }
    private Sample sample(Kind kind, SampledThread thread, boolean truncated, Frame... frames) {
        return new Sample(kind, thread, Instant.parse("2026-09-28T00:00:00Z"), List.of(frames), truncated);
    }
    private Data data(Sample... samples) { return new Data(List.of(samples), Map.of(Kind.JAVA, new Counts(9, 2, 3, 1)), true); }

    @Test void recursionInclusiveSelfAndThreadKindsStayDistinct() throws Exception {
        Frame root = frame(1, "root"), work = frame(1, "work");
        Data data = data(sample(Kind.JAVA, one, false, work, work, root), sample(Kind.JAVA, one, false, root),
                sample(Kind.NATIVE, one, false, root), sample(Kind.JAVA, two, false, root));
        View view = aggregate(data, Kind.JAVA, one);
        assertEquals(2, view.root().inclusive());
        Node r = view.root().children().getFirst();
        assertEquals(2, r.inclusive()); assertEquals(1, r.self());
        assertEquals(1, r.children().getFirst().children().getFirst().self());
        assertEquals(0, r.children().getFirst().self());
        assertEquals(1, aggregate(data, Kind.NATIVE, null).root().inclusive());
        assertEquals(2, data.threads(Kind.JAVA).size()); // Same Java ID/name, distinct recorded identities.
        assertTrue(view.text().contains("Missing/empty stacks: 2")); assertTrue(view.text().contains("PARTIAL"));
        conservation(view.root());
    }
    @Test void cappedTreeRejectsWholePathsAndDoesNotInventSelfSamples() throws Exception {
        Frame a = frame(1, "a"), b = frame(1, "b"), c = frame(1, "c");
        View view = aggregate(data(sample(Kind.JAVA, one, false, b, a), sample(Kind.JAVA, one, false, c, a),
                sample(Kind.JAVA, one, false, b, a)), Kind.JAVA, null, 3);
        assertEquals(3, view.selected()); assertEquals(1, view.omitted()); assertEquals(2, view.root().inclusive());
        assertEquals(0, view.root().children().getFirst().self()); conservation(view.root());
    }
    @Test void truncatedRootsDescriptorsAndClassIdsAreNotMerged() throws Exception {
        Frame a = frame(1, "run"), otherLoader = frame(2, "run"), overload = new Frame(1, a.className(), a.method(), "(I)V", 12);
        View view = aggregate(data(sample(Kind.JAVA, one, true, a), sample(Kind.JAVA, one, false, a),
                sample(Kind.JAVA, one, false, otherLoader), sample(Kind.JAVA, one, false, overload)), Kind.JAVA, null);
        assertEquals(4, view.root().children().size());
        Node truncated = view.root().children().stream().filter(n -> n.frame() == null).findFirst().orElseThrow();
        assertEquals("[older frames not captured]", truncated.label()); assertEquals(0, truncated.self()); conservation(view.root());
    }
    @Test void emptyAndInterruptedAreDifferentOutcomes() throws Exception {
        assertEquals(0, aggregate(data(), Kind.JAVA, null).root().inclusive());
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class, () -> aggregate(data(sample(Kind.JAVA, one, false, frame(1, "run"))), Kind.JAVA, null)); }
        finally { Thread.interrupted(); }
    }
    private RecordedEvent recorded(boolean stack, int depth) throws Exception {
        Path path = directory.resolve("stack-" + stack + "-" + depth + ".jfr");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx96m", "-cp",
                System.getProperty("beacon.fixture.classes"), "dev.jvmbeacon.fixture.StackRecordingFixture", path.toString(), Boolean.toString(stack), Integer.toString(depth))
                .redirectErrorStream(true).redirectOutput(directory.resolve("child-" + stack + "-" + depth + ".txt").toFile()).start();
        try {
            assertTrue(child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "Owned stack fixture timed out");
            assertEquals(0, child.exitValue());
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); }
        }
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) { var event = file.readEvent(); if (event.getEventType().getName().equals("beacon.StackConversionTest")) return event; }
        }
        throw new AssertionError("Expected controlled custom event");
    }
    @Test void realConsumerFrameOrderMissingStackAndProductionEventAllowlist() throws Exception {
        RecordedEvent event = recorded(true, 4);
        Builder builder = new Builder(); builder.accept(event);
        assertEquals(0, builder.finish(false).samples().size()); // Custom event is NOT a CPU sample.
        builder.acceptSample(Kind.JAVA, event); builder.acceptSample(Kind.JAVA, recorded(false, 0));
        Data data = builder.finish(false);
        Sample sample = data.samples().getFirst();
        assertEquals("emitAtDepth", sample.leafFirst().getFirst().method()); // Leaf-first verified with known nested call.
        assertEquals(2, data.counts().get(Kind.JAVA).observed()); assertEquals(1, data.counts().get(Kind.JAVA).missing());
        assertEquals(-1, sample.thread().recordedId()); // eventThread is deliberately not substituted for sampledThread.
        assertEquals(5, sample.leafFirst().stream().filter(f -> f.method().equals("emitAtDepth")).count());
        assertTrue(sample.leafFirst().getFirst().line() > 0);
    }
    @Test void retentionAndDepthHaveExplicitLossCounts() throws Exception {
        var event = recorded(true, 0);
        Builder builder = new Builder();
        for (int i = 0; i < MAX_SAMPLES + 3; i++) builder.acceptSample(Kind.JAVA, event);
        Data data = builder.finish(false);
        assertTrue(data.samples().size() <= MAX_SAMPLES);
        assertTrue(data.samples().stream().mapToInt(s -> s.leafFirst().size()).sum() <= MAX_FRAMES);
        assertEquals(MAX_SAMPLES + 3, data.counts().get(Kind.JAVA).observed());
        assertEquals(MAX_SAMPLES + 3 - data.samples().size(), data.counts().get(Kind.JAVA).omitted());
        Builder deep = new Builder(); deep.acceptSample(Kind.JAVA, recorded(true, 180));
        assertTrue(deep.finish(false).samples().getFirst().truncated());
        assertTrue(deep.finish(false).samples().getFirst().leafFirst().size() <= MAX_DEPTH);
    }
    private static void conservation(Node node) {
        assertEquals(node.inclusive(), node.self() + node.children().stream().mapToLong(Node::inclusive).sum());
        node.children().forEach(JfrStacksTest::conservation);
    }
}
