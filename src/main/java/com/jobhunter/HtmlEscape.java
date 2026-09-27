package com.jobhunter;

/**
 * Tiny helper to escape dynamic strings before they are dropped into the
 * hand-built HTML dashboard. Every piece of dynamic text (resume-derived
 * or job-derived) MUST pass through {@link #escape(String)} before being
 * appended to the dashboard's StringBuilder.
 */
public final class HtmlEscape {

    private HtmlEscape() {
        // no instances
    }

    /**
     * Escapes &amp; &lt; &gt; " ' so the string is safe to embed as HTML text
     * or inside a double- or single-quoted HTML attribute.
     */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
