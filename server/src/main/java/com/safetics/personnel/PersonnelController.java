package com.safetics.personnel;

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

import com.safetics.personnel.PersonnelDtos.PersonnelLoginRequest;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentView;
import com.safetics.projects.AssignmentService;
import com.safetics.personnel.PersonnelDtos.PersonnelLoginResponse;
import com.safetics.personnel.PersonnelDtos.PersonnelRequest;
import com.safetics.personnel.PersonnelDtos.PersonnelView;
import com.safetics.personnel.PersonnelDtos.PositionRequest;
import com.safetics.personnel.PersonnelDtos.PositionView;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Посади (перегляд — усі учасники, зміни — catalog.manage) і виконавці (personnel.manage).
 * Призначення на проєкт — у ProjectController.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "personnel")
public class PersonnelController {

    private final PositionService positions;
    private final PersonnelService personnel;
    private final AssignmentService assignments;

    public PersonnelController(PositionService positions, PersonnelService personnel, AssignmentService assignments) {
        this.positions = positions;
        this.personnel = personnel;
        this.assignments = assignments;
    }

    // ---------------------------------------------------------------- посади

    @GetMapping("/positions")
    public List<PositionView> positions(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return positions.list(includeArchived);
    }

    @PostMapping("/positions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('catalog.manage')")
    public PositionView createPosition(@Valid @RequestBody PositionRequest request) {
        return positions.create(request, null);
    }

    @PutMapping("/positions/{id}")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public PositionView updatePosition(@PathVariable("id") UUID id, @Valid @RequestBody PositionRequest request) {
        return positions.update(id, request);
    }

    @PostMapping("/positions/{id}/archive")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public PositionView archivePosition(@PathVariable("id") UUID id) {
        return positions.setArchived(id, true);
    }

    @PostMapping("/positions/{id}/restore")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public PositionView restorePosition(@PathVariable("id") UUID id) {
        return positions.setArchived(id, false);
    }

    // ---------------------------------------------------------------- виконавці

    @GetMapping("/personnel")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public List<PersonnelView> list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "positionId", required = false) UUID positionId,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return personnel.list(new PersonnelService.Filter(query, positionId, includeArchived));
    }

    @GetMapping("/personnel/{id}")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public PersonnelView get(@PathVariable("id") UUID id) {
        return personnel.get(id);
    }

    /** Історія призначень виконавця (на проєктах, видимих користувачу). */
    @GetMapping("/personnel/{id}/assignments")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public List<PersonnelAssignmentView> assignments(@PathVariable("id") UUID id) {
        return assignments.ofPersonnel(id);
    }

    @PostMapping("/personnel")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('personnel.manage')")
    public PersonnelView create(@Valid @RequestBody PersonnelRequest request) {
        return personnel.create(request);
    }

    @PutMapping("/personnel/{id}")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public PersonnelView update(@PathVariable("id") UUID id, @Valid @RequestBody PersonnelRequest request) {
        return personnel.update(id, request);
    }

    @PostMapping("/personnel/{id}/archive")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public PersonnelView archive(@PathVariable("id") UUID id) {
        return personnel.archive(id);
    }

    @PostMapping("/personnel/{id}/restore")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public PersonnelView restore(@PathVariable("id") UUID id) {
        return personnel.restore(id);
    }

    @PostMapping("/personnel/{id}/login")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public PersonnelLoginResponse grantLogin(@PathVariable("id") UUID id,
                                             @Valid @RequestBody PersonnelLoginRequest request) {
        return personnel.grantLogin(id, request);
    }

    @DeleteMapping("/personnel/{id}/login")
    @PreAuthorize("hasAuthority('personnel.manage')")
    public PersonnelView revokeLogin(@PathVariable("id") UUID id) {
        return personnel.revokeLogin(id);
    }
}
