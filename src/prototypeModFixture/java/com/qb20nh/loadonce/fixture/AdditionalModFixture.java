package com.qb20nh.loadonce.fixture;

import java.util.UUID;

/** Independent optional mod used to observe fresh initialization after restore. */
public final class AdditionalModFixture {
    private static final UUID identity = UUID.randomUUID();
    private static int calls;

    private AdditionalModFixture() { }

    public static void initialize() {
        if (++calls != 1) throw new IllegalStateException("Additional mod initialized more than once");
        String mode = System.getProperty("loadonce.prototype.boot");
        if (!("cold".equals(mode) || "warm".equals(mode))) {
            throw new IllegalStateException("Additional mod executed before the prototype boot boundary");
        }
        System.out.println("LOADONCE_ADDITIONAL_MOD_INIT identity=" + identity + " mode=" + mode
                + " calls=" + calls + " pid=" + ProcessHandle.current().pid());
    }
}
