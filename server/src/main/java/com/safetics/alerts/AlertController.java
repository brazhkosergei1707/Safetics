package com.safetics.alerts;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.alerts.AlertService.AlertsView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Мої алерти: будь-який учасник компанії бачить лише свої. */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public AlertsView mine() {
        return alerts.mine();
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable("id") UUID id) {
        alerts.markRead(id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        alerts.markAllRead();
    }
}
