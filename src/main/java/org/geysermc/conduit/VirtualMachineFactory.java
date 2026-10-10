package org.geysermc.conduit;

import java.util.concurrent.CompletableFuture;

/**
 * Creates a {@link VirtualMachine} over a {@link ConduitContext}.
 */
public class VirtualMachineFactory {
    private final VirtualMachine vm;

    private VirtualMachineFactory(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Returns the machine, ready to have handlers registered.
     *
     * @return the machine
     */
    public VirtualMachine await() {
        return this.vm;
    }

    /**
     * Returns the machine as an already completed future.
     *
     * @return a future completing with the machine
     */
    public CompletableFuture<VirtualMachine> awaitUninterruptibly() {
        return CompletableFuture.completedFuture(this.vm);
    }

    /**
     * Creates a factory for a machine that scans the given context.
     *
     * @param context the jars to scan
     * @return a factory for a machine over the context
     */
    public static VirtualMachineFactory create(ConduitContext context) {
        return new VirtualMachineFactory(new VirtualMachine(context));
    }
}
