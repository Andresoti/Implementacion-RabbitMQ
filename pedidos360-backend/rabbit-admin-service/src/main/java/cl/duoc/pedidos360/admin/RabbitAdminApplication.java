package cl.duoc.pedidos360.admin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import cl.duoc.pedidos360.common.config.*;
import cl.duoc.pedidos360.common.controller.PublicController;
import cl.duoc.pedidos360.common.error.ApiExceptionHandler;
@SpringBootApplication
@Import({SecurityConfig.class,TopologyConfig.class,PublicController.class,ApiExceptionHandler.class})
public class RabbitAdminApplication {public static void main(String[] args){SpringApplication.run(RabbitAdminApplication.class,args);}}
