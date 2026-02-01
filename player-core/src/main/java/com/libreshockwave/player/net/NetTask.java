package com.libreshockwave.player.net;

import java.net.URI;

/**
 * Represents a single network request task.
 * Similar to dirplayer-rs net_task.rs.
 */
public class NetTask {

    public enum Method {
        GET, POST
    }

    public enum State {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private final int taskId;
    private final String url;
    private final String originalUrl;
    private final Method method;
    private final String postData;

    private State state = State.PENDING;
    private byte[] result;
    private int errorCode = 0;
    private String errorMessage;

    public NetTask(int taskId, String originalUrl, String url, Method method, String postData) {
        this.taskId = taskId;
        this.url = url;
        this.originalUrl = originalUrl;
        this.method = method;
        this.postData = postData;
    }

    public static NetTask get(int taskId, String originalUrl, String url) {
        return new NetTask(taskId, originalUrl, url, Method.GET, null);
    }

    public static NetTask post(int taskId, String originalUrl, String url, String postData) {
        return new NetTask(taskId, originalUrl, url, Method.POST, postData);
    }

    // Accessors

    public int getTaskId() {
        return taskId;
    }

    public String getUrl() {
        return url;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public Method getMethod() {
        return method;
    }

    public String getPostData() {
        return postData;
    }

    public State getState() {
        return state;
    }

    public boolean isDone() {
        return state == State.COMPLETED || state == State.FAILED;
    }

    public byte[] getResult() {
        return result;
    }

    public String getResultAsString() {
        return result != null ? new String(result) : "";
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // State mutations

    public void markInProgress() {
        this.state = State.IN_PROGRESS;
    }

    public void complete(byte[] data) {
        this.result = data;
        this.state = State.COMPLETED;
        this.errorCode = 0;
    }

    public void fail(int errorCode, String errorMessage) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.state = State.FAILED;
    }

    /**
     * Get the stream status string.
     * Matches Director's getStreamStatus format.
     */
    public String getStreamStatus() {
        return switch (state) {
            case PENDING -> "Connecting";
            case IN_PROGRESS -> "Loading";
            case COMPLETED -> "Complete";
            case FAILED -> "Error";
        };
    }

    @Override
    public String toString() {
        return "NetTask{id=" + taskId + ", util=" + url + ", method=" + method + ", state=" + state + "}";
    }
}
