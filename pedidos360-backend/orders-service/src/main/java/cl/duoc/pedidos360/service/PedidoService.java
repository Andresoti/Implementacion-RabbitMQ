package cl.duoc.pedidos360.service;

import cl.duoc.pedidos360.dto.CreatePedidoRequest;
import cl.duoc.pedidos360.dto.PedidoResponse;
import cl.duoc.pedidos360.model.Pedido;
import cl.duoc.pedidos360.repository.PedidoRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PedidoService {
    private final PedidoRepository repository;
    private final cl.duoc.pedidos360.outbox.OutboxStore outbox;

    public PedidoService(PedidoRepository repository, cl.duoc.pedidos360.outbox.OutboxStore outbox) { this.repository = repository; this.outbox=outbox; }

    public List<PedidoResponse> list() {
        return repository.findAll().stream().map(PedidoResponse::from).toList();
    }

    @Transactional
    public PedidoResponse create(CreatePedidoRequest request) {
        var pedido = new Pedido(null, request.cliente(), request.producto(), request.cantidad(), "CREATED");
        repository.saveAndFlush(pedido);
        outbox.stage(new cl.duoc.pedidos360.common.OrderEvent(java.util.UUID.randomUUID().toString(), pedido.getId(), pedido.getCliente(), pedido.getProducto(), pedido.getCantidad(), java.time.Instant.now()));
        return PedidoResponse.from(pedido);
    }
}
