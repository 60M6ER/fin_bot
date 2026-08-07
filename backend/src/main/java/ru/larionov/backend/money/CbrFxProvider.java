package ru.larionov.backend.money;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Официальный курс ЦБ РФ.
 *
 * Берём с cbr.ru напрямую, а не с популярных зеркал вроде cbr-xml-daily.ru: зеркало —
 * это ещё одна чужая инфраструктура на пути к числу, которым подписан баланс
 * пользователя, и отвечает оно за него ни перед кем.
 *
 * Ответ в windows-1251, а не в UTF-8 — кодировку берём из заголовка XML, иначе
 * названия валют приезжают мусором (сам курс при этом читается верно, поэтому
 * ошибка была бы незаметной до первого показа названия).
 */
@Slf4j
@Component
public class CbrFxProvider implements FxRateProvider {

    public static final String ID = "CBR";

    private static final URI DAILY = URI.create("https://www.cbr.ru/scripts/XML_daily.asp");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<FxRate> usdRub() {
        try {
            HttpRequest request = HttpRequest.newBuilder(DAILY).timeout(TIMEOUT).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.debug("ЦБ ответил {}", response.statusCode());
                return Optional.empty();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Не удалось получить курс ЦБ: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<FxRate> parse(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Разбираем чужой XML: внешние сущности выключаем, чтобы ответ сервиса
        // не мог заставить нас ходить по чужим ссылкам или читать локальные файлы.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);

        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        NodeList valutes = doc.getElementsByTagName("Valute");

        for (int i = 0; i < valutes.getLength(); i++) {
            Node node = valutes.item(i);
            if (!(node instanceof Element valute)) {
                continue;
            }
            if (!"USD".equalsIgnoreCase(text(valute, "CharCode"))) {
                continue;
            }

            BigDecimal value = decimal(text(valute, "Value"));
            BigDecimal nominal = decimal(text(valute, "Nominal"));
            if (value == null || nominal == null || nominal.signum() <= 0) {
                return Optional.empty();
            }

            // Value — цена НОМИНАЛА, а не одной единицы. Для доллара номинал равен
            // единице, но для валют вроде йены — сотне, и деление здесь не формальность.
            BigDecimal rate = value.divide(nominal, 10, java.math.RoundingMode.HALF_UP);
            Instant asOf = parseDate(doc);
            return Optional.of(new FxRate(CurrencyCode.USD, CurrencyCode.RUB, rate, ID, asOf));
        }
        return Optional.empty();
    }

    /** Дата курса из атрибута Date="dd.MM.yyyy"; при неудаче — момент запроса. */
    private Instant parseDate(Document doc) {
        try {
            String raw = doc.getDocumentElement().getAttribute("Date");
            if (raw == null || raw.isBlank()) {
                return Instant.now();
            }
            String[] parts = raw.split("\\.");
            return java.time.LocalDate.of(
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]))
                    .atStartOfDay(java.time.ZoneId.of("Europe/Moscow"))
                    .toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent();
    }

    /** ЦБ отдаёт числа с запятой в качестве десятичного разделителя. */
    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
