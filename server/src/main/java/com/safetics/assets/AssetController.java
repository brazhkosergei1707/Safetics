package com.safetics.assets;

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

import com.safetics.assets.AssetDtos.AssetCreateRequest;
import com.safetics.assets.AssetDtos.AssetPage;
import com.safetics.assets.AssetDtos.AssetUpdateRequest;
import com.safetics.assets.AssetDtos.AssetView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Об'єкти: свердловини, бурові установки, авто. Читання — asset.view, зміни — asset.edit. */
@RestController
@RequestMapping("/api/v1/assets")
@Tag(name = "assets")
public class AssetController {

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('asset.view')")
    public AssetPage list(
            @RequestParam(name = "typeId", required = false) UUID typeId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "parentId", required = false) UUID parentId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return assets.list(new AssetService.Filter(typeId, status, parentId, query, includeArchived, limit, offset));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('asset.view')")
    public AssetView get(@PathVariable("id") UUID id) {
        return assets.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('asset.edit')")
    public AssetView create(@Valid @RequestBody AssetCreateRequest request) {
        return assets.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('asset.edit')")
    public AssetView update(@PathVariable("id") UUID id, @Valid @RequestBody AssetUpdateRequest request) {
        return assets.update(id, request);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('asset.edit')")
    public AssetView archive(@PathVariable("id") UUID id) {
        return assets.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('asset.edit')")
    public AssetView restore(@PathVariable("id") UUID id) {
        return assets.restore(id);
    }
}
