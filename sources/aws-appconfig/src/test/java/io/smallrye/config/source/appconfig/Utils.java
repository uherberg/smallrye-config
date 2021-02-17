package io.smallrye.config.source.appconfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class Utils {
    public static String fileToString(Path path) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path);
        String line;
        StringBuilder stringBuilder = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            stringBuilder.append(line);
            stringBuilder.append("\n");
        }
        return stringBuilder.toString();
    }
}
