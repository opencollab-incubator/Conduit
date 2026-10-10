package org.geysermc.conduit;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureScopeTest {
    @Test
    void listenerStopsAtEndOfRun() {
        CaptureChannel<String> channel = CaptureChannel.create();

        List<String> first = new ArrayList<>();
        VirtualMachine a = VirtualMachineFactory.create(ConduitContext.load(new Path[0])).await();
        a.capture(channel, first::add);
        a.execute();

        List<String> second = new ArrayList<>();
        VirtualMachine b = VirtualMachineFactory.create(ConduitContext.load(new Path[0])).await();
        b.capture(channel, second::add);
        channel.emit("from-b");
        b.execute();

        channel.emit("after-everything");

        assertTrue(first.isEmpty(), "first run kept listening after it finished");
        assertEquals(List.of("from-b"), second);
    }
}
