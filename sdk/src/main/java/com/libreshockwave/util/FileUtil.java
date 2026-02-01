package com.libreshockwave.util;

public class FileUtil {
    public static String getFileNameWithoutExtension(String string) {
        if (string == null || string.isEmpty()) {
            return string;
        }

        // Strip path (handles both / and \)
        String fileName = string.replaceAll("^.*[\\\\/]", "");

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) { // no extension or hidden file like ".gitignore"
            return fileName;
        }

        return fileName.substring(0, lastDot);
    }
}
