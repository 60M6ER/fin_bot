package ru.larionov.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Кэширование статики SPA.
 *
 * Правило здесь ровно одно, но без него обновление приложения ломает интерфейс:
 * имена файлов сборки содержат хэш содержимого, а единственное место, где эти имена
 * записаны, — index.html. Он отдавался вообще без заголовков кэширования, и браузер
 * по эвристике держал старую копию. После выкатки такой браузер продолжал просить
 * куски прошлой сборки, которых в новом образе уже нет: страницы, подгружаемые
 * лениво (обзор, настройки), просто переставали открываться — с 404 на файл,
 * которого никто не искал.
 *
 * Отсюда несимметричность: index.html перепроверяется всегда (ответ обычно 304 и
 * стоит один запрос), а файлы с хэшем в имени можно держать вечно — по такому
 * адресу содержимое не меняется никогда.
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Более частный шаблон побеждает общий "/**" из автоконфигурации Spring Boot,
        // поэтому остальную статику здесь перечислять не нужно: ей достаётся
        // no-cache из spring.web.resources.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).immutable());
    }
}
