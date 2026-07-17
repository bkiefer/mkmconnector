package de.dfki.drz.mkm;

import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TestMkmConnector {
  private static final String TEST_DIR = "src/test/resources";

  @Test
  public void test() throws IOException, MqttException, InterruptedException {
    Map<String, Object> credentials = Connector.readConfig("credentials.yml");
    Map<String, Object> configs = Connector.readConfig(TEST_DIR + "/config.yml");

    Connector conn = new Connector(false);
    conn.init(credentials, configs);

    try (BufferedReader in
        = Files.newBufferedReader(Path.of(TEST_DIR, "input.json"))){
      JsonNode jn = new ObjectMapper().readTree(in);
      long fromTime = Connector.xsdToLong(jn.get("fromTime").asText());
      long toTime = Connector.xsdToLong(jn.get("toTime").asText());
      assertTrue(conn.sendFusion(jn));
      assertTrue(conn.getMessagesFromIAIS(fromTime, toTime).size() > 0);
    }
  }
}

