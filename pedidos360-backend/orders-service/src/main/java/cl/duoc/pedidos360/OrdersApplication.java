package cl.duoc.pedidos360;
import cl.duoc.pedidos360.common.config.SecurityConfig;
import cl.duoc.pedidos360.common.controller.PublicController;
import cl.duoc.pedidos360.common.error.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
@SpringBootApplication
@Import({SecurityConfig.class, cl.duoc.pedidos360.common.config.TopologyConfig.class, PublicController.class, ApiExceptionHandler.class})
public class OrdersApplication { public static void main(String[] args) {SpringApplication.run(OrdersApplication.class,args);} }
