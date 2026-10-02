package com.ecommerce.architecture.fixture.beta.internal;

import com.ecommerce.architecture.fixture.beta.BetaApi;

public class BetaInternals implements BetaApi {

    @Override
    public int value() {
        return 42;
    }
}
