package com.ecommerce.cart.web;

import com.ecommerce.cart.domain.CartView;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.ObjectStorage;
import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.TaxRegime;
import com.ecommerce.shared.GstStateCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Request and response bodies of carts and quotes (LLD §4.3–4.5). Money is paise, GST included. */
final class CartApi {

    static final String CURRENCY = "INR";
    private static final Pattern STRONG_ETAG = Pattern.compile("\"(\\d{1,18})\"");

    private CartApi() {
    }

    record SetQuantity(@NotNull @Min(1) @Max(10) Integer quantity) {
    }

    record ApplyCoupon(@NotBlank @Size(max = 20) String code) {
    }

    record QuoteCart(@NotBlank @GstStateCode String deliveryStateCode) {
    }

    /** {@code added_unit_price_paise} appears only when the price changed since the line was added. */
    record CartLineResponse(
            String sku,
            UUID productId,
            String title,
            Map<String, String> optionValues,
            URI imageUrl,
            int quantity,
            long unitPricePaise,
            Long addedUnitPricePaise,
            long lineTotalPaise,
            boolean available) {
    }

    /** Prices are current and indicative; only a quote binds. The subtotal counts lines that can be bought. */
    record CartResponse(
            long version,
            List<CartLineResponse> lines,
            String couponCode,
            long subtotalPaise,
            String currency,
            Instant expiresAt) {

        static CartResponse of(CartView cart, ObjectStorage storage) {
            return new CartResponse(cart.version(), cart.lines().stream()
                    .map(line -> new CartLineResponse(line.sku(), line.productId(), line.title(), line.optionValues(),
                            url(line.imageKey(), storage), line.quantity(), line.unitPricePaise(),
                            line.addedUnitPricePaise(), line.lineTotalPaise(), line.available()))
                    .toList(), cart.couponCode(), cart.subtotalPaise(), CURRENCY, cart.expiresAt());
        }
    }

    /** Keep {@code cart_token} secret: it is the only credential for the cart, and it is never shown again. */
    record GuestCartCreated(String cartToken, CartResponse cart) {
    }

    record QuoteLineResponse(
            String sku,
            UUID productId,
            UUID variantId,
            String title,
            Map<String, String> optionValues,
            URI imageUrl,
            int quantity,
            long unitPricePaise,
            Long previousUnitPricePaise,
            long grossPaise,
            long discountPaise,
            long amountPaise,
            long taxableValuePaise,
            int gstRateBps,
            long cgstPaise,
            long sgstPaise,
            long igstPaise) {
    }

    record QuoteShippingResponse(long feePaise, long taxableValuePaise, int gstRateBps, long cgstPaise,
            long sgstPaise, long igstPaise) {
    }

    record QuoteTotalsResponse(
            long grossPaise,
            long discountPaise,
            long goodsPaise,
            long shippingPaise,
            long taxableValuePaise,
            long cgstPaise,
            long sgstPaise,
            long igstPaise,
            long taxPaise,
            long grandTotalPaise) {
    }

    /** Valid until {@code valid_until}; every total is the sum of its parts. */
    record QuoteResponse(
            UUID id,
            String couponCode,
            String supplyStateCode,
            String deliveryStateCode,
            TaxRegime taxRegime,
            String currency,
            List<QuoteLineResponse> lines,
            QuoteShippingResponse shipping,
            QuoteTotalsResponse totals,
            Instant validUntil,
            Instant createdAt) {

        static QuoteResponse of(Quote quote, ObjectStorage storage) {
            Quote.Shipping shipping = quote.shipping();
            Quote.Totals totals = quote.totals();
            return new QuoteResponse(quote.id(), quote.couponCode(), quote.supplyState().code(),
                    quote.deliveryState().code(), quote.taxRegime(), CURRENCY,
                    quote.lines().stream().map(line -> new QuoteLineResponse(line.sku(), line.productId(),
                            line.variantId(), line.title(), line.optionValues(), url(line.imageKey(), storage),
                            line.quantity(), line.unitPricePaise(), line.previousUnitPricePaise(), line.grossPaise(),
                            line.discountPaise(), line.amountPaise(), line.taxableValuePaise(), line.gstRateBps(),
                            line.cgstPaise(), line.sgstPaise(), line.igstPaise())).toList(),
                    new QuoteShippingResponse(shipping.feePaise(), shipping.taxableValuePaise(), shipping.gstRateBps(),
                            shipping.cgstPaise(), shipping.sgstPaise(), shipping.igstPaise()),
                    new QuoteTotalsResponse(totals.grossPaise(), totals.discountPaise(), totals.goodsPaise(),
                            totals.shippingPaise(), totals.taxableValuePaise(), totals.cgstPaise(), totals.sgstPaise(),
                            totals.igstPaise(), totals.taxPaise(), totals.grandTotalPaise()),
                    quote.validUntil(), quote.createdAt());
        }
    }

    static ResponseEntity<CartResponse> withEtag(CartView cart, ObjectStorage storage) {
        return ResponseEntity.ok().eTag("\"" + cart.version() + "\"").body(CartResponse.of(cart, storage));
    }

    /** {@code null} without the header; a weak or unknown tag never matches. */
    static Long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        Matcher strong = STRONG_ETAG.matcher(ifMatch.strip());
        if (!strong.matches()) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition_failed",
                    "If-Match must carry the cart's ETag.");
        }
        return Long.parseLong(strong.group(1));
    }

    private static URI url(String imageKey, ObjectStorage storage) {
        return imageKey == null ? null : storage.publicUrl(imageKey);
    }
}
