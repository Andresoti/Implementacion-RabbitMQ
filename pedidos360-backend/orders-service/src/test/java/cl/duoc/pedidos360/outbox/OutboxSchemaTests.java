package cl.duoc.pedidos360.outbox;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class OutboxSchemaTests {
 @Test void validateRefusesToStartWhenOutboxSchemaIsMissing(){
  var datasource=new JdbcDataSource();datasource.setURL("jdbc:h2:mem:missing_outbox;MODE=PostgreSQL");
  var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(datasource);
  factory.setPackagesToScan("cl.duoc.pedidos360.outbox");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
  factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate"));
  try{assertThrows(jakarta.persistence.PersistenceException.class,factory::afterPropertiesSet);}
  finally{factory.destroy();}
 }
}
