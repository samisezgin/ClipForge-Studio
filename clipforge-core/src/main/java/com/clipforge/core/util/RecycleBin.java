package com.clipforge.core.util;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Moves files to the Windows Recycle Bin with the shell's own delete operation.
 * <p>
 * {@code java.awt.Desktop.moveToTrash} is not used: on drives without a Recycle Bin (USB sticks) or for files too
 * large for it, the shell would delete permanently without asking. Here the "nuke warning" is requested, so Windows
 * asks before anything is deleted for good; if the user declines, the file stays.
 */
public final class RecycleBin {

    private static final int FO_DELETE = 3;
    private static final short FOF_SILENT = 0x0004;
    private static final short FOF_NOCONFIRMATION = 0x0010;
    private static final short FOF_ALLOWUNDO = 0x0040;
    private static final short FOF_NOERRORUI = 0x0400;
    private static final short FOF_WANTNUKEWARNING = 0x4000;

    // SHFILEOPSTRUCTW on 64-bit Windows (natural alignment)
    private static final long SIZE = 56;
    private static final long OFF_FUNC = 8;
    private static final long OFF_FROM = 16;
    private static final long OFF_FLAGS = 32;
    private static final long OFF_ABORTED = 36;

    private RecycleBin() {
    }

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                && "amd64".equals(System.getProperty("os.arch"));
    }

    /**
     * @return {@code true} if the file is gone (in the Recycle Bin); {@code false} if it is still there, e.g. because
     * the user declined a permanent deletion
     * @throws IOException if the Recycle Bin is not available on this system or the shell reports an error
     */
    public static boolean moveToRecycleBin(Path file) throws IOException {
        if (!isSupported()) {
            throw new IOException("The Recycle Bin is not available on this system");
        }
        Path abs = file.toAbsolutePath().normalize();
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32", arena);
            MethodHandle op = linker.downcallHandle(shell32.find("SHFileOperationW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            // the list of paths ends with an extra NUL; allocateFrom adds the final one
            MemorySegment from = arena.allocateFrom(abs + "\0", StandardCharsets.UTF_16LE);
            MemorySegment s = arena.allocate(SIZE, 8); // zero-initialised: no window, no target, no title
            s.set(ValueLayout.JAVA_INT, OFF_FUNC, FO_DELETE);
            s.set(ValueLayout.ADDRESS, OFF_FROM, from);
            s.set(ValueLayout.JAVA_SHORT, OFF_FLAGS, (short) (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT
                    | FOF_NOERRORUI | FOF_WANTNUKEWARNING));
            int rc = (int) op.invokeExact(s);
            boolean aborted = s.get(ValueLayout.JAVA_INT, OFF_ABORTED) != 0;
            if (rc != 0 && !aborted) {
                throw new IOException("Moving to the Recycle Bin failed (code 0x" + Integer.toHexString(rc) + ")");
            }
            return !aborted && !Files.exists(abs);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Moving to the Recycle Bin failed: " + t, t);
        }
    }
}
