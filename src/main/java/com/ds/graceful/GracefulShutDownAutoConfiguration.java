package com.ds.graceful;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.concurrent.*;

/**
 * Created by zm on 2020/3/1.
 */
@ConditionalOnProperty(havingValue = "true", prefix = "graceful.shutdown", name = "enabled", matchIfMissing = true)
@ConditionalOnWebApplication
@Configuration
@Import(HealthController.class)
public class GracefulShutDownAutoConfiguration {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${graceful.shutdown.interval:10}")
    private int interval;
    @Value("${graceful.shutdown.timeout:60}")
    private int timeout;

    @Bean
    public TomcatConnectorCustomizer connectorCustomizer() {
        return new GracefulTomcatConnectorCustomizer();
    }

    @Bean
    public WebServerFactoryCustomizer webServerFactoryCustomizer(TomcatConnectorCustomizer connectorCustomizer) {
        return (WebServerFactoryCustomizer<TomcatServletWebServerFactory>) factory -> factory.addConnectorCustomizers(connectorCustomizer);
    }


    private class GracefulTomcatConnectorCustomizer implements TomcatConnectorCustomizer {

        private volatile Connector connector;

        @Override
        public void customize(Connector connector) {
            this.connector = connector;
        }


        @EventListener
        @Order(Ordered.HIGHEST_PRECEDENCE)
        public void onApplicationEvent(ContextClosedEvent event) throws InterruptedException {
            if (this.connector == null) {
                logger.info("No connector...");
                return;
            }
            stopAcceptingNewRequests();
            shutdownThreadPoolExecutor();
        }

        private void shutdownThreadPoolExecutor() throws InterruptedException {
            ThreadPoolExecutor executor = getThreadPoolExecutor();
            if (executor != null) {
                logger.info("{} is gracefully shutting down...", applicationName);
                executor.shutdown();
                awaitShutdown(executor);
            }
        }

        private void awaitShutdown(ThreadPoolExecutor executor) throws InterruptedException {
            logger.info("Await shutting down, {}...", executor);
            for (int remaining = timeout; remaining > 0; remaining -= interval) {
                if (executor.awaitTermination(remaining, TimeUnit.SECONDS)) {
                    logger.info("Shutdown finish,");
                    return;
                }
                logger.info("Shutdown in progress {} thread(s) active, {} seconds remaining", executor.getActiveCount(), remaining);
            }
            logMessageifThereAreStillActiveThreads(executor);
        }

        private void logMessageifThereAreStillActiveThreads(ThreadPoolExecutor executor) {
            if (executor.getActiveCount() > 0) {
                logger.info("{} thread(s) still active, force shutdown", executor.getActiveCount());
            }
        }

        private ThreadPoolExecutor getThreadPoolExecutor() {
            Executor executor = connector.getProtocolHandler().getExecutor();
            if (executor != null) {
                return (ThreadPoolExecutor) executor;
            }
            return null;
        }

        private void stopAcceptingNewRequests() {
            this.connector.pause();
            logger.info("Paused {} to stop accepting new requests.", connector);
        }
    }
}
