package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryNightlyWindowTest {

    @Test
    void successfulFinishedInputsAdvanceToExplicitUpperBound() {
        MemoryNightlyWindow.Selection selection = MemoryNightlyWindow.select(100, 200,
                List.of(input("done", 150, false), input("old", 100, false),
                        input("future", 201, false)));

        MemoryNightlyWindow.Transition transition = MemoryNightlyWindow.complete(
                selection, report(true));

        assertEquals(1, selection.inputs().size());
        assertEquals(200, transition.nextSince());
        assertTrue(transition.successful());
    }

    @Test
    void failedAnalysisOrOrganizationKeepsTheSameWindow() {
        MemoryNightlyWindow.Selection selection = MemoryNightlyWindow.select(100, 200,
                List.of(input("done", 150, false)));

        assertEquals(100, MemoryNightlyWindow.complete(selection, report(false)).nextSince());
        MemoryDreamingWorkflow.Report organizerFailure = new MemoryDreamingWorkflow.Report(
                1, 1, 1, true, "boom", List.of(), false);
        assertEquals(100, MemoryNightlyWindow.complete(selection, organizerFailure).nextSince());
    }

    @Test
    void runningInputIsSkippedAndNotBackfilledByLaterSuccess() {
        MemoryNightlyWindow.Selection first = MemoryNightlyWindow.select(100, 200, List.of(
                input("running", 150, true), input("done", 160, false)));

        MemoryNightlyWindow.Transition completed = MemoryNightlyWindow.complete(first, report(true));
        MemoryNightlyWindow.Selection next = MemoryNightlyWindow.select(completed.nextSince(), 300,
                List.of(input("running-finished-later", 150, false),
                        input("new", 250, false)));

        assertEquals(List.of("running"), first.skippedInputIds());
        assertEquals(200, completed.nextSince());
        assertEquals(List.of("new"), next.inputs().stream()
                .map(MemoryDreamingWorkflow.Input::inputId).toList());
    }

    @Test
    void emptyWindowCannotTurnIntoSuccessfulProgress() {
        MemoryNightlyWindow.Selection selection = MemoryNightlyWindow.select(100, 200, List.of());
        MemoryDreamingWorkflow.Report emptyReport = new MemoryDreamingWorkflow.Report(
                0, 0, 0, false, null, List.of(), false);

        MemoryNightlyWindow.Transition transition = MemoryNightlyWindow.complete(selection, emptyReport);

        assertFalse(transition.successful());
        assertEquals(100, transition.nextSince());
    }

    private static MemoryNightlyWindow.Input input(String id, long endedAt, boolean running) {
        return new MemoryNightlyWindow.Input(id, endedAt, running,
                new MemoryTranscript(List.of("fact-" + id)));
    }

    private static MemoryDreamingWorkflow.Report report(boolean successful) {
        return new MemoryDreamingWorkflow.Report(1, successful ? 1 : 0, 1,
                successful, null, successful ? List.of() : List.of(
                new MemoryDreamingWorkflow.Failure("input", "boom")), successful);
    }
}
