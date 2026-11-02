package com.clipforge.core.util;

/** Exposes package-private {@link DiskCheck} helpers to tests in other packages. */
public final class DiskCheckTestAccess {

    private DiskCheckTestAccess() {
    }

    public static boolean isFat(String type) {
        return DiskCheck.isFat(type);
    }
}
