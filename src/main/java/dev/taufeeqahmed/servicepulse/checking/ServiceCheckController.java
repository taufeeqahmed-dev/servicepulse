package dev.taufeeqahmed.servicepulse.checking;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/services")
public class ServiceCheckController {

    private final ServiceCheckService service;

    public ServiceCheckController(ServiceCheckService service) {
        this.service = service;
    }

    @PostMapping("/{id}/check")
    public ResponseEntity<HealthCheckResponse> check(@PathVariable long id) {
        return ResponseEntity.of(service.check(id));
    }
}
