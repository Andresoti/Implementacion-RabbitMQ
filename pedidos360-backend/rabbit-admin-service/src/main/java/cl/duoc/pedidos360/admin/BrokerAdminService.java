package cl.duoc.pedidos360.admin;
import org.springframework.amqp.core.*;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service
public class BrokerAdminService {
 private final AmqpAdmin broker;
 public BrokerAdminService(AmqpAdmin broker){this.broker=broker;}
 public void createQueue(QueueRequest request){
  String declared=broker.declareQueue(new Queue(request.name(),request.durable(),false,false,Map.of("x-queue-type","classic")));
  if(!request.name().equals(declared))throw new org.springframework.amqp.AmqpException("Queue declaration was not confirmed");
 }

 public void createExchange(ExchangeRequest request){
  Exchange exchange=switch(request.type()){
   case DIRECT->new DirectExchange(request.name(),request.durable(),false);
   case TOPIC->new TopicExchange(request.name(),request.durable(),false);
  };
  broker.declareExchange(exchange);
 }
 private Binding binding(BindingRequest request){return new Binding(request.queue(),Binding.DestinationType.QUEUE,request.exchange(),request.routingKey(),null);}
 public void createBinding(BindingRequest request){broker.declareBinding(binding(request));}
 public void deleteBinding(BindingRequest request){broker.removeBinding(binding(request));}
 public void deleteQueue(String name){broker.deleteQueue(name,true,true);}
 public void deleteExchange(String name){
  if(!broker.deleteExchange(name))throw new org.springframework.amqp.AmqpException("Exchange deletion was not confirmed");
 }
}
