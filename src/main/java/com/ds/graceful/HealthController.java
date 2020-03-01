package com.ds.graceful;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Created by zm on 2020/3/1.
 */
@RestController
public class HealthController {

    @Value("${spring.application.name}")
    private String appName;

    @GetMapping("/health")
    public String health() {
        return "Up " + appName;
    }
}
