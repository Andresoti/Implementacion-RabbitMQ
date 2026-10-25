package cl.duoc.pedidos360.worker;
import cl.duoc.pedidos360.testing.LocalJwt;
import cl.duoc.pedidos360.common.OrderEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.UUID;import java.time.Instant;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class WorkerApiTests {
 static final LocalJwt jwt=new LocalJwt();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("app.security.issuer",()->jwt.issuer);}
 @AfterAll static void stop(){jwt.close();}
 @Autowired MockMvc mvc;@Autowired ResultService results;@Autowired JdbcTemplate jdbc;
 @BeforeEach void clean(){jdbc.update("delete from processing_result");jdbc.update("delete from event_identity");}
 @Test void returnsPersistedEnglishResultContractToScopedReader()throws Exception{
  String eventId=UUID.randomUUID().toString();results.process(new OrderEvent(eventId,4,"Ana","Book",2,Instant.now()),Domain.TICKET);
  mvc.perform(get("/api/results").header("Authorization",jwt.bearer("pedidos/read")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
   .andExpect(jsonPath("$[0].eventId").value(eventId)).andExpect(jsonPath("$[0].orderId").value(4))
   .andExpect(jsonPath("$[0].domain").value("TICKET")).andExpect(jsonPath("$[0].result").isString())
   .andExpect(jsonPath("$[0].processedAt").isString()).andExpect(jsonPath("$[0].length()").value(5));
 }
 @Test void readRequiresSignedAccessTokenAndReadScope()throws Exception{
  mvc.perform(get("/api/results")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/results").header("Authorization",jwt.bearer("pedidos/write"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/results").header("Authorization",jwt.token(jwt.claims("pedidos/read").claim("token_use","id"),jwt.key))).andExpect(status().isUnauthorized());
 }
 @Test void deniesUnlistedRoutesAndAllowsOnlyPublicPing()throws Exception{
  mvc.perform(get("/api/public/ping")).andExpect(status().isOk());
  mvc.perform(post("/api/results").header("Authorization",jwt.bearer("pedidos/read"))).andExpect(status().isForbidden());
 }
}
