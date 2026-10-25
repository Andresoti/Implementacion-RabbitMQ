package cl.duoc.pedidos360.outbox;
import cl.duoc.pedidos360.common.config.TopologyProperties;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
@Service
public class OutboxRelay {
 private static final Logger log=LoggerFactory.getLogger(OutboxRelay.class);
 private final JdbcTemplate jdbc;private final ConfirmedPublisher publisher;private final TopologyProperties topology;
 public OutboxRelay(JdbcTemplate jdbc,ConfirmedPublisher publisher,TopologyProperties topology){this.jdbc=jdbc;this.publisher=publisher;this.topology=topology;}
 @Transactional
 public boolean dispatchOne(){
  // The row lock spans broker confirmation and commit. Other relays skip this intent.
  var rows=jdbc.query("select id,event_id,destination,payload,attempts from outbox where published_at is null and next_attempt_at<=CURRENT_TIMESTAMP order by id limit 1 for update skip locked",
   (rs,n)->new Intent(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)));
  if(rows.isEmpty())return false;
  var intent=rows.get(0); boolean ticket=intent.destination().equals("TICKET");
  try {
   publisher.publish(ticket?topology.tasksExchange():topology.ordersExchange(),ticket?topology.ticketRoute():topology.orderRoute(),intent.eventId(),intent.payload());
   jdbc.update("update outbox set published_at=CURRENT_TIMESTAMP where id=?",intent.id());
  } catch(org.springframework.amqp.AmqpException|IllegalStateException failure) {
   long delay=Math.min(300,5L*(1L<<Math.min(intent.attempts(),6)));
   jdbc.update("update outbox set attempts=attempts+1,next_attempt_at=? where id=?",Timestamp.from(Instant.now().plusSeconds(delay)),intent.id());
   log.warn("Outbox publication pending: intentId={} category={}",intent.id(),failure.getClass().getSimpleName());
  }
  return true;
 }
 private record Intent(long id,String eventId,String destination,String payload,int attempts){}
}
