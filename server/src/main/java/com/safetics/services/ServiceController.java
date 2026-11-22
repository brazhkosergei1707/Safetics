package com.safetics.services;

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

import com.safetics.services.ServiceDtos.CancelServiceRequest;
import com.safetics.services.ServiceDtos.ServiceCreateRequest;
import com.safetics.services.ServiceDtos.ServiceTypeRequest;
import com.safetics.services.ServiceDtos.ServiceTypeView;
import com.safetics.services.ServiceDtos.ServiceUpdateRequest;
import com.safetics.services.ServiceDtos.ServiceView;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Типи послуг (перегляд — усі, зміни — catalog.manage) і послуги проєкту. Хто може змінювати
 * послугу, вирішує ServiceWorkflow: менеджер, супервайзер проєкту, замовник (лише заплановані).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "services")
public class ServiceController {

    private final ServiceTypeService types;
    private final ServiceWorkflow services;

    public ServiceController(ServiceTypeService types, ServiceWorkflow services) {
        this.types = types;
        this.services = services;
    }

    // ---------------------------------------------------------------- типи

    @GetMapping("/service-types")
    public List<ServiceTypeView> types(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return types.list(includeArchived);
    }

    @PostMapping("/service-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ServiceTypeView createType(@Valid @RequestBody ServiceTypeRequest request) {
        return types.create(request, null);
    }

    @PutMapping("/service-types/{id}")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ServiceTypeView updateType(@PathVariable("id") UUID id, @Valid @RequestBody ServiceTypeRequest request) {
        return types.update(id, request);
    }

    @PostMapping("/service-types/{id}/archive")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ServiceTypeView archiveType(@PathVariable("id") UUID id) {
        return types.setArchived(id, true);
    }

    @PostMapping("/service-types/{id}/restore")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ServiceTypeView restoreType(@PathVariable("id") UUID id) {
        return types.setArchived(id, false);
    }

    // ---------------------------------------------------------------- послуги

    @GetMapping("/projects/{projectId}/services")
    public List<ServiceView> list(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "typeId", required = false) UUID typeId) {
        return services.list(projectId, status, typeId);
    }

    @PostMapping("/projects/{projectId}/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceView create(@PathVariable("projectId") UUID projectId, @Valid @RequestBody ServiceCreateRequest request) {
        return services.create(projectId, request);
    }

    @GetMapping("/services/{id}")
    public ServiceView get(@PathVariable("id") UUID id) {
        return services.get(id);
    }

    @PutMapping("/services/{id}")
    public ServiceView update(@PathVariable("id") UUID id, @Valid @RequestBody ServiceUpdateRequest request) {
        return services.update(id, request);
    }

    @PostMapping("/services/{id}/start")
    public ServiceView start(@PathVariable("id") UUID id) {
        return services.start(id);
    }

    @PostMapping("/services/{id}/complete")
    public ServiceView complete(@PathVariable("id") UUID id) {
        return services.complete(id);
    }

    @PostMapping("/services/{id}/cancel")
    public ServiceView cancel(@PathVariable("id") UUID id, @Valid @RequestBody CancelServiceRequest request) {
        return services.cancel(id, request.reason());
    }

    @PostMapping("/services/{id}/reopen")
    public ServiceView reopen(@PathVariable("id") UUID id) {
        return services.reopen(id);
    }
}
