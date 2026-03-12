package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.cast.XmedTextParser;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.TextChunk;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Dump all cast members from external .cct files for Mobiles Disco,
 * focusing on text content (STXT / XMED) to find English UI labels.
 *
 * Run: ./gradlew :player-core:runExternalCastDump
 */
public class ExternalCastDump {

    private static final String[] CAST_FILES = {
        "C:/xampp/htdocs/mobiles/dcr_0519b_e/mobiles.cct",
        "C:/xampp/htdocs/mobiles/dcr_0519b_e/MemberScript.cct",
        "C:/xampp/htdocs/mobiles/dcr_0519b_e/FuseScript.cct",
    };

    // English labels we're looking for
    private static final String[] SEARCH_LABELS = {
        "User", "Password", "ENTER", "Login", "WELCOME", "CREDITS",
        "registered", "modify", "username", "email", "register",
        "connect", "play", "guest", "sign", "forgot", "remember",
        "name", "nick", "submit", "cancel", "ok", "error", "close"
    };

    public static void main(String[] args) throws Exception {
        for (String path : CAST_FILES) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== Loading: " + path);
            System.out.println("=".repeat(80));

            DirectorFile file;
            try {
                file = DirectorFile.load(Path.of(path));
            } catch (Exception e) {
                System.out.println("ERROR loading file: " + e.getMessage());
                e.printStackTrace();
                continue;
            }

            List<CastMemberChunk> members = file.getCastMembers();
            System.out.println("Total cast members: " + members.size());
            System.out.println();

            for (CastMemberChunk member : members) {
                String name = member.name();
                MemberType type = member.memberType();
                int id = member.id().value();

                // Always print basic info
                System.out.printf("  Member #%d  name=%-30s  type=%-10s", id, quote(name), type);

                // Try to get STXT text content
                String textContent = null;
                try {
                    TextChunk stxt = file.getTextForMember(member);
                    if (stxt != null && stxt.text() != null && !stxt.text().isEmpty()) {
                        textContent = stxt.text();
                        System.out.printf("  [STXT] text=%s", quote(truncate(textContent, 100)));
                    }
                } catch (Exception e) {
                    System.out.printf("  [STXT ERROR: %s]", e.getMessage());
                }

                // Try to get XMED text content
                try {
                    XmedTextParser.XmedText xmed = file.getXmedTextForMember(member);
                    if (xmed != null && xmed.text() != null && !xmed.text().isEmpty()) {
                        textContent = xmed.text();
                        System.out.printf("  [XMED] text=%s font=%s size=%d",
                                quote(truncate(xmed.text(), 100)), xmed.fontName(), xmed.fontSize());
                    }
                } catch (Exception e) {
                    System.out.printf("  [XMED ERROR: %s]", e.getMessage());
                }

                System.out.println();

                // Check if name or text content matches any search labels
                if (textContent != null) {
                    checkForLabels(id, name, textContent);
                }
                if (name != null && !name.isEmpty()) {
                    checkForLabels(id, name, name);
                }
            }

            // Also dump all TEXT type members specifically
            System.out.println("\n--- TEXT-type members summary ---");
            int textCount = 0;
            for (CastMemberChunk member : members) {
                if (member.isText() || member.isTextXtra()) {
                    textCount++;
                    String label = member.isTextXtra() ? "TEXT_XTRA" : "TEXT";
                    System.out.printf("  [%s] Member #%d  name=%s%n", label, member.id().value(), quote(member.name()));

                    // Print full text for text members
                    try {
                        TextChunk stxt = file.getTextForMember(member);
                        if (stxt != null && stxt.text() != null) {
                            System.out.println("    STXT full text: " + quote(stxt.text()));
                        }
                    } catch (Exception e) { /* already printed above */ }

                    try {
                        XmedTextParser.XmedText xmed = file.getXmedTextForMember(member);
                        if (xmed != null && xmed.text() != null) {
                            System.out.println("    XMED full text: " + quote(xmed.text()));
                        }
                    } catch (Exception e) { /* already printed above */ }
                }
            }
            System.out.println("Total TEXT/TEXT_XTRA members: " + textCount);
        }

        System.out.println("\n=== Done ===");
    }

    private static void checkForLabels(int memberId, String memberName, String content) {
        String lower = content.toLowerCase();
        for (String label : SEARCH_LABELS) {
            if (lower.contains(label.toLowerCase())) {
                System.out.printf("    >>> MATCH: label '%s' found in member #%d (name=%s): %s%n",
                        label, memberId, quote(memberName), quote(truncate(content, 120)));
                break; // one match per content is enough
            }
        }
    }

    private static String quote(String s) {
        if (s == null || s.isEmpty()) return "\"\"";
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
