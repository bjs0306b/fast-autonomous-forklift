package com.fast.backend.transport.dispatch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@link DispatchProperties} 활성화(prompt48.md 19장). */
@Configuration
@EnableConfigurationProperties(DispatchProperties.class)
public class DispatchConfig {
}
