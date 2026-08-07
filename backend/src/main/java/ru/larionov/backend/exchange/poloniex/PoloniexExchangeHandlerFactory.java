package ru.larionov.backend.exchange.poloniex;

import org.springframework.stereotype.Component;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionContext;
import ru.larionov.backend.service.ExchangeHandler;
import ru.larionov.backend.service.ExchangeHandlerFactory;

@Component
public class PoloniexExchangeHandlerFactory implements ExchangeHandlerFactory {

    @Override
    public ExchangeType exchange() {
        return ExchangeType.POLONIEX;
    }

    @Override
    public ExchangeHandler create(ExchangeConnectionContext context) {
        return new PoloniexExchangeHandler(context);
    }
}
