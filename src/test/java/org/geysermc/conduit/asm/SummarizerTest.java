package org.geysermc.conduit.asm;

import org.geysermc.conduit.ConduitContext;
import org.geysermc.conduit.Summary;
import org.geysermc.conduit.Value;
import org.geysermc.conduit.VirtualMachine;
import org.geysermc.conduit.VirtualMachineFactory;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizerTest {
    private static final String DASHBOARD = "org.geysermc.conduit.asm.FakeDashboard";

    private final VirtualMachine vm = VirtualMachineFactory.create(context()).await();
    private final Summary update = this.vm.summarize(DASHBOARD, "update", null);

    @Test
    void straightLineArithmetic() {
        assertEquals("(arg0.speed * 2.0)", this.assigned("this.needle", "x"));
    }

    @Test
    void loopOverArrayFieldUnrollsWithConstructedLength() {
        for (int i = 0; i < 4; i++) {
            assertEquals("(Math.cos((arg0.time + " + i + ".0)) * arg0.speed)", this.assigned("this.lights[" + i + "]", "x"));
        }
        assertNull(this.find("this.lights[4]", "x"));
    }

    @Test
    void enhancedForOverArrayField() {
        assertEquals("arg0.paused", this.assigned("this.badges[0]", "visible"));
        assertEquals("arg0.paused", this.assigned("this.badges[1]", "visible"));
    }

    @Test
    void branchesMergeIntoConditionalWithReadAfterWrite() {
        assertEquals("((arg0.paused != 0) ? 1.0 : (arg0.speed * 2.0))", this.assigned("this.label", "x"));
    }

    @Test
    void switchKeepsEarlierValueOnUnmatchedArm() {
        assertEquals("((arg0.mode == 1) ? 1.0 : ((arg0.mode == 0) ? 0.5 : this.needle.y))", this.assigned("this.needle", "y"));
    }

    @Test
    void privateHelperIsInlined() {
        assertEquals("((arg0.mode != 2) ? 1 : 0)", this.assigned("this.label", "visible"));
        assertTrue(this.update.invocations().isEmpty(), this.update.invocations()::toString);
    }

    @Test
    void earlyReturnGuardsLaterAssignments() {
        assertEquals("((arg0.speed < 0.1) ? this.label.y : 2.0)", this.assigned("this.label", "y"));
    }

    @Test
    void constructorResolvesArrayElements() {
        Summary constructor = this.vm.summarize(DASHBOARD, "<init>", null);
        assertNotNull(constructor);
        assertEquals("[arg0.find(\"light0\"), arg0.find(\"light1\"), arg0.find(\"light2\"), arg0.find(\"light3\")]",
                String.valueOf(constructor.assigned(new Value.This(), "lights")));
        assertEquals(1, constructor.invocations().size());
    }

    @Test
    void staticArraysSizeAndFillArrays() {
        Summary constructor = this.vm.summarize(DASHBOARD, "<init>", null);
        assertNotNull(constructor);
        assertEquals("[arg0.find(\"dial0\"), arg0.find(\"dial1\"), arg0.find(\"dial2\")]",
                String.valueOf(constructor.assigned(new Value.This(), "dials")));
    }

    @Test
    void constructorCallSubstitutesItsArguments() {
        Value panel = new Value.Constructed("org/geysermc/conduit/asm/FakePanel", List.of());
        Summary constructor = this.vm.summarize(new Value.Constructed("org/geysermc/conduit/asm/FakeDashboard", List.of(panel)));
        assertNotNull(constructor);
        assertEquals("new FakePanel().find(\"needle\")", String.valueOf(constructor.assigned(new Value.This(), "needle")));
    }

    @Test
    void wideValuesKeepTheirSlots() {
        Summary widths = this.vm.summarize(DASHBOARD, "widths", null);
        assertNotNull(widths);
        assertEquals("((arg0 * arg1) + (arg0 * arg1))", String.valueOf(widths.returned()));
    }

    @Test
    void callsOnArgumentsAreKeptAndTypeTestsAreBooleans() {
        Summary read = this.vm.summarize("org.geysermc.conduit.asm.FakeReader", "read", null);
        assertNotNull(read);
        Value input = new Value.Parameter(1);
        assertEquals("((arg0 instanceof FakePausableSource && (arg0.isPaused() != 0)) ? 1 : 0)", String.valueOf(read.assigned(input, "paused")));
        assertEquals("arg0.getMode()", String.valueOf(read.assigned(input, "mode")));
    }

    @Test
    void concatenationKeepsItsUnknownParts() {
        Summary icon = this.vm.summarize(DASHBOARD, "icon", null);
        assertNotNull(icon);
        assertEquals("(\"icons/mode_\" + arg0.mode + ((arg0.paused != 0) ? \"_paused\" : \"\") + \".png\")",
                String.valueOf(icon.returned()));
    }

    @Test
    void opaqueClassesAreNotFollowed() {
        assertEquals("(arg0.speed / 2.0)", String.valueOf(this.vm.summarize(DASHBOARD, "halved", null).returned()));

        VirtualMachine opaque = VirtualMachineFactory.create(context()).await().opaque(DASHBOARD);
        assertEquals("FakeDashboard.half(arg0.speed)", String.valueOf(opaque.summarize(DASHBOARD, "halved", null).returned()));
    }

    @Test
    void missingMethodIsNull() {
        assertNull(this.vm.summarize(DASHBOARD, "missing", null));
    }

    private String assigned(String target, String field) {
        Summary.Assignment assignment = this.find(target, field);
        assertNotNull(assignment, () -> target + "." + field + " not assigned in " + this.update.assignments());
        return String.valueOf(assignment.value());
    }

    private Summary.Assignment find(String target, String field) {
        List<Summary.Assignment> matches = this.update.assignments().stream()
                .filter(assignment -> String.valueOf(assignment.target()).equals(target) && assignment.field().equals(field))
                .toList();
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static ConduitContext context() {
        try {
            return ConduitContext.load(Path.of(FakeDashboard.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
