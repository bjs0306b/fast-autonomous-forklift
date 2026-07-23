package com.fast.backend.config.mqtt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttTopicsTest {

    private MqttTopics mqttTopics;

    @BeforeEach
    void setUp() {
        MqttProperties.Topics topics = new MqttProperties.Topics(
                "forklift/+/status",
                "forklift/+/location",
                "forklift/+/path",
                "forklift/+/command-result",
                "forklift/+/fork-status",
                "forklift/+/error",
                "cargo/detected",
                "forklift/%s/command",
                "forklift/%s/emergency");
        MqttProperties properties = new MqttProperties(
                "tcp://localhost:1883", null, null,
                "fast-backend-inbound", "fast-backend-outbound",
                10, 30, true, true, 1, 5000L, 5000L, topics);
        mqttTopics = new MqttTopics(properties);
    }

    @Test
    void forkliftCommand_buildsTopicWithForkliftId() {
        assertThat(mqttTopics.forkliftCommand("F01")).isEqualTo("forklift/F01/command");
    }

    @Test
    void forkliftEmergency_buildsTopicWithForkliftId() {
        assertThat(mqttTopics.forkliftEmergency("F01")).isEqualTo("forklift/F01/emergency");
    }

    @Test
    void isForkliftStatusTopic_matchesOnlyStatusTopic() {
        assertThat(mqttTopics.isForkliftStatusTopic("forklift/F01/status")).isTrue();
        assertThat(mqttTopics.isForkliftStatusTopic("forklift/F01/location")).isFalse();
        assertThat(mqttTopics.isForkliftStatusTopic("forklift/F01/status/extra")).isFalse();
    }

    @Test
    void isForkliftLocationTopic_matchesOnlyLocationTopic() {
        assertThat(mqttTopics.isForkliftLocationTopic("forklift/F01/location")).isTrue();
        assertThat(mqttTopics.isForkliftLocationTopic("forklift/F01/status")).isFalse();
    }

    @Test
    void extractForkliftId_extractsFromStatusOrLocationTopic() {
        assertThat(mqttTopics.extractForkliftId("forklift/F01/status")).isEqualTo("F01");
        assertThat(mqttTopics.extractForkliftId("forklift/F02/location")).isEqualTo("F02");
    }

    @Test
    void isForkliftPathTopic_matchesOnlyPathTopic() {
        assertThat(mqttTopics.isForkliftPathTopic("forklift/SIM01/path")).isTrue();
        assertThat(mqttTopics.isForkliftPathTopic("forklift/SIM01/location")).isFalse();
    }

    @Test
    void extractForkliftId_extractsFromPathTopic() {
        assertThat(mqttTopics.extractForkliftId("forklift/SIM01/path")).isEqualTo("SIM01");
    }

    @Test
    void isForkliftCommandResultTopic_matchesOnlyCommandResultTopic() {
        assertThat(mqttTopics.isForkliftCommandResultTopic("forklift/REAL01/command-result")).isTrue();
        assertThat(mqttTopics.isForkliftCommandResultTopic("forklift/REAL01/command")).isFalse();
        assertThat(mqttTopics.isForkliftCommandResultTopic("forklift/REAL01/status")).isFalse();
    }

    @Test
    void isForkliftForkStatusTopic_matchesOnlyForkStatusTopic() {
        assertThat(mqttTopics.isForkliftForkStatusTopic("forklift/REAL01/fork-status")).isTrue();
        assertThat(mqttTopics.isForkliftForkStatusTopic("forklift/REAL01/status")).isFalse();
    }

    @Test
    void isForkliftErrorTopic_matchesOnlyErrorTopic() {
        assertThat(mqttTopics.isForkliftErrorTopic("forklift/REAL01/error")).isTrue();
        assertThat(mqttTopics.isForkliftErrorTopic("forklift/REAL01/status")).isFalse();
    }

    @Test
    void extractForkliftId_extractsFromCommandResultForkStatusAndErrorTopics() {
        assertThat(mqttTopics.extractForkliftId("forklift/REAL01/command-result")).isEqualTo("REAL01");
        assertThat(mqttTopics.extractForkliftId("forklift/REAL01/fork-status")).isEqualTo("REAL01");
        assertThat(mqttTopics.extractForkliftId("forklift/REAL01/error")).isEqualTo("REAL01");
    }

    @Test
    void forkliftCommand_blankForkliftId_throwsException() {
        assertThatThrownBy(() -> mqttTopics.forkliftCommand(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mqttTopics.forkliftCommand(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractForkliftId_unrelatedTopic_throwsException() {
        assertThatThrownBy(() -> mqttTopics.extractForkliftId("cargo/detected"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
