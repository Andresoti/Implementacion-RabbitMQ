package cl.duoc.pedidos360.outbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.slf4j.LoggerFactory;
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.outbox.enabled",havingValue="true",matchIfMissing=true)
public class OutboxScheduler {
 private final OutboxRelay relay;
 public OutboxScheduler(OutboxRelay relay){this.relay=relay;}
 @Scheduled(fixedDelayString="${app.outbox.delay-ms:1000}")
 public void tick(){
  try{for(int i=0;i<10 && relay.dispatchOne();i++) { /* bounded batch */ }}
  catch(RuntimeException failure){LoggerFactory.getLogger(OutboxScheduler.class).warn("Outbox cycle deferred: category={}",failure.getClass().getSimpleName());}
 }
}
