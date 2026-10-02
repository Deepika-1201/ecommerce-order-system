package com.ecommerce.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.catalog.domain.PageCursor;
import com.ecommerce.platform.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorsTests {

    @Test
    void cursorsSurviveTheRoundTrip() {
        PageCursor after = new PageCursor.AfterId(UUID.randomUUID());
        PageCursor offset = new PageCursor.Offset(40);

        assertThat(Cursors.decode(Cursors.encode(after))).isEqualTo(after);
        assertThat(Cursors.decode(Cursors.encode(offset))).isEqualTo(offset);
        assertThat(Cursors.encode(after)).matches("[A-Za-z0-9_-]+");
        assertThat(Cursors.decode(null)).isNull();
        assertThat(Cursors.decode("")).isNull();
    }

    @Test
    void cursorsThisApiDidNotIssueAreRefused() {
        for (String cursor : new String[] {"not base64!", encoded("a:not-a-uuid"), encoded("o:0"), encoded("o:-20"),
                encoded("o:x"), encoded("b:1"), encoded("a:")}) {
            assertThatThrownBy(() -> Cursors.decode(cursor)).as(cursor)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_cursor"));
        }
    }

    private static String encoded(String plain) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }
}
