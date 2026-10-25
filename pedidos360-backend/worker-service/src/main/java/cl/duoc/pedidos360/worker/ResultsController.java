package cl.duoc.pedidos360.worker;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
public class ResultsController {
 private final ResultService results;
 public ResultsController(ResultService results){this.results=results;}
 @GetMapping("/api/results") public List<ProcessingResult> list(){return results.list();}
}
