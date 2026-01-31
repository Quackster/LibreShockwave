package com.libreshockwave.tools.format;

/**
 * Utility for getting channel names and labels.
 */
public final class ChannelNames {

    private ChannelNames() {}

    /**
     * Returns a human-readable name for a channel index.
     */
    public static String get(int channelIndex) {
        if (channelIndex < 6) {
            return switch (channelIndex) {
                case 0 -> "Tempo";
                case 1 -> "Palette";
                case 2 -> "Transition";
                case 3 -> "Sound 1";
                case 4 -> "Sound 2";
                case 5 -> "Script";
                default -> "Ch " + (channelIndex + 1);
            };
        }
        return "Ch " + (channelIndex - 5);
    }

    /**
     * Creates an array of channel labels for a score table.
     */
    public static String[] createLabels(int channelCount) {
        String[] labels = new String[channelCount];
        for (int i = 0; i < channelCount; i++) {
            labels[i] = get(i);
        }
        return labels;
    }
}
