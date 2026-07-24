package com.fast.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FastBackendApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    @Test
    void testProfile_disablesBrokerConnectingMqttInfrastructureBeans() {
        assertThat(applicationContext.containsBean("mqttClientFactory")).isFalse();
        assertThat(applicationContext.containsBean("mqttInboundAdapter")).isFalse();
        assertThat(applicationContext.containsBean("mqttOutboundHandler")).isFalse();
    }
}
