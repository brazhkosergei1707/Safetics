package com.safetics.reports;

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

import com.safetics.reports.ReportCatalogDtos.ActivityCodeRequest;
import com.safetics.reports.ReportCatalogDtos.ActivityCodeView;
import com.safetics.reports.ReportCatalogDtos.ConsumableItemRequest;
import com.safetics.reports.ReportCatalogDtos.ConsumableItemView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Довідники рапорту: перегляд — усі учасники компанії, зміни — catalog.manage. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "report-catalogs")
public class ReportCatalogController {

    private final ActivityCodeService codes;
    private final ConsumableItemService items;

    public ReportCatalogController(ActivityCodeService codes, ConsumableItemService items) {
        this.codes = codes;
        this.items = items;
    }

    @GetMapping("/activity-codes")
    public List<ActivityCodeView> codes(@RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return codes.list(includeArchived);
    }

    @PostMapping("/activity-codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ActivityCodeView createCode(@Valid @RequestBody ActivityCodeRequest request) {
        return codes.create(request, null);
    }

    @PutMapping("/activity-codes/{id}")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ActivityCodeView updateCode(@PathVariable("id") UUID id, @Valid @RequestBody ActivityCodeRequest request) {
        return codes.update(id, request);
    }

    @PostMapping("/activity-codes/{id}/archive")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ActivityCodeView archiveCode(@PathVariable("id") UUID id) {
        return codes.setArchived(id, true);
    }

    @PostMapping("/activity-codes/{id}/restore")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ActivityCodeView restoreCode(@PathVariable("id") UUID id) {
        return codes.setArchived(id, false);
    }

    @GetMapping("/consumable-items")
    public List<ConsumableItemView> items(@RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return items.list(includeArchived);
    }

    @PostMapping("/consumable-items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ConsumableItemView createItem(@Valid @RequestBody ConsumableItemRequest request) {
        return items.create(request, null);
    }

    @PutMapping("/consumable-items/{id}")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ConsumableItemView updateItem(@PathVariable("id") UUID id, @Valid @RequestBody ConsumableItemRequest request) {
        return items.update(id, request);
    }

    @PostMapping("/consumable-items/{id}/archive")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ConsumableItemView archiveItem(@PathVariable("id") UUID id) {
        return items.setArchived(id, true);
    }

    @PostMapping("/consumable-items/{id}/restore")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ConsumableItemView restoreItem(@PathVariable("id") UUID id) {
        return items.setArchived(id, false);
    }
}
