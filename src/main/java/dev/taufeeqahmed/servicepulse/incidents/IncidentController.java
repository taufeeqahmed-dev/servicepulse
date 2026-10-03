package dev.taufeeqahmed.servicepulse.incidents;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IncidentController {

    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping("/incidents")
    public List<IncidentResponse> list(@RequestParam(required = false) IncidentStatus status) {
        return incidents.list(status);
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<IncidentResponse> find(@PathVariable long id) {
        return ResponseEntity.of(incidents.find(id));
    }

    @GetMapping("/services/{serviceId}/incidents")
    public ResponseEntity<List<IncidentResponse>> history(@PathVariable long serviceId) {
        return ResponseEntity.of(incidents.history(serviceId));
    }
}
