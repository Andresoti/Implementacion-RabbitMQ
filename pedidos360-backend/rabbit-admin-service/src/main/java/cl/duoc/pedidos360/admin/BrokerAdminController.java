package cl.duoc.pedidos360.admin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/rabbitmq")
public class BrokerAdminController {
 private final BrokerAdminService service;
 public BrokerAdminController(BrokerAdminService service){this.service=service;}
 @PostMapping("/queues") @ResponseStatus(HttpStatus.CREATED)
 public Map<String,String> createQueue(@Valid @RequestBody QueueRequest request){service.createQueue(request);return Map.of("name",request.name());}

 @PostMapping("/exchanges") @ResponseStatus(HttpStatus.CREATED)
 public Map<String,String> createExchange(@Valid @RequestBody ExchangeRequest request){service.createExchange(request);return Map.of("name",request.name());}
 @PostMapping("/bindings") @ResponseStatus(HttpStatus.CREATED)
 public BindingRequest createBinding(@Valid @RequestBody BindingRequest request){service.createBinding(request);return request;}
 @DeleteMapping("/bindings") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void deleteBinding(@Valid @RequestBody BindingRequest request){service.deleteBinding(request);}
 @DeleteMapping("/queues/{name}") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void deleteQueue(@PathVariable @Pattern(regexp=ResourceName.PATTERN) String name){service.deleteQueue(name);}
 @DeleteMapping("/exchanges/{name}") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void deleteExchange(@PathVariable @Pattern(regexp=ResourceName.PATTERN) String name){service.deleteExchange(name);}
}
