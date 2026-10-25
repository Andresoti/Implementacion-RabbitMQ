package cl.duoc.pedidos360.dto;

import cl.duoc.pedidos360.model.Pedido;

public record PedidoResponse(Long id, String cliente, String producto, Integer cantidad, String estado) {
    public static PedidoResponse from(Pedido pedido) {
        return new PedidoResponse(pedido.getId(), pedido.getCliente(), pedido.getProducto(),
                pedido.getCantidad(), pedido.getEstado());
    }
}
