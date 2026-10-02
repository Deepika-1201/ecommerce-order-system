package com.ecommerce.catalog.web;

import com.ecommerce.catalog.domain.PageCursor;
import com.ecommerce.platform.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Opaque list cursors: clients pass back what they were given and never build one (LLD §3.3). */
final class Cursors {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Cursors() {
    }

    static String encode(PageCursor cursor) {
        String plain = switch (cursor) {
            case PageCursor.AfterId after -> "a:" + after.id();
            case PageCursor.Offset offset -> "o:" + offset.offset();
        };
        return ENCODER.encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    static PageCursor decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String plain = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            if (plain.startsWith("a:")) {
                return new PageCursor.AfterId(UUID.fromString(plain.substring(2)));
            }
            if (plain.startsWith("o:")) {
                int offset = Integer.parseInt(plain.substring(2));
                if (offset > 0) {
                    return new PageCursor.Offset(offset);
                }
            }
        } catch (IllegalArgumentException malformed) {
            // Falls through to the same answer as any other cursor this API did not issue.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "This cursor was not issued by this API.");
    }
}
