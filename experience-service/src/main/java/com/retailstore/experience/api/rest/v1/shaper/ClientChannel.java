package com.retailstore.experience.api.rest.v1.shaper;

/**
 * Represents the client type requesting data.
 * The experience layer detects this from the X-Client-Channel header
 * and shapes responses accordingly — more fields for web, fewer for mobile.
 */
public enum ClientChannel {
    WEB,       // full rich response
    MOBILE,    // lean payload, no large descriptions
    TABLET,    // similar to web, slightly reduced
    UNKNOWN;   // defaults to web behaviour

    public static ClientChannel from(String header) {
        if (header == null || header.isBlank()) return UNKNOWN;
        try { return valueOf(header.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return UNKNOWN; }
    }
}
