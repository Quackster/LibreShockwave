package com.libreshockwave.util;

import java.net.URI;

public class FileUtil {

    /**
     * Extract just the filename from a path or URL.
     * Handles HTTP URLs, Windows paths (D:\path\file.cst), and Unix paths.
     */
    public static String getFileName(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        // For HTTP URLs, use URI parsing
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                URI uri = URI.create(path);
                String uriPath = uri.getPath();
                if (uriPath != null && !uriPath.isEmpty()) {
                    int lastSlash = uriPath.lastIndexOf('/');
                    return lastSlash >= 0 ? uriPath.substring(lastSlash + 1) : uriPath;
                }
            } catch (Exception e) {
                // Fall through to path-based extraction
            }
        }

        // Strip path (handles both / and \)
        return path.replaceAll("^.*[\\\\/]", "");
    }

    public static String getFileNameWithoutExtension(String string) {
        if (string == null || string.isEmpty()) {
            return string;
        }

        // Get just the filename first
        String fileName = getFileName(string);

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) { // no extension or hidden file like ".gitignore"
            return fileName;
        }

        return fileName.substring(0, lastDot);
    }
}
