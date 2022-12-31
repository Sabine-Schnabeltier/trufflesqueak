/*
 * Copyright (c) 2017-2022 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2022 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Properties;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.shared.SqueakLanguageConfig;

public final class MiscUtils {

    // The delta between Squeak Epoch (January 1st 1901) and POSIX Epoch (January 1st 1970)
    public static final long EPOCH_DELTA_SECONDS = (69L * 365 + 17) * 24 * 3600;
    public static final long EPOCH_DELTA_MICROSECONDS = EPOCH_DELTA_SECONDS * 1000 * 1000;
    public static final long TIME_ZONE_OFFSET_MICROSECONDS = (Calendar.getInstance().get(Calendar.ZONE_OFFSET) + Calendar.getInstance().get(Calendar.DST_OFFSET)) * 1000L;
    public static final long TIME_ZONE_OFFSET_SECONDS = TIME_ZONE_OFFSET_MICROSECONDS / 1000 / 1000;

    public static final String[] GC_YOUNG_GEN_NAMES = {"G1 Young Generation", "PS Scavenge"};
    public static final String[] GC_OLD_GEN_NAMES = {"G1 Old Generation", "PS MarkSweep"};
    public static final String GC_EDEN_SPACE_SUFFIX = "Eden Space";
    public static final String GC_SURVIVOR_SPACE_SUFFIX = "Survivor Space";
    public static final String GC_OLD_GEN_SUFFIX = "Old Gen";

    @CompilationFinal private static volatile SecureRandom random;

    private MiscUtils() {
    }

    public static int bitSplit(final long value, final int offset, final int size) {
        return (int) (value >> offset & size - 1);
    }

    @TruffleBoundary
    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @TruffleBoundary
    public static String format(final String format, final Object... args) {
        return String.format(format, args);
    }

    @TruffleBoundary
    public static long getCollectionCount(final String[] names) {
        final GarbageCollectorMXBean mxBean = getGarbageCollectorMXBean(names);
        return mxBean != null ? mxBean.getCollectionCount() : 1L; // avoid division by zero
    }

    @TruffleBoundary
    public static long getCollectionTime(final String[] names) {
        final GarbageCollectorMXBean mxBean = getGarbageCollectorMXBean(names);
        return mxBean != null ? mxBean.getCollectionTime() : 1L; // avoid division by zero
    }

    @TruffleBoundary
    private static GarbageCollectorMXBean getGarbageCollectorMXBean(final String[] names) {
        for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (ArrayUtils.containsEqual(names, bean.getName())) {
                return bean;
            }
        }
        return null;
    }

    @TruffleBoundary
    public static long getMemoryPoolUsageCommitted(final String suffix) {
        final MemoryUsage usage = getUsage(suffix);
        return usage != null ? usage.getCommitted() : 1L; // avoid division by zero
    }

    @TruffleBoundary
    public static long getMemoryPoolUsageFree(final String suffix) {
        final MemoryUsage usage = getUsage(suffix);
        return usage != null ? usage.getCommitted() - usage.getUsed() : 1L;
    }

    @TruffleBoundary
    public static long getMemoryPoolUsageUsed(final String suffix) {
        final MemoryUsage usage = getUsage(suffix);
        return usage != null ? usage.getUsed() : 1L; // avoid division by zero
    }

    @TruffleBoundary
    private static MemoryUsage getUsage(final String suffix) {
        final MemoryPoolMXBean mxBean = getMemoryPoolMXBean(suffix);
        return mxBean == null ? null : mxBean.getUsage();
    }

    @TruffleBoundary
    private static MemoryPoolMXBean getMemoryPoolMXBean(final String suffix) {
        for (final MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
            if (bean.getName().endsWith(suffix)) {
                return bean;
            }
        }
        return null;
    }

    @TruffleBoundary
    public static String getGraalVMInformation() {
        final String graalVMVersion = System.getProperty("graalvm.version", "");
        if (graalVMVersion.isEmpty()) {
            return ""; // No information available; not running on GraalVM.
        }
        final String graalVMHome = System.getProperty("graalvm.home", "n/a");
        return String.format("GRAAL_VERSION=%s\nGRAAL_HOME=%s", graalVMVersion, graalVMHome);
    }

    @TruffleBoundary
    public static long getHeapMemoryMax() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }

    @TruffleBoundary
    public static long getHeapMemoryUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    @TruffleBoundary
    public static String getJavaClassPath() {
        return System.getProperty("java.class.path");
    }

    @TruffleBoundary
    public static String getJavaHome() {
        return System.getProperty("java.home");
    }

    public static SecureRandom getSecureRandom() {
        /* SecureRandom must be initialized at (native image) runtime. */
        if (random == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            random = new SecureRandom();
        }
        return random;
    }

    @TruffleBoundary
    public static long getStartTime() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    public static String getStringResource(final Class<?> clazz, final String name) {
        CompilerAsserts.neverPartOfCompilation();
        try (InputStream is = clazz.getResourceAsStream(name)) {
            if (is != null) {
                return new String(is.readAllBytes());
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @TruffleBoundary
    public static String getSystemProperties() {
        final Properties properties = System.getProperties();
        final StringBuilder sb = new StringBuilder(256);
        sb.append("\n\n== System Properties =================================>\n");
        final Object[] keys = properties.keySet().toArray();
        Arrays.sort(keys);
        for (final Object systemKey : keys) {
            final String key = (String) systemKey;
            sb.append(String.format("%s = %s\n", key, System.getProperty(key, "n/a")));
        }
        sb.append("<= System Properties ===================================\n\n");
        return sb.toString();
    }

    @TruffleBoundary
    public static long getTotalCompilationTime() {
        final CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean.isCompilationTimeMonitoringSupported()) {
            return compilationBean.getTotalCompilationTime();
        } else {
            return -1L;
        }
    }

    @TruffleBoundary
    public static long getUptime() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    @TruffleBoundary
    public static String getVMInformation() {
        final String releaseFilePath = System.getProperty("java.home") + File.separator + "languages" + File.separator + SqueakLanguageConfig.ID + File.separator + "release";
        final Properties properties = new Properties();
        try {
            properties.load(Files.newInputStream(Paths.get(releaseFilePath)));
        } catch (final IOException e) {
            return String.format("\n%s (%s; %s)\n", System.getProperty("java.vm.name"), System.getProperty("java.vm.version"), System.getProperty("java.vm.info"));
        }
        final String source = properties.getProperty("SOURCE", "unknown source").replaceAll("\"", "");
        final String graalVMVersion = properties.getProperty("GRAALVM_VERSION", "unknown GraalVM version").replaceAll("\"", "");
        final String javaVersion = properties.getProperty("JAVA_VERSION", "unknown Java version").replaceAll("\"", "");
        final String osName = properties.getProperty("OS_NAME", "unknown os name").replaceAll("\"", "");
        final String osArch = properties.getProperty("OS_ARCH", "unknown os arch").replaceAll("\"", "");
        final String commitInfo = properties.getProperty("COMMIT_INFO", "unknown commit").replaceAll("\"", "");
        return String.format("%s\nbuilt for GraalVM %s (Java %s, %s, %s)\n%s", source, graalVMVersion, javaVersion, osName, osArch, commitInfo);
    }

    @TruffleBoundary
    public static String getVMPath() {
        final String binaryName = OS.isWindows() ? "java.exe" : "java";
        return System.getProperty("java.home") + File.separatorChar + "bin" + File.separatorChar + binaryName;
    }

    public static boolean isBlank(final String str) {
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static double millisToSeconds(final double millis) {
        return millis / 1000d;
    }

    private static double nanosToSeconds(final double nanos) {
        return nanos / (1000d * 1000d * 1000d);
    }

    /* Wraps bitmap in a BufferedImage for efficient drawing. */
    @TruffleBoundary
    public static BufferedImage new32BitBufferedImage(final int[] words, final int width, final int height, final boolean withAlpha) {
        /**
         * {@link ColorModel#getRGBdefault()} with alpha = 1.0. Transparency not needed at this
         * point. More importantly for the {@link JPEGReadWriter2Plugin}, {@link BufferedImage}s
         * without alpha channel can be exported as JPEG.
         */
        final DirectColorModel cm = new DirectColorModel(
                        32,
                        0x00ff0000, // Red
                        0x0000ff00, // Green
                        0x000000ff, // Blue
                        withAlpha ? 0xff000000 : 0 // Alpha
        );
        final SampleModel sm = cm.createCompatibleSampleModel(width, height);
        final DataBufferInt db = new DataBufferInt(words, words.length);
        final WritableRaster raster = Raster.createWritableRaster(sm, db, null);
        return new BufferedImage(cm, raster, true, null);
    }

    @TruffleBoundary
    public static void printResourceSummary(final SqueakImageContext image) {
        image.printToStdOut("# Resource Summary");
        final double totalProcessTimeSeconds = millisToSeconds(System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime());
        final long processCPUTime = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        image.printToStdOut(String.format("> Total process time: %ss | CPU load: %.2f", totalProcessTimeSeconds, nanosToSeconds(processCPUTime) / totalProcessTimeSeconds));
        long totalGCCount = 0;
        long totalGCTime = 0;
        for (final GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long time = gcBean.getCollectionTime();
            final long count = gcBean.getCollectionCount();
            totalGCTime += Math.max(time, 0);
            totalGCCount += Math.max(count, 0);
            final double timeSeconds = millisToSeconds(time);
            image.printToStdOut(String.format("> %8.4fs (%5.2f%% of total time) in %4s GCs of %s", timeSeconds, timeSeconds / totalProcessTimeSeconds * 100, count, gcBean.getName()));
        }
        final double totalGCSeconds = millisToSeconds(totalGCTime);
        image.printToStdOut(String.format("> %8.4fs (%5.2f%% of total time) in %4s GCs in total", totalGCSeconds, totalGCSeconds / totalProcessTimeSeconds * 100, totalGCCount));
    }

    @TruffleBoundary
    public static long runtimeFreeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    @TruffleBoundary
    public static long runtimeMaxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    @TruffleBoundary
    public static long runtimeTotalMemory() {
        return Runtime.getRuntime().totalMemory();
    }

    @TruffleBoundary
    public static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @TruffleBoundary
    public static byte[] stringToBytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @TruffleBoundary
    public static int[] stringToCodePointsArray(final String value) {
        return value.codePoints().toArray();
    }

    @TruffleBoundary
    public static String stringValueOf(final char value) {
        return String.valueOf(value);
    }

    @TruffleBoundary
    public static void systemGC() {
        System.gc();
    }

    @TruffleBoundary
    public static byte[] toBytes(final String value) {
        return value.getBytes();
    }

    /** Similar to {@link Math#toIntExact(long)}, but uses an assertion. */
    public static int toIntExact(final long value) {
        assert (int) value == value;
        return (int) value;
    }

    public static long toJavaMicrosecondsUTC(final long microseconds) {
        return microseconds - EPOCH_DELTA_MICROSECONDS;
    }

    public static long toSqueakMicrosecondsLocal(final long microseconds) {
        return toSqueakMicrosecondsUTC(microseconds) + TIME_ZONE_OFFSET_MICROSECONDS;
    }

    public static long toSqueakMicrosecondsUTC(final long microseconds) {
        return microseconds + EPOCH_DELTA_MICROSECONDS;
    }

    public static long toSqueakSecondsLocal(final long seconds) {
        return seconds + EPOCH_DELTA_SECONDS + TIME_ZONE_OFFSET_SECONDS;
    }

    @TruffleBoundary
    public static String toString(final Object value) {
        return value.toString();
    }
}
