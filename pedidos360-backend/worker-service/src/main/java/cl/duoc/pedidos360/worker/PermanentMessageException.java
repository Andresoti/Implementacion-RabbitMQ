package cl.duoc.pedidos360.worker;
public class PermanentMessageException extends RuntimeException {
 public PermanentMessageException(){super("Invalid event or identity conflict");}
}
