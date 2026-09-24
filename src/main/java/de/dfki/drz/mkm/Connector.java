package de.dfki.drz.mkm;

import static de.dfki.mlt.mqtt.MqttHandler.bytesToString;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.dfki.drz.mkm.ui.ResultWindow;
import de.dfki.mlt.mqtt.MqttHandler;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Connector implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(Connector.class);
  private static final String SLOTS_TOPIC = "mkm/result";
  private static final String CONTROL_TOPIC = "mkm/control";

  // provided credentials
  private static final String USER = "user";
  private static final String PASSWORD = "password";
  // private static final String TOKEN = "token"; // formerly for EC

  // config slots
  private static final String BASE_URL = "base_url";
  private static final String PORT = "port";

  private static Map<String, String> slotNameMap = new HashMap<>();
  static {
    String[] slotNameMapping = {
        "fromTime", "start_time",
        "toTime", "end_time",
        "addressee", "receiver",
        "text", "message" };
    for (int i = 0; i < slotNameMapping.length; i += 2) {
      slotNameMap.put(slotNameMapping[i], slotNameMapping[i + 1]);
    }
  }

  private static String[] timeSlots = { "start_time", "end_time" };

  private static final String ADD_RADIOMESSAGES_ENDPOINT = "add_messages";

  // It's best practice to share a single OkHttpClient instance across your
  // application
  protected static final OkHttpClient httpClient = new OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build();

  public static OkHttpClient getHttpClient() {
    return httpClient;
  }

  // from XsdAnySimpleType
  protected static String extractValue(String value) {
    int index = value.lastIndexOf('^');
    return value.substring(1, index - 2);
  }

  public static long xsdToLong(String xsdlong) {
    return Long.parseLong(extractValue(xsdlong));
  }

  public static String getBasicAuthHeader(Map<String, Object> configs) {
    String username = (String) configs.get(USER);
    String password = (String) configs.get(PASSWORD);

    // Encode credentials to Base64 for Basic Auth
    String credentials = username + ":" + password;
    String basicAuthHeader = "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes());
    return basicAuthHeader;
  }

  // Define the Media Type for JSON
  // Note: In OkHttp 4+, use MediaType.get() instead of MediaType.parse()
  private static final MediaType JSON_MEDIA =
      MediaType.get("application/json; charset=utf-8");

  protected boolean evaluation = false;
  private Writer w = null;

  private ObjectMapper mapper;
  private MqttHandler client;

  private boolean rw;

  private boolean isRunning;
  private Thread proc;
  private BlockingQueue<JsonNode> queue;

  private HttpUrl base_url;
  private String basicAuthHeader;


  public Connector(boolean interactive) {
    if (interactive) {
      this.rw = interactive;
      ResultWindow.interactive();
    }
  }

  private void initMqtt(Map<String, Object> configs) throws MqttException {
    mapper = new ObjectMapper();
    if (configs == null) configs = Collections.emptyMap();
    client = new MqttHandler(configs);
    queue = new LinkedBlockingQueue<>();
    client.register(SLOTS_TOPIC, this::receiveCombined);
    client.register(CONTROL_TOPIC, this::receiveCommand);
    // client.register(STRING_TOPIC, this::receiveString);
  }

  private void initIAISApi(Map<String, Object> credentials,
      Map<String, Object> confs) {
    String url = (String) confs.get(BASE_URL);
    if (null == url) {
      String error = "No " + BASE_URL + " in config missing or null (obligatory)";
      logger.error("{}", error);
      throw new IllegalArgumentException(error);
    }
    HttpUrl.Builder hb = HttpUrl.parse(url).newBuilder();
    if (confs.containsKey(PORT)) {
      hb.port((int) confs.get(PORT));
    }
    base_url = hb.build();
    basicAuthHeader = getBasicAuthHeader(credentials);
  }


  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void init(Map credentials, Map configs)
      throws IOException, MqttException {
    initMqtt((Map<String, Object>)configs.get("mqtt"));
    initIAISApi((Map<String, Object>) credentials.get("iais"),
        (Map<String, Object>) configs.get("iais"));

    // start processing thread
    isRunning = true;
    proc = new Thread(this);
    proc.setDaemon(true);
    proc.start();
    // initECApi();
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
      if (rw) {
        ResultWindow.getResultWindow().addResult(node);
      }
      // push to queue for asynchronous processing
      queue.add(node);
      return true;
    } catch (JsonProcessingException ex) {
      logger.error("Error converting incoming msg into JSON: {}",
          ex.getMessage());
    }
    return false;
  }

  private static OffsetDateTime toODT(long l) {
    return new Date(l).toInstant().atZone(ZoneId.systemDefault())
        .toOffsetDateTime();
  }

  private static String getString(OffsetDateTime o) {
    return o.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }


  public boolean addMessages(JsonNode node) {
    // Define endpoint and credentials
    HttpUrl url = base_url.newBuilder()
        .addPathSegments(ADD_RADIOMESSAGES_ENDPOINT)
        .build();

    // create the JSON payload
    String jsonPayload = null;
    try {
      jsonPayload = mapper.writeValueAsString(node);
    } catch (JsonProcessingException ex) {
      logger.error("Converting JsonNode failed: {}", ex.getMessage());
    }

    logger.warn("Sending {} to {}", jsonPayload, url.toString());    
    // Create the Request Body
    // In OkHttp 4+, the parameter order is (String content, MediaType
    // contentType)
    RequestBody body = RequestBody.create(jsonPayload, JSON_MEDIA);

    // 6. Build the Request
    Request request = new Request.Builder().url(url)
        .header("Authorization", basicAuthHeader) // Add Basic Auth header
        .put(body).build();

    // 8. Execute the call (Synchronous)
    // Using try-with-resources ensures the response body is closed properly
    try (Response response = getHttpClient().newCall(request).execute()) {
      if (!response.isSuccessful()) {
        logger.error("Unexpected code {}: {}", response.code(),
            response.message());
        return false;
      }

      // Print the response
      logger.debug("Success! Status Code: {}", response.code());
      String resp = response.body().string();
      if (resp != null && ! resp.equals("null")) {
        logger.info("Response Body: \n{}", resp);
      }
    } catch (IOException e) {
      logger.error("API call failed: {}", e.getMessage());
      return false;
    }
    return true;
  }


  public String getRadioMessages(String from, String to) {
    // endpoint is /, only add parameters
    HttpUrl url = base_url.newBuilder()
        .addQueryParameter("start_time", from)
        .addQueryParameter("end_time", to)
        .build();

    // Build the Request
    Request request = new Request.Builder().url(url)
        .header("Authorization", basicAuthHeader) // Add Basic Auth header
        .get()
        .build();

    // 8. Execute the call (Synchronous)
    // Using try-with-resources ensures the response body is closed properly
    try (Response response = getHttpClient().newCall(request).execute()) {
      if (!response.isSuccessful()) {
        logger.error("Unexpected code {}: {}", response.code(),
            response.message());
        return null;
      }

      // Print the response
      logger.debug("Success! Status Code: {}", response.code());
      if (response.body() != null) {
        return response.body().string();
      }
    } catch (IOException e) {
      logger.error("API call failed: {}", e.getMessage());
    }
    return null;
  }


  boolean sendMessageToIAIS(JsonNode msg) {
    ObjectNode node = (ObjectNode) msg;
    // remap slot names
    for (String slot : slotNameMap.keySet()) {
      if (node.has(slot)) {
        JsonNode value = node.remove(slot);
        node.set(slotNameMap.get(slot), value);
      }
    }

    // map the time slots!
    for (String slot : timeSlots) {
      String xsdlong = node.remove(slot).asText();
      long time = xsdToLong(xsdlong);
      OffsetDateTime odt = toODT(time);
      node.set(slot, mapper.valueToTree(getString(odt)));
    }

    ArrayNode arr = mapper.createArrayNode();
    arr.add(msg);
    return addMessages(arr);
  }


  JsonNode getMessagesFromIAIS(long from, long to) {
    OffsetDateTime startTime = toODT(from);
    OffsetDateTime endTime = toODT(to);
    // a list of radio messages
    String response = getRadioMessages(getString(startTime), getString(endTime));
    JsonNode messages = null;
    if (response != null) {
      try {
        messages = mapper.readTree(response);
      } catch (JsonProcessingException ex) {
        logger.error("Error converting response into JSON: {}",
            ex.getMessage());
      }
    }
    return messages;
  }


  /** node also has slots id, intent, frame
   */
  boolean sendFusion(JsonNode node) {
    return sendMessageToIAIS(node);
  }

  @Override
  public void run() {
    while (isRunning) {
      JsonNode n = null;
      try {
        n = queue.take();
      } catch (InterruptedException e) {
      }
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

  private static void usage(OptionParser p, Exception e) throws IOException {
    if (null == e) {
      logger.error("Obligatory credentials file not specified");
    } else {
      logger.error("Error reading credentials file: {}", e.getMessage());
    }

    p.printHelpOn(System.out);
  }

  public static void main(String[] args) throws Exception {
    OptionParser parser = new OptionParser("i");
    parser.accepts("help");
    parser.accepts("config", "Configuration file for (minimally) the base URL")
    .withRequiredArg()
    .required();

    OptionSet options = null;
    Map<String, Object> credentials = new HashMap<>();
    Map<String, Object> configs = new HashMap<>();

    try {
      options = parser.parse(args);
      if (!options.nonOptionArguments().isEmpty()) {
        credentials = readConfig(options.nonOptionArguments().get(0));
      } else {
        usage(parser, null);
        System.exit(1);
      }
      if (options.has("config")) {
        configs = readConfig((String) options.valueOf("config"));
      }
    } catch (Exception e) {
      usage(parser, e);
    }
    Connector conn = new Connector(options.has("i"));
    conn.init(credentials, configs);
    Thread main = new Thread();

    main.start();
    try {
      main.join();
    } catch (InterruptedException ex) {
    }
  }
}
