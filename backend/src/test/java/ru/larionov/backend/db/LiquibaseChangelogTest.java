package ru.larionov.backend.db;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Прогон всего changelog на чистой схеме настоящего PostgreSQL.
 *
 * До появления этого теста миграции не проверялись НИЧЕМ. В остальных тестах
 * Liquibase выключен (spring.liquibase.enabled=false), а схему поднимает Hibernate
 * из сущностей — то есть тесты проверяли отображение сущностей и молчали про сами
 * changeset'ы. Несогласованный changeset впервые обнаружился бы на старте боевого
 * сервера, где чинить его уже дорого.
 *
 * <h3>Почему именно PostgreSQL, а не H2</h3>
 * H2 не годится: changeset 500-instrument создаёт индекс с {@code varchar_pattern_ops},
 * которого у H2 нет вовсе. Обойти это можно было бы только правкой уже применённого
 * на бою changeset'а, а у него от этого изменится контрольная сумма — и боевой старт
 * упадёт валидацией. Чинить проверку ценой поломки того, что она проверяет, незачем.
 *
 * <h3>Почему тест пропускается, а не падает</h3>
 * База поднимается docker-compose и есть не всегда. Пропуск честнее ложной зелени:
 * тест, переписанный под доступную базу, проверял бы не ту СУБД, на которой
 * миграция поедет. Поднять базу — {@code docker compose up -d postgres}.
 */
class LiquibaseChangelogTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    /** Порт из docker-compose; перекрывается переменной окружения для чужих окружений. */
    private static final String URL = System.getenv().getOrDefault(
            "TEST_PG_URL", "jdbc:postgresql://localhost:5435/trading_bot");
    private static final String USER = System.getenv().getOrDefault("TEST_PG_USER", "trading_bot");
    private static final String PASSWORD = System.getenv().getOrDefault("TEST_PG_PASSWORD", "");

    @Test
    @DisplayName("changelog применяется к пустой схеме целиком")
    void appliesToEmptySchema() throws Exception {
        withFreshSchema(connection -> {
            update(connection);

            assertThat(tableExists(connection, "bot_order")).isTrue();
            assertThat(tableExists(connection, "money_ledger")).isTrue();
            assertThat(tableExists(connection, "grid_generation")).isTrue();
        });
    }

    @Test
    @DisplayName("повторный прогон ничего не применяет заново")
    void isIdempotent() throws Exception {
        withFreshSchema(connection -> {
            update(connection);
            long afterFirst = countAppliedChangeSets(connection);

            update(connection);

            assertThat(countAppliedChangeSets(connection)).isEqualTo(afterFirst);
        });
    }

    /**
     * Колонки, которых требует код, действительно появляются.
     *
     * Проверяются те, чьё отсутствие не поймал бы никакой другой тест: Hibernate
     * в тестах строит схему сам и о changelog ничего не знает, поэтому забытый
     * addColumn дожил бы до боевого запуска.
     */
    @Test
    @DisplayName("роль заявки и режим поколения заведены миграцией")
    void addsGridRoleAndGenerationMode() throws Exception {
        withFreshSchema(connection -> {
            update(connection);

            assertThat(columns(connection, "bot_order")).contains("grid_role");
            assertThat(columns(connection, "money_ledger")).contains("grid_role");
            assertThat(columns(connection, "grid_generation"))
                    .contains("kind", "direction", "margin",
                            "entry_price", "target_price", "multiplier", "margin_episode_id");
        });
    }

    /**
     * Границы диапазона перестают быть обязательными.
     *
     * У восстановительного эпизода диапазона нет вовсе. Не отработай здесь
     * dropNotNullConstraint — первая же такая строка не записалась бы на боевом
     * сервере в момент переворота позиции, то есть в худший из возможных.
     */
    @Test
    @DisplayName("границы диапазона поколения стали необязательными")
    void generationRangeBecomesNullable() throws Exception {
        withFreshSchema(connection -> {
            update(connection);

            assertThat(isNullable(connection, "grid_generation", "lower_price")).isTrue();
            assertThat(isNullable(connection, "grid_generation", "upper_price")).isTrue();
        });
    }

    /**
     * Репетиция на КОПИИ боевой базы: миграция едет поверх накопленных данных.
     *
     * Прогон на пустой схеме этого не проверяет вовсе. Там нечего бэкфиллить и не на
     * чём проверить снятие NOT NULL: обе операции работают со строками, которых в
     * пустой схеме нет. Между тем именно они и опасны — на боевом сервере update
     * пройдёт по всей истории заявок, и ошибиться в нём можно ровно один раз.
     *
     * База задаётся снаружи и создаётся копией боевой:
     * <pre>
     * docker exec fin_bot-postgres-1 sh -c \
     *   'psql -U trading_bot -d postgres -c "create database trading_bot_rehearsal"; \
     *    pg_dump -U trading_bot -d trading_bot | psql -U trading_bot -d trading_bot_rehearsal'
     * TEST_PG_REHEARSAL_URL=jdbc:postgresql://localhost:5435/trading_bot_rehearsal
     * </pre>
     *
     * Указывать сюда боевую базу нельзя и не нужно: миграцию на неё накатывает
     * приложение при старте, а тест бы только опередил его без права на ошибку.
     */
    @Test
    @DisplayName("миграция применяется поверх накопленных боевых данных")
    void appliesOnTopOfRealData() throws Exception {
        String url = System.getenv("TEST_PG_REHEARSAL_URL");
        assumeTrue(url != null && !url.isBlank(),
                "TEST_PG_REHEARSAL_URL не задан — репетиция на копии боевых данных пропущена");

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD)) {
            long before = countAppliedChangeSets(connection);
            long ordersBefore = count(connection, "bot_order");
            long ledgerBefore = count(connection, "money_ledger");
            assumeTrue(ordersBefore > 0, "В копии нет заявок — репетиции не на чем проверять");

            update(connection);

            // Больше ИЛИ СТОЛЬКО ЖЕ: копия боевой базы может быть снята уже после
            // выкатки, и тогда применять нечего. Ноль новых changeset'ов на такой
            // базе — правильное поведение, а не ошибка: это и есть идемпотентность,
            // ради которой Liquibase и ведёт свой журнал.
            assertThat(countAppliedChangeSets(connection))
                    .as("changeset'ы не должны исчезать, а недостающие обязаны примениться")
                    .isGreaterThanOrEqualTo(before);
            assertThat(count(connection, "bot_order"))
                    .as("миграция не имеет права терять заявки")
                    .isEqualTo(ordersBefore);
            assertThat(count(connection, "money_ledger")).isEqualTo(ledgerBefore);

            /*
             * Бэкфилл роли. Проверяется ПОЛНОТА, а не соответствие роли стороне.
             *
             * Соответствие «продажа = закрытие» верно только для истории, накопленной
             * до появления шортов, и отличить её в общей куче уже нечем: у шортового
             * бота продажа законно ОТКРЫВАЕТ позицию, и таких строк в базе теперь
             * сколько угодно. Требовать здесь лонгового правила значило бы объявить
             * ошибкой ровно то поведение, ради которого всё и делалось.
             *
             * А вот полнота — настоящий инвариант миграции: строка без роли означала
             * бы, что бэкфилл кого-то пропустил, и партии по ней перестроятся неверно.
             */
            assertThat(count(connection, "bot_order where grid_role is null"))
                    .as("роль обязана быть проставлена каждой заявке").isZero();
            assertThat(count(connection, "bot_order where grid_role not in ('OPEN','CLOSE')"))
                    .as("роль обязана быть одной из двух известных").isZero();
            assertThat(count(connection, "money_ledger where side is not null and grid_role is null"))
                    .as("у торговой строки книги роль обязана быть").isZero();

            /*
             * Режим поколения. Проверяется согласованность, а не «всё лонговое».
             *
             * Утверждать второе было можно ровно до первого шортового бота — теперь
             * в базе законно живут поколения с direction=SHORT и margin=true, и
             * требовать от них лонга значило бы объявить ошибкой саму цель работы.
             *
             * Настоящий инвариант такой: вид и направление заполнены известными
             * значениями, а шортовое поколение обязано быть маржинальным — шорта
             * без маржи не бывает, и строка, утверждающая обратное, означала бы
             * потерянный признак.
             */
            assertThat(count(connection, "grid_generation where kind not in ('GRID','RECOVERY')"))
                    .as("вид поколения обязан быть известным").isZero();
            assertThat(count(connection, "grid_generation where direction not in ('LONG','SHORT')"))
                    .as("направление обязано быть известным").isZero();
            assertThat(count(connection, "grid_generation where direction = 'SHORT' and margin = false"))
                    .as("шортовое поколение не может быть немаржинальным").isZero();

            assertThat(isNullable(connection, "grid_generation", "lower_price")).isTrue();
        }
    }

    // ==============================
    // ИНФРАСТРУКТУРА
    // ==============================

    private interface SchemaTest {
        void run(Connection connection) throws Exception;
    }

    /**
     * Отдельная схема на каждый тест, удаляется после прогона.
     *
     * Схема, а не база: создать базу из-под того же соединения нельзя, а мусорить
     * в боевой {@code public} категорически незачем — changelog там уже применён,
     * и повторный прогон поверх него ничего бы не проверил.
     */
    private void withFreshSchema(SchemaTest test) throws Exception {
        assumeTrue(databaseAvailable(),
                "PostgreSQL недоступен по " + URL + " — миграции не проверены. "
                        + "Поднять: docker compose up -d postgres");

        String schema = "liquibase_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(URL, USER, PASSWORD)) {
            exec(admin, "create schema " + schema);
            try (Connection connection = open(URL, schema)) {
                test.run(connection);
            } finally {
                // Отдельным соединением: своё Liquibase закрывает вместе с собой,
                // и убирать за собой через него уже нечем.
                exec(admin, "drop schema " + schema + " cascade");
            }
        }
    }

    /** Соединение к указанной базе, работающее внутри указанной схемы. */
    private Connection open(String url, String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
        exec(connection, "set search_path to " + schema);
        return connection;
    }

    private boolean databaseAvailable() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Прогон миграций на СВОЁМ соединении.
     *
     * Своё оно потому, что {@code Liquibase.close()} закрывает и обёрнутый Database,
     * и лежащее под ним JDBC-соединение. Отдай мы ему рабочее — после первого же
     * прогона проверять результат было бы нечем, а прибрать за собой схему тем более.
     */
    private void update(Connection connection) throws Exception {
        String schema = currentSchema(connection);
        // URL берём У САМОГО соединения, а не из константы. Константа указывает на
        // боевую базу, и репетиция на копии молча накатила бы миграцию не туда —
        // ровно на ту базу, которую в этот момент читают живые боты.
        try (Connection own = open(connection.getMetaData().getURL(), schema)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(own));
            database.setDefaultSchemaName(schema);
            try (Liquibase liquibase =
                         new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    private String currentSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select current_schema()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void exec(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    /** {@code from} — таблица, при необходимости с условием: «bot_order where side = 'BUY'». */
    private long count(Connection connection, String from) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from " + from)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countAppliedChangeSets(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from databasechangelog")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, currentSchema(connection), table, null)) {
            return rs.next();
        }
    }

    private List<String> columns(Connection connection, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData()
                .getColumns(null, currentSchema(connection), table, null)) {
            while (rs.next()) {
                result.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private boolean isNullable(Connection connection, String table, String column) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getColumns(null, currentSchema(connection), table, column)) {
            if (!rs.next()) {
                throw new AssertionError("Нет колонки " + table + "." + column);
            }
            return "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
        }
    }
}
