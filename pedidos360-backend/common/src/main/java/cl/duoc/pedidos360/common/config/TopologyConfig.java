package cl.duoc.pedidos360.common.config;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.amqp.core.*;
import java.util.Map;
@Configuration(proxyBeanMethods=false)
@PropertySource("classpath:shared.properties")
@EnableConfigurationProperties(TopologyProperties.class)
public class TopologyConfig {
 @Bean TopicExchange ordersExchange(TopologyProperties p) {return new TopicExchange(p.ordersExchange(),true,false);}
 @Bean DirectExchange tasksExchange(TopologyProperties p) {return new DirectExchange(p.tasksExchange(),true,false);}
 @Bean DirectExchange deadExchange(TopologyProperties p) {return new DirectExchange(p.deadExchange(),true,false);}
 @Bean Queue notificationQueue(TopologyProperties p) {return new Queue(p.notificationQueue(),true,false,false,Map.of("x-queue-type","classic","x-dead-letter-exchange",p.deadExchange(),"x-dead-letter-routing-key",p.notificationFailure()));}
 @Bean Queue notificationDlq(TopologyProperties p) {return new Queue(p.notificationDlq(),true,false,false,Map.of("x-queue-type","classic"));}
 @Bean Binding notificationBinding(TopologyProperties p) {return new Binding(p.notificationQueue(),Binding.DestinationType.QUEUE,p.ordersExchange(),p.notificationBinding(),null);}
 @Bean Binding notificationDeadBinding(TopologyProperties p) {return new Binding(p.notificationDlq(),Binding.DestinationType.QUEUE,p.deadExchange(),p.notificationFailure(),null);}
 @Bean Queue documentQueue(TopologyProperties p) {return new Queue(p.documentQueue(),true,false,false,Map.of("x-queue-type","classic","x-dead-letter-exchange",p.deadExchange(),"x-dead-letter-routing-key",p.documentFailure()));}
 @Bean Queue documentDlq(TopologyProperties p) {return new Queue(p.documentDlq(),true,false,false,Map.of("x-queue-type","classic"));}
 @Bean Binding documentBinding(TopologyProperties p) {return new Binding(p.documentQueue(),Binding.DestinationType.QUEUE,p.ordersExchange(),p.documentBinding(),null);}
 @Bean Binding documentDeadBinding(TopologyProperties p) {return new Binding(p.documentDlq(),Binding.DestinationType.QUEUE,p.deadExchange(),p.documentFailure(),null);}
 @Bean Queue ticketQueue(TopologyProperties p) {return new Queue(p.ticketQueue(),true,false,false,Map.of("x-queue-type","classic","x-dead-letter-exchange",p.deadExchange(),"x-dead-letter-routing-key",p.ticketFailure()));}
 @Bean Queue ticketDlq(TopologyProperties p) {return new Queue(p.ticketDlq(),true,false,false,Map.of("x-queue-type","classic"));}
 @Bean Binding ticketBinding(TopologyProperties p) {return new Binding(p.ticketQueue(),Binding.DestinationType.QUEUE,p.tasksExchange(),p.ticketRoute(),null);}
 @Bean Binding ticketDeadBinding(TopologyProperties p) {return new Binding(p.ticketDlq(),Binding.DestinationType.QUEUE,p.deadExchange(),p.ticketFailure(),null);}
}
