package com.safetics.assets;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.assets.AssetDtos.AssetTypeRequest;
import com.safetics.assets.AssetDtos.AssetTypeView;
import com.safetics.assets.AssetDtos.InstallTemplateRequest;
import com.safetics.assets.AssetDtos.InstallTemplateResponse;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Типи об'єктів: читають усі з asset.view, змінюють власник і адмін (catalog.manage). */
@RestController
@RequestMapping("/api/v1/asset-types")
@Tag(name = "assets")
public class AssetTypeController {

    private final AssetTypeService types;
    private final TemplateService templates;

    public AssetTypeController(AssetTypeService types, TemplateService templates) {
        this.types = types;
        this.templates = templates;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('asset.view')")
    public List<AssetTypeView> list(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return types.list(includeArchived);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('asset.view')")
    public AssetTypeView get(@PathVariable("id") UUID id) {
        return types.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('catalog.manage')")
    public AssetTypeView create(@Valid @RequestBody AssetTypeRequest request) {
        return types.create(request, null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public AssetTypeView update(@PathVariable("id") UUID id, @Valid @RequestBody AssetTypeRequest request) {
        return types.update(id, request);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public AssetTypeView archive(@PathVariable("id") UUID id) {
        return types.setArchived(id, true);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public AssetTypeView restore(@PathVariable("id") UUID id) {
        return types.setArchived(id, false);
    }

    /** Встановити типи з галузевого шаблону (exploration-drilling). Наявні типи не змінюються. */
    @PostMapping("/install-template")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public InstallTemplateResponse installTemplate(@Valid @RequestBody InstallTemplateRequest request) {
        return templates.install(request.templateKey());
    }
}
