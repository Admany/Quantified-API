package org.admany.quantified.core.common.opencl.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NativeLibraryExtractor {

    private static final Logger LOGGER = Logger.getLogger(NativeLibraryExtractor.class.getName());
    private static volatile boolean extracted = false;

    private NativeLibraryExtractor() {}

    public static synchronized void extractAndSetLibraryPath() {
        if (extracted) return;
        extracted = true;
        try (InputStream listStream = NativeLibraryExtractor.class.getClassLoader().getResourceAsStream("natives/natives-list.txt")) {
            if (listStream == null) {
                LOGGER.fine("No embedded native libraries found in resources/natives");
                return;
            }
            List<String> files = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(listStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) files.add(line.trim());
                }
            }
            if (files.isEmpty()) {
                LOGGER.fine("Natives list is empty");
                return;
            }
            Path tmp = Files.createTempDirectory("quantified-natives");
            tmp.toFile().deleteOnExit();
            for (String name : files) {
                String resPath = "natives/" + name;
                try (InputStream is = NativeLibraryExtractor.class.getClassLoader().getResourceAsStream(resPath)) {
                    if (is == null) {
                        LOGGER.fine("Native resource not found: " + resPath);
                        continue;
                    }
                    Path target = tmp.resolve(name);
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Failed to extract native resource: " + name, e);
                }
            }
            System.setProperty("org.lwjgl.librarypath", tmp.toAbsolutePath().toString());
            LOGGER.info("Embedded natives extracted to " + tmp.toAbsolutePath());
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Failed to extract embedded natives", t);
        }
    }
}
