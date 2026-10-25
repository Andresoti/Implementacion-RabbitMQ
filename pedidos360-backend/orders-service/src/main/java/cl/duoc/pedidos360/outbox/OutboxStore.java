package cl.duoc.pedidos360.outbox;
import cl.duoc.pedidos360.common.OrderEvent;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class OutboxStore {
 private final JdbcTemplate jdbc; private final ObjectMapper mapper;
 public OutboxStore(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 public void stage(OrderEvent event) {
  String payload=mapper.writeValueAsString(event);
  for(String destination: new String[]{"ORDER","TICKET"}) {
   jdbc.update("insert into outbox(event_id,destination,payload,attempts,next_attempt_at) values(?,?,?,0,CURRENT_TIMESTAMP)",event.eventId(),destination,payload);
  }
 }
}
