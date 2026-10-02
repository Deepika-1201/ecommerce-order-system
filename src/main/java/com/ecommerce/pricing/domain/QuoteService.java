package com.ecommerce.pricing.domain;

import com.ecommerce.catalog.SkuCatalog;
import com.ecommerce.catalog.SkuDetails;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.QuoteRequest;
import com.ecommerce.pricing.Quotes;
import com.ecommerce.pricing.domain.PricingInput.ShippingRule;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prices carts into immutable quotes and keeps them until a day after they expire (LLD §4.5). */
@Service
class QuoteService implements Quotes {

    static final String PURGE_TASK = "pricing.purge-quotes";
    static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(1);
    private static final int PURGE_BATCH = 1_000;
    private static final int MAX_PURGE_BATCHES = 50;

    private final QuoteRepository quotes;
    private final CouponService coupons;
    private final SkuCatalog skuCatalog;
    private final PricingProperties properties;
    private final Clock clock;

    QuoteService(QuoteRepository quotes, CouponService coupons, SkuCatalog skuCatalog, PricingProperties properties,
            Clock clock) {
        this.quotes = quotes;
        this.coupons = coupons;
        this.skuCatalog = skuCatalog;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Quote create(QuoteRequest request) {
        List<SkuDetails> skus = purchasable(request.lines());
        long gross = 0;
        for (int i = 0; i < skus.size(); i++) {
            gross = Math.addExact(gross, Math.multiplyExact(skus.get(i).listPricePaise(),
                    request.lines().get(i).quantity()));
        }
        Coupon coupon = request.couponCode() == null ? null
                : coupons.quotable(request.couponCode(), request.customerId(), gross);

        List<PricingInput.Line> pricingLines = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            pricingLines.add(new PricingInput.Line(request.lines().get(i).quantity(), skus.get(i).listPricePaise(),
                    skus.get(i).gstCategory()));
        }
        PricedQuote priced = QuoteCalculator.price(new PricingInput(pricingLines,
                coupon == null ? null : coupon.rule(),
                new ShippingRule(properties.shipping().feePaise(), properties.shipping().freeFromPaise()),
                properties.supplyState(), request.deliveryState()));

        Instant now = clock.instant();
        Quote quote = new Quote(Ids.newId(), request.cartId(), request.customerId(),
                coupon == null ? null : coupon.id(), coupon == null ? null : coupon.code(),
                properties.supplyState(), request.deliveryState(), priced.regime(),
                lines(request.lines(), skus, priced.lines()), shipping(priced.shipping()), totals(priced.totals()),
                now.plus(properties.quoteValidity()), now);
        quotes.insert(quote);
        return quote;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Quote> findForCustomer(UUID quoteId, UUID customerId) {
        return quotes.findForCustomer(quoteId, customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Quote> findForGuestCart(UUID quoteId, UUID cartId) {
        return quotes.findForGuestCart(quoteId, cartId);
    }

    @HandlesTask(type = PURGE_TASK, every = "1h")
    void purgeExpired(TaskExecution<Void> task) {
        Instant cutoff = clock.instant().minus(RETENTION_AFTER_EXPIRY);
        int batches = 0;
        while (quotes.deleteExpiredBefore(cutoff, PURGE_BATCH) == PURGE_BATCH && ++batches < MAX_PURGE_BATCHES) {
            // Each batch is its own statement, so a large backlog never holds locks for long.
        }
    }

    /** The SKUs of the lines, in line order; any line that can no longer be bought fails the quote. */
    private List<SkuDetails> purchasable(List<QuoteRequest.Line> lines) {
        Map<String, SkuDetails> found = skuCatalog.find(lines.stream().map(QuoteRequest.Line::sku).toList());
        List<SkuDetails> skus = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (QuoteRequest.Line line : lines) {
            SkuDetails details = found.get(line.sku().toUpperCase(Locale.ROOT));
            if (details == null || !details.purchasable()) {
                unavailable.add(line.sku());
            }
            skus.add(details);
        }
        if (!unavailable.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "cart_has_unavailable_items",
                    "These items can no longer be bought: " + String.join(", ", unavailable) + ".");
        }
        return skus;
    }

    private static List<Quote.Line> lines(List<QuoteRequest.Line> requested, List<SkuDetails> skus,
            List<PricedQuote.Line> priced) {
        List<Quote.Line> lines = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            SkuDetails sku = skus.get(i);
            PricedQuote.Line line = priced.get(i);
            Long added = requested.get(i).addedPricePaise();
            lines.add(new Quote.Line(sku.sku(), sku.productId(), sku.variantId(), sku.title(), sku.optionValues(),
                    sku.imageKey(), requested.get(i).quantity(), sku.listPricePaise(),
                    added != null && added != sku.listPricePaise() ? added : null,
                    line.grossPaise(), line.discountPaise(), line.amountPaise(), line.taxableValuePaise(),
                    line.gstRateBps(), line.tax().cgstPaise(), line.tax().sgstPaise(), line.tax().igstPaise()));
        }
        return lines;
    }

    private static Quote.Shipping shipping(PricedQuote.Charge shipping) {
        return new Quote.Shipping(shipping.amountPaise(), shipping.taxableValuePaise(), shipping.gstRateBps(),
                shipping.tax().cgstPaise(), shipping.tax().sgstPaise(), shipping.tax().igstPaise());
    }

    private static Quote.Totals totals(PricedQuote.Totals totals) {
        return new Quote.Totals(totals.grossPaise(), totals.discountPaise(), totals.goodsPaise(),
                totals.shippingPaise(), totals.taxableValuePaise(), totals.tax().cgstPaise(),
                totals.tax().sgstPaise(), totals.tax().igstPaise(), totals.grandTotalPaise());
    }
}
