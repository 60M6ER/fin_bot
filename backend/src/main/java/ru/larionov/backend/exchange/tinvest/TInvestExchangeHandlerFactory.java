package ru.larionov.backend.exchange.tinvest;

import org.springframework.stereotype.Component;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionContext;
import ru.larionov.backend.service.ExchangeHandler;
import ru.larionov.backend.service.ExchangeHandlerFactory;

@Component
public class TInvestExchangeHandlerFactory implements ExchangeHandlerFactory {

    @Override
    public ExchangeType exchange() {
        return ExchangeType.T_INVEST;
    }

    @Override
    public ExchangeHandler create(ExchangeConnectionContext context) {
        return new TInvestExchangeHandler(context);
    }
}
