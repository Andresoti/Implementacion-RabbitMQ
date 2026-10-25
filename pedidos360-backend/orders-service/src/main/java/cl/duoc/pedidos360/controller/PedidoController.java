package cl.duoc.pedidos360.controller;

import jakarta.validation.Valid;

import cl.duoc.pedidos360.dto.CreatePedidoRequest;
import cl.duoc.pedidos360.dto.PedidoResponse;
import cl.duoc.pedidos360.service.PedidoService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {
    private final PedidoService service;

    public PedidoController(PedidoService service) { this.service = service; }

    @GetMapping
    public List<PedidoResponse> list() { return service.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PedidoResponse create(@Valid @RequestBody CreatePedidoRequest request) {
        return service.create(request);
    }
}
