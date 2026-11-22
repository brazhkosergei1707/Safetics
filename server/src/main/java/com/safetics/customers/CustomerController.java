package com.safetics.customers;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.customers.CustomerDtos.ContactRequest;
import com.safetics.customers.CustomerDtos.ContactView;
import com.safetics.customers.CustomerDtos.CustomerRequest;
import com.safetics.customers.CustomerDtos.CustomerView;
import com.safetics.customers.CustomerDtos.PortalAccessRequest;
import com.safetics.customers.CustomerDtos.PortalAccessResponse;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Замовники і контактні особи. Читання — client.view, зміни — client.edit.
 * Вхід контактної особи в портал видає той, хто веде проєкти (project.manage).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "customers")
public class CustomerController {

    private final CustomerService customers;
    private final ContactService contacts;

    public CustomerController(CustomerService customers, ContactService contacts) {
        this.customers = customers;
        this.contacts = contacts;
    }

    // ---------------------------------------------------------------- замовники

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('client.view')")
    public List<CustomerView> list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return customers.list(query, includeArchived);
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('client.view')")
    public CustomerView get(@PathVariable("id") UUID id) {
        return customers.get(id);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('client.edit')")
    public CustomerView create(@Valid @RequestBody CustomerRequest request) {
        return customers.create(request);
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('client.edit')")
    public CustomerView update(@PathVariable("id") UUID id, @Valid @RequestBody CustomerRequest request) {
        return customers.update(id, request);
    }

    @PostMapping("/customers/{id}/archive")
    @PreAuthorize("hasAuthority('client.edit')")
    public CustomerView archive(@PathVariable("id") UUID id) {
        return customers.archive(id);
    }

    @PostMapping("/customers/{id}/restore")
    @PreAuthorize("hasAuthority('client.edit')")
    public CustomerView restore(@PathVariable("id") UUID id) {
        return customers.restore(id);
    }

    // ---------------------------------------------------------------- контактні особи

    @GetMapping("/customers/{id}/contacts")
    @PreAuthorize("hasAuthority('client.view')")
    public List<ContactView> contacts(
            @PathVariable("id") UUID customerId,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return contacts.list(customerId, includeArchived);
    }

    @PostMapping("/customers/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('client.edit')")
    public ContactView createContact(@PathVariable("id") UUID customerId, @Valid @RequestBody ContactRequest request) {
        return contacts.create(customerId, request);
    }

    @PutMapping("/customer-contacts/{id}")
    @PreAuthorize("hasAuthority('client.edit')")
    public ContactView updateContact(@PathVariable("id") UUID id, @Valid @RequestBody ContactRequest request) {
        return contacts.update(id, request);
    }

    @PostMapping("/customer-contacts/{id}/archive")
    @PreAuthorize("hasAuthority('client.edit')")
    public ContactView archiveContact(@PathVariable("id") UUID id) {
        return contacts.archive(id);
    }

    @PostMapping("/customer-contacts/{id}/restore")
    @PreAuthorize("hasAuthority('client.edit')")
    public ContactView restoreContact(@PathVariable("id") UUID id) {
        return contacts.restore(id);
    }

    @PostMapping("/customer-contacts/{id}/portal-access")
    @PreAuthorize("hasAuthority('project.manage')")
    public PortalAccessResponse grantPortalAccess(@PathVariable("id") UUID id,
                                                  @Valid @RequestBody PortalAccessRequest request) {
        return contacts.grantPortalAccess(id, request);
    }

    @DeleteMapping("/customer-contacts/{id}/portal-access")
    @PreAuthorize("hasAuthority('project.manage')")
    public ContactView revokePortalAccess(@PathVariable("id") UUID id) {
        return contacts.revokePortalAccess(id);
    }
}
