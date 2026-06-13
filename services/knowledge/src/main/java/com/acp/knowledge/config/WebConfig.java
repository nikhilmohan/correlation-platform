package com.acp.knowledge.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

/**
 * Web config for slash-bearing record identifiers.
 *
 * <p>Record identifiers contain slashes (e.g. {@code core-ip/modelParams/noise-filter}); callers
 * URL-encode them as {@code %2F} in the path segment. To keep a {@code %2F}-encoded recordId in
 * ONE path segment we must:
 * <ol>
 *   <li>relax Tomcat to accept encoded slashes (it rejects {@code %2F} by default), and</li>
 *   <li>tell Spring's URL path matching NOT to decode the path, so {@code %2F} is matched as an
 *       encoded character rather than split into segments.</li>
 * </ol>
 * The controllers decode the {@code recordId} {@code @PathVariable} themselves (a single decode).
 * This is what makes the frozen {@code model-params/{recordId}} contract path resolvable.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> allowEncodedSlashes() {
        return factory -> factory.addConnectorCustomizers(WebConfig::relaxConnector);
    }

    private static void relaxConnector(Connector connector) {
        connector.setEncodedSolidusHandling("passthrough");
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setUrlDecode(false);
        helper.setRemoveSemicolonContent(false);
        configurer.setUrlPathHelper(helper);
    }
}
