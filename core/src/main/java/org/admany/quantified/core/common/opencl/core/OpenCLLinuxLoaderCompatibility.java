package org.admany.quantified.core.common.opencl.core;

/**
 * Makes LWJGL use the OpenCL ICD loader SONAME shipped by Linux runtime
 * packages.  Debian/Ubuntu deliberately expose {@code libOpenCL.so.1} at
 * runtime and reserve the unversioned {@code libOpenCL.so} symlink for the
 * development package.  LWJGL's default Linux name is the latter.
 */
public final class OpenCLLinuxLoaderCompatibility {

    private static final String LWJGL_OPENCL_LIBRARY_NAME = "org.lwjgl.opencl.libname";
    private static final String LINUX_ICD_LOADER_SONAME = "libOpenCL.so.1";

    private OpenCLLinuxLoaderCompatibility() {
    }

    /**
     * Must run before the first LWJGL OpenCL class is initialized.
     */
    public static void configureBeforeLwjglOpenCl() {
        if (!isLinux() || hasExplicitLwjglOpenClLibrary()) {
            return;
        }
        System.setProperty(LWJGL_OPENCL_LIBRARY_NAME, LINUX_ICD_LOADER_SONAME);
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
    }

    private static boolean hasExplicitLwjglOpenClLibrary() {
        String configured = System.getProperty(LWJGL_OPENCL_LIBRARY_NAME);
        return configured != null && !configured.isBlank();
    }
}
