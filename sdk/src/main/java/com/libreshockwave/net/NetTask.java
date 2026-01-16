package com.libreshockwave.net;

import java.net.URI;

/**
 * Represents a single network task.
 * Matches dirplayer-rs NetTask (vm-rust/src/player/net_task.rs).
 */
public record NetTask(
    int id,
    String url,           // Original URL from Lingo
    URI resolvedUri,      // Fully resolved URI
    HttpMethod method,
    String postData       // Nullable, only for POST
) {
    public enum HttpMethod { GET, POST }

    public static NetTask get(int id, String url, URI resolvedUri) {
        return new NetTask(id, url, resolvedUri, HttpMethod.GET, null);
    }

    public static NetTask post(int id, String url, URI resolvedUri, String postData) {
        return new NetTask(id, url, resolvedUri, HttpMethod.POST, postData);
    }
}
