package de.dfki.drz.mkm;

import static de.dfki.mlt.mqtt.MqttHandler.bytesToString;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import de.dfki.mlt.drz.fraunhofer_api.ApiClient;
import de.dfki.mlt.drz.fraunhofer_api.ApiException;
import de.dfki.mlt.drz.fraunhofer_api.Configuration;
import de.dfki.mlt.drz.fraunhofer_api.api.DefaultApi;
import de.dfki.mlt.drz.fraunhofer_api.model.RadioMessage;

import de.dfki.mlt.mqtt.MqttHandler;

public class Connector implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(Connector.class);
  private static final String SLOTS_TOPIC = "mkm/result";
  private static final String CONTROL_TOPIC = "mkm/control";

  protected boolean evaluation = false;
  private Writer w = null;

  private ObjectMapper mapper;
  private MqttHandler client;

  private boolean isRunning;
  private Thread proc;
  private BlockingQueue<JsonNode> queue;

  // provided credentials
  final String USER = "development";
  final String PASSWORD = "LookMomNoVPN!";
  
  final String BASE_URL_ROBLW = "http://10.26.2.42:8080/radio-transcription";
  final String BASE_URL_IAIS = "https://eve.iais.fraunhofer.de/radio-transcription";

  
  private static DefaultApi api;
  //private static final SimpleDateFormat sdf =
  //    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  //EuroCommandClient ECclient = new EuroCommandClient();
  UUID missionId;


  private void initMqtt(Map<String, Object> configs) throws MqttException {
    mapper = new ObjectMapper();
    client = new MqttHandler(configs);
    queue = new LinkedBlockingQueue<>();
    client.register(SLOTS_TOPIC, this::receiveCombined);
    client.register(CONTROL_TOPIC, this::receiveCommand);
    //client.register(STRING_TOPIC, this::receiveString);
 }

  private void initIAISApi(Map<String, Object> configs) {
    String url = BASE_URL_ROBLW;
    if (configs.containsKey("backend_url")) {
      url = (String) configs.get("backend_url");
    }

    ApiClient apiClient = Configuration.getDefaultApiClient();
    apiClient.setUsername(USER);
    apiClient.setPassword(PASSWORD);
    
    api = new DefaultApi();
    api.setCustomBaseUrl(url);
  }

  /*
  private void initECApi() {
    try {
      List<MissionResourceRestApiContract> missionResources =
          ECclient.getMissionResources(missionId);
      if (!missionResources.isEmpty()) {
        missionId = missionResources.get(0).getMissionId();
      }
    } catch (de.dfki.mlt.drz.eurocommand_api.ApiException ex) {
      missionId = null;
    }
  }
  */

  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void init(Map configs)
    throws IOException, MqttException {
    initMqtt(configs);
    initIAISApi(configs);

    // start processing thread
    isRunning = true;
    proc = new Thread(this);
    proc.setDaemon(true);
    proc.start();
    //initECApi();
  }

  public void shutdown() {
    // stop processing thread
    isRunning = false;
    try {
      queue.add(null); // in case the queue blocks run()
      proc.join();
      logger.info("Processing queue stopped");
    } catch (InterruptedException ex) {
    }
    // disconnect from broker
    try {
      if (client != null) {
        client.disconnect();
        client = null;
        logger.info("Disconnected from broker");
      }
    } catch (MqttException e) {
      logger.error("Error disconnecting from broker: {}", e.getMessage());
    }
    if (w != null) {
      try {
        w.close();
      } catch (IOException ex) {
        logger.error("Closing evaluation file failed: {}", ex.getMessage());
      }
    }
  }

  private boolean receiveCommand(byte[] b) {
    String cmd = bytesToString(b);
    switch (cmd) {
      case "exit":
        shutdown();
        break;
      default:
        logger.warn("Unknown command: {}", cmd);
        break;
    }
    return !cmd.isEmpty();
  }

  private boolean receiveCombined(byte[] b) {
    try {
      String json = MqttHandler.bytesToString(b);
      JsonNode node = mapper.readTree(json);
      // push to queue for asynchronous processing
      queue.add(node);
      return true;
    } catch (JsonProcessingException ex) {
      logger.error("Error converting incoming msg into JSON: {}",
          ex.getMessage());
    }
    return false;
  }


  private OffsetDateTime toODT(long l) {
    return new Date(l).toInstant().atZone(ZoneId.systemDefault())
      .toOffsetDateTime();
  }

  protected void sendMessageToIAIS(String sender, String receiver,
      String message, long fromTime, long toTime) {

    List<RadioMessage> radioMessages = new ArrayList<>();
    radioMessages.add(new RadioMessage()
        .sender(sender)
        .receiver(receiver)
        .message(message)
        .startTime(toODT(fromTime))
        .endTime(toODT(toTime)));
    try {
      api.addRadioMessagesAddMessagesPut(radioMessages);
      // if there is no exception, putting messages was successful
    } catch (ApiException e) {
      logger.error("Sending Message failed: {}", e.getMessage());
    }
    getMessagesFromIAIS(fromTime, toTime);
  }

  protected void getMessagesFromIAIS(long from, long to) {
    OffsetDateTime startTime = toODT(from);
    OffsetDateTime endTime = toODT(to);
    try {
      List<RadioMessage> response = api.getTranscriptionsGet(startTime, endTime);
      System.out.println("found " + response.size() + " results");
      for (RadioMessage m : response) {
        System.out.println(m);
      }
    } catch (ApiException e) {
      throw new RuntimeException(e);
    }
  }
  
  /*
  protected void sendMessageToEC(String sender, String receiver,
      String message, long fromTime, long toTime) {
    try {
      ECclient.sendMessage(message, sender, receiver, missionId);
    } catch (de.dfki.mlt.drz.eurocommand_api.ApiException ex) {
      logger.error("EC sending: {}", ex);
    }
  }
  */

  /** node also has slots id, intent, frame
   */
  void sendFusion(JsonNode node) {
    String sender = node.get("sender").asText();
    String addressee = node.get("addressee").asText();
    String text = node.get("text").asText();
    long fromTime = node.get("fromTime").asLong();
    long toTime = node.get("toTime").asLong();
    sendMessageToIAIS(sender, addressee, text, fromTime, toTime);
    //sendMessageToEC(sender, addressee, text, fromTime, toTime);
  }

  public void run() {
    while (isRunning) {
      JsonNode n = queue.poll();
      if (n != null) {
        sendFusion(n);
      }
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> readConfig(String confname)
      throws FileNotFoundException {
    Yaml yaml = new Yaml();
    File confFile = new File(confname);
    return (Map<String, Object>) yaml.load(new FileReader(confFile));
  }

  public static void main(String[] args) throws Exception {
    Map<String, Object> configs = new HashMap<>();
    if (args.length > 0) {
      configs = readConfig(args[0]);
    }
    Connector conn = new Connector();
    conn.init(configs);
    Thread main = new Thread();

    main.start();
    try {
      main.join();
    }
    catch (InterruptedException ex) {}
  }
}
