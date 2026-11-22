package com.safetics.platform;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.assets.TemplateService;
import com.safetics.assets.TemplateService.TemplateSummary;
import com.safetics.platform.PlatformDtos.CreateCompanyRequest;
import com.safetics.platform.PlatformDtos.CreateCompanyResponse;
import com.safetics.platform.PlatformDtos.PlatformCompanyView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Компанії платформи. Лише адмін платформи (authority platform.admin). */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "platform")
@PreAuthorize("hasAuthority('platform.admin')")
public class PlatformController {

    private final PlatformService platform;
    private final TemplateService templates;

    public PlatformController(PlatformService platform, TemplateService templates) {
        this.platform = platform;
        this.templates = templates;
    }

    @GetMapping("/templates")
    public List<TemplateSummary> templates() {
        return templates.list();
    }

    @GetMapping("/companies")
    public List<PlatformCompanyView> companies() {
        return platform.list();
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateCompanyResponse create(@Valid @RequestBody CreateCompanyRequest request) {
        return platform.create(request);
    }

    @PostMapping("/companies/{id}/suspend")
    public PlatformCompanyView suspend(@PathVariable("id") UUID id) {
        return platform.setStatus(id, "suspended");
    }

    @PostMapping("/companies/{id}/activate")
    public PlatformCompanyView activate(@PathVariable("id") UUID id) {
        return platform.setStatus(id, "active");
    }
}
