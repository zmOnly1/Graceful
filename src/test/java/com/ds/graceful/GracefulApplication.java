package com.ds.graceful;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.ConsulRawClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.health.model.HealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.SocketUtils;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Created by zm on 2020/2/29.
 */
public class GracefulApplication {

    protected static final Logger logger = LoggerFactory.getLogger(GracefulApplication.class);

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(10);

        ConfigurableApplicationContext app1 = startSpringBootApplication(executorService, () -> getServerArgs("app1"));
        ConfigurableApplicationContext app2 = startSpringBootApplication(executorService, () -> getServerArgs("app2"));

        waitForAppServerRegister();
        startSpringBootApplication(executorService, () -> getClientArgs("client"));

        Queue<String> requestQueue = new LinkedList<>();
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("client-slow");
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("stop");
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("start");
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("client");
        requestQueue.add("client");

        RestTemplate restTemplate = new RestTemplate();
        while (!requestQueue.isEmpty()) {
            logger.info("New request......");

            String path = requestQueue.poll();
            if ("start".equals(path) && !app1.isActive()) {
                logger.info("Prepare to start app1");
                app1 = startSpringBootApplication(executorService, () -> getServerArgs("app1"));
                waitForAppServerRegister();
                int sleepSecond = 5;
                while (sleepSecond > 0) {
                    logger.info("Wait for health server list refresh on client side, {} second left", sleepSecond);
                    sleepSecond--;
                    Thread.sleep(1000);
                }
            } else if ("stop".equals(path) && app1.isActive()) {
                logger.info("Prepare to stop app1");
                stopspringBootApp(app1);
            } else {
                if (path.contains("slow")) {
                    asyncExecute(executorService, o -> {
                        logger.info("Slow request {}, result: {}", path, restTemplate.getForObject("http://localhost:35100/" + path, String.class));
                    });
                } else {
                    syncExecute(o -> {
                        logger.info("Slow request {}, result: {}", path, restTemplate.getForObject("http://localhost:35100/" + path, String.class));
                    });
                }
            }
        }

        System.out.println("sssssssssssssssssss");
    }

    private static void waitForAppServerRegister() throws InterruptedException {
        ConsulClient consulClient = new ConsulClient(new ConsulRawClient("localhost", 8500));
        List<HealthService> healthServices = new ArrayList<>();

        while (healthServices.size() != 2) {
            logger.info("Wait for 2 servers completely register......");
            Response<List<HealthService>> response = consulClient.getHealthServices("graceful-test", null, true, QueryParams.DEFAULT, "");

            healthServices = response.getValue();
            logger.info("Health Server size {}, list: {}", healthServices.size(), healthServices);
            Thread.sleep(1000);
        }
    }

    private static void asyncExecute(ExecutorService executorService, Consumer<Object> consumer) {
        executorService.execute(() -> {
            try {
                consumer.accept(null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void syncExecute(Consumer<Object> consumer) {
        try {
            consumer.accept(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ConfigurableApplicationContext startSpringBootApplication(ExecutorService executorService, Supplier<String[]> argsSupplier) throws ExecutionException, InterruptedException {
        return executorService.submit(() -> SpringApplication.run(App.class, argsSupplier.get())).get();
    }

    protected static String[] getServerArgs(String appName) {
        return getArgs(appName, true);
    }

    protected static String[] getClientArgs(String appName) {
        return getArgs(appName, false);
    }

    protected static String[] getArgs(String appName, boolean isServer) {
        return getProperties(appName, isServer).entrySet().stream()
                   .map(e -> "--" + e.getKey() + "=" + e.getValue())
                   .toArray(String[]::new);
    }

    protected static final Properties getProperties(String appName, boolean isServer) {
        Properties props = new Properties();
        int port = SocketUtils.findAvailableTcpPort();
        props.setProperty("server.port", isServer ? Integer.toString(port) : "35100");
        props.setProperty("spring.application.name", appName);
        //props.setProperty("logging.level.org.springframework.web", "trace");
        props.setProperty("spring.cloud.consul.host", "localhost");
        props.setProperty("spring.cloud.consul.port", "8500");
        props.setProperty("spring.cloud.consul.enabled", "true");
        props.setProperty("spring.cloud.consul.discovery.enabled", "true");
        props.setProperty("spring.cloud.consul.discovery.instanceId", appName);
        props.setProperty("spring.cloud.consul.discovery.register", isServer ? "true" : "false");
        props.setProperty("spring.cloud.consul.discovery.service-name", "graceful-test");
        props.setProperty("spring.cloud.consul.discovery.health-check-critical-timeout", "30s");
        props.setProperty("graceful-test.ribbon.ServerListRefreshInterval", "5000");//defoul 30s
        props.setProperty("spring.jmx.default-domain", appName);
        //props.setProperty("spring.jmx.enabled", "false");
        props.setProperty("spring.application.admin.enabled", "false");//
        props.setProperty("spring.cloud.consul.discovery.health-check-url", String.format("http://localhost:%s/health", port));
        //props.setProperty("spring.cloud.consul.discovery.health-check-interval", "30000s"); // after consul check will return passing service
        return props;
    }

    protected static void stopspringBootApp(ConfigurableApplicationContext applicationContext) {
        applicationContext.close();
    }


    @RestController
    @SpringBootApplication
    @EnableDiscoveryClient
    static class App {

        @Value("${spring.application.name}")
        private String appName;

        @LoadBalanced
        @Bean
        public RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @GetMapping("/server")
        public String server() {
            return "Hello " + appName;
        }

        @GetMapping("/server-slow")
        public String serverSlow() {
            logger.info("Receive slow request..." + appName);
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            long cycle = 150000000L;
            while (cycle-- != 0) {
                System.out.print("");
            }
            System.out.println();
            stopWatch.stop();
            logger.info("Slow complete: " + stopWatch.getTotalTimeSeconds());
            return "SLow " + appName;
        }

        @Autowired
        private RestTemplate restTemplate;

        @GetMapping("/client")
        public String client() {
            return restTemplate.getForObject("http://graceful-test/server", String.class);
        }

        @GetMapping("/client-slow")
        public String clientSlow() {
            return restTemplate.getForObject("http://graceful-test/server-slow", String.class);
        }

        @EventListener
        @Order(Ordered.HIGHEST_PRECEDENCE)
        public void onApplicationEvent(ContextClosedEvent event) {
            logger.info("Shutdown occured......");
        }
    }

}
