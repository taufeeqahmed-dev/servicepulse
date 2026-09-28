package dev.taufeeqahmed.servicepulse.history;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/services")
public class CheckHistoryController {

    private final CheckHistoryService service;

    public CheckHistoryController(CheckHistoryService service) {
        this.service = service;
    }

    @GetMapping("/{id}/checks")
    public ResponseEntity<List<CheckHistoryResponse>> history(@PathVariable long id) {
        return ResponseEntity.of(service.history(id));
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<ServiceStatsResponse> stats(@PathVariable long id) {
        return ResponseEntity.of(service.stats(id));
    }
}
