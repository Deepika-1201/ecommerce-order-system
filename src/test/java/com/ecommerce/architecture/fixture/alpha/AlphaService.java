package com.ecommerce.architecture.fixture.alpha;

import com.ecommerce.architecture.fixture.beta.internal.BetaInternals;

public class AlphaService {

    public int answer() {
        return new BetaInternals().value();
    }
}
