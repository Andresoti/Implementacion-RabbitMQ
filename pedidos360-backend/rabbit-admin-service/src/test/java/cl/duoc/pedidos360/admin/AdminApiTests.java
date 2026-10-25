package cl.duoc.pedidos360.admin;
import cl.duoc.pedidos360.testing.LocalJwt;
import org.springframework.amqp.core.*;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @AutoConfigureMockMvc @ActiveProfiles("test")
class AdminApiTests {
 static final LocalJwt jwt=new LocalJwt();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("app.security.issuer",()->jwt.issuer);}
 @AfterAll static void stop(){jwt.close();}
 @Autowired MockMvc mvc;@MockitoBean AmqpAdmin broker;
 @org.springframework.boot.test.web.server.LocalServerPort int httpPort;
 @BeforeEach void clean(){reset(broker);when(broker.declareQueue(any(Queue.class))).thenAnswer(call->call.<Queue>getArgument(0).getName());}
 @Test void realHttpPreservesMediaTypeFailureWithoutBrokerMutation()throws Exception{
  var request=java.net.http.HttpRequest.newBuilder()
   .uri(java.net.URI.create("http://127.0.0.1:"+httpPort+"/api/rabbitmq/queues"))
   .header("Authorization",jwt.bearer("rabbitmq/admin")).header("Content-Type","text/plain")
   .POST(java.net.http.HttpRequest.BodyPublishers.ofString("not-json")).build();
  var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
  Assertions.assertEquals(415,response.statusCode());
  verifyNoInteractions(broker);
 }
 @Test void createsValidatedQueueThroughDedicatedAdminService()throws Exception{
  mvc.perform(post("/api/rabbitmq/queues").header("Authorization",jwt.bearer("rabbitmq/admin"))
   .contentType("application/json").content("{\"name\":\"test.queue\",\"durable\":true}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("test.queue"));
  verify(broker).declareQueue(argThat(q->q.getName().equals("test.queue")&&q.isDurable()&&!q.isAutoDelete()&&!q.isExclusive()));
 }
 @ParameterizedTest @ValueSource(strings={"{}","{\"name\":\"amq.reserved\",\"durable\":true}","{\"name\":\"bad/name\",\"durable\":true}","{\"name\":\"test.queue\"}","null","{"})
 void rejectsInvalidQueueWithoutBrokerMutation(String body)throws Exception{
  mvc.perform(post("/api/rabbitmq/queues").header("Authorization",jwt.bearer("rabbitmq/admin"))
   .contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}"));
  verify(broker,never()).declareQueue(any(Queue.class));
 }
 @Test void createsExchangeAndBindingThenDeletesAllThreeResources()throws Exception{
  String auth=jwt.bearer("rabbitmq/admin");
  mvc.perform(post("/api/rabbitmq/exchanges").header("Authorization",auth).contentType("application/json")
   .content("{\"name\":\"test.exchange\",\"type\":\"TOPIC\",\"durable\":true}"))
   .andExpect(status().isCreated());
  verify(broker).declareExchange(argThat(e->e.getName().equals("test.exchange")&&e.getType().equals("topic")&&e.isDurable()));
  String binding="{\"exchange\":\"test.exchange\",\"queue\":\"test.queue\",\"routingKey\":\"test.*\"}";
  mvc.perform(post("/api/rabbitmq/bindings").header("Authorization",auth).contentType("application/json").content(binding)).andExpect(status().isCreated());
  verify(broker).declareBinding(argThat(b->b.getExchange().equals("test.exchange")&&b.getDestination().equals("test.queue")&&b.getRoutingKey().equals("test.*")));
  mvc.perform(delete("/api/rabbitmq/bindings").header("Authorization",auth).contentType("application/json").content(binding)).andExpect(status().isNoContent());
  verify(broker).removeBinding(any(Binding.class));
  mvc.perform(delete("/api/rabbitmq/queues/test.queue").header("Authorization",auth)).andExpect(status().isNoContent());
  verify(broker).deleteQueue("test.queue",true,true);
  when(broker.deleteExchange("test.exchange")).thenReturn(true);
  mvc.perform(delete("/api/rabbitmq/exchanges/test.exchange").header("Authorization",auth)).andExpect(status().isNoContent());
  verify(broker).deleteExchange("test.exchange");
 }
 @Test void rejectsInvalidExchangeBindingAndDeleteNames()throws Exception{
  String auth=jwt.bearer("rabbitmq/admin");
  mvc.perform(post("/api/rabbitmq/exchanges").header("Authorization",auth).contentType("application/json")
   .content("{\"name\":\"test.exchange\",\"type\":\"INVALID\",\"durable\":true}"))
   .andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}"));
  mvc.perform(post("/api/rabbitmq/bindings").header("Authorization",auth).contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest());
  mvc.perform(delete("/api/rabbitmq/queues/amq.reserved").header("Authorization",auth))
   .andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}"));
 }
 @Test void brokerFailureDoesNotReturnSuccessOrExposeItsDetails()throws Exception{
  doThrow(new AmqpConnectException(new java.net.ConnectException("secret-host:5672"))).when(broker).declareQueue(any(Queue.class));
  mvc.perform(post("/api/rabbitmq/queues").header("Authorization",jwt.bearer("rabbitmq/admin"))
   .contentType("application/json").content("{\"name\":\"test.queue\",\"durable\":true}"))
   .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"error\":\"service_unavailable\"}"));
 }
 @Test void nullQueueDeclarationIsNotReportedAsCreated()throws Exception{
  when(broker.declareQueue(any(Queue.class))).thenReturn(null);
  mvc.perform(post("/api/rabbitmq/queues").header("Authorization",jwt.bearer("rabbitmq/admin"))
   .contentType("application/json").content("{\"name\":\"test.queue\",\"durable\":true}"))
   .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"error\":\"service_unavailable\"}"));
 }
 @Test void failedExchangeDeletionIsNotReportedAsSuccessful()throws Exception{
  when(broker.deleteExchange("test.exchange")).thenReturn(false);
  mvc.perform(delete("/api/rabbitmq/exchanges/test.exchange").header("Authorization",jwt.bearer("rabbitmq/admin")))
   .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"error\":\"service_unavailable\"}"));
 }
 @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("invalidRoutingKeys")
 void rejectsUnencodableRoutingKeysBeforeBrokerMutation(String routingKey)throws Exception{
  mvc.perform(post("/api/rabbitmq/bindings").header("Authorization",jwt.bearer("rabbitmq/admin"))
   .contentType("application/json").content("{\"exchange\":\"test.exchange\",\"queue\":\"test.queue\",\"routingKey\":\""+routingKey+"\"}"))
   .andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}"));
  verify(broker,never()).declareBinding(any(Binding.class));
 }
 static java.util.stream.Stream<String> invalidRoutingKeys(){return java.util.stream.Stream.of("é".repeat(128),"prefix\\u0000suffix","\\uD800","\\uDC00");}
 @ParameterizedTest @ValueSource(strings={"","unicode"})
 void acceptsValidRoutingKeyByteBoundaries(String kind)throws Exception{
  String route=kind.isEmpty()?"":"é".repeat(127)+"a";
  mvc.perform(post("/api/rabbitmq/bindings").header("Authorization",jwt.bearer("rabbitmq/admin"))
   .contentType("application/json").content("{\"exchange\":\"test.exchange\",\"queue\":\"test.queue\",\"routingKey\":\""+route+"\"}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(3));
  verify(broker).declareBinding(argThat(binding->binding.getRoutingKey().equals(route)));
 }
 @Test void adminCorsAllowsDeleteButDoesNotBroadenOrdersCors()throws Exception{
  mvc.perform(options("/api/rabbitmq/queues/test.queue").header("Origin","http://localhost:4200").header("Access-Control-Request-Method","DELETE"))
   .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Methods","POST,DELETE,OPTIONS"));
 }
 @Test void adminRequiresItsOwnScopeAndSignature()throws Exception{
  mvc.perform(post("/api/rabbitmq/queues")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/rabbitmq/queues").header("Authorization",jwt.bearer("pedidos/read pedidos/write"))).andExpect(status().isForbidden());
  mvc.perform(post("/api/rabbitmq/queues").header("Authorization",jwt.token(jwt.claims("rabbitmq/admin").claim("client_id","wrong"),jwt.key))).andExpect(status().isUnauthorized());
 }
}
