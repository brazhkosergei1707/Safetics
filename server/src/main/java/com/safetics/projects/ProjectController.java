package com.safetics.projects;

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

import com.safetics.projects.ProjectDtos.GrantAccessRequest;
import com.safetics.projects.ProjectDtos.ProjectAccessView;
import com.safetics.projects.ProjectDtos.ProjectCreateRequest;
import com.safetics.projects.ProjectDtos.ProjectUpdateRequest;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectDtos.ShiftScheduleRequest;
import com.safetics.projects.ProjectDtos.ShiftScheduleView;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Проєкти. Перегляд — кожен учасник компанії, але лише видимі йому проєкти (RLS): адмін і керівник
 * — усі, інші — з доступом, портал замовника — проєкти свого замовника. Зміни — project.manage.
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "projects")
public class ProjectController {

    private final ProjectService projects;
    private final ShiftScheduleService schedules;
    private final ProjectAccessService access;

    public ProjectController(ProjectService projects, ShiftScheduleService schedules, ProjectAccessService access) {
        this.projects = projects;
        this.schedules = schedules;
        this.access = access;
    }

    @GetMapping
    public List<ProjectView> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "customerId", required = false) UUID customerId,
            @RequestParam(name = "q", required = false) String query) {
        return projects.list(new ProjectService.Filter(status, customerId, query));
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable("id") UUID id) {
        return projects.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('project.manage')")
    public ProjectView create(@Valid @RequestBody ProjectCreateRequest request) {
        return projects.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('project.manage')")
    public ProjectView update(@PathVariable("id") UUID id, @Valid @RequestBody ProjectUpdateRequest request) {
        return projects.update(id, request);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('project.manage')")
    public ProjectView close(@PathVariable("id") UUID id) {
        return projects.close(id);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('project.manage')")
    public ProjectView reopen(@PathVariable("id") UUID id) {
        return projects.reopen(id);
    }

    // ---------------------------------------------------------------- графік змін

    @GetMapping("/{id}/shift-schedules")
    public List<ShiftScheduleView> schedules(@PathVariable("id") UUID id) {
        return schedules.list(id);
    }

    @PostMapping("/{id}/shift-schedules")
    @PreAuthorize("hasAuthority('project.manage')")
    public List<ShiftScheduleView> saveSchedule(@PathVariable("id") UUID id,
                                                @Valid @RequestBody ShiftScheduleRequest request) {
        return schedules.save(id, request);
    }

    @DeleteMapping("/{id}/shift-schedules/{scheduleId}")
    @PreAuthorize("hasAuthority('project.manage')")
    public List<ShiftScheduleView> deleteSchedule(@PathVariable("id") UUID id,
                                                  @PathVariable("scheduleId") UUID scheduleId) {
        return schedules.delete(id, scheduleId);
    }

    // ---------------------------------------------------------------- доступ

    @GetMapping("/{id}/access")
    @PreAuthorize("hasAuthority('project.manage')")
    public List<ProjectAccessView> access(@PathVariable("id") UUID id) {
        return access.list(id);
    }

    @PostMapping("/{id}/access")
    @PreAuthorize("hasAuthority('project.manage')")
    public List<ProjectAccessView> grantAccess(@PathVariable("id") UUID id,
                                               @Valid @RequestBody GrantAccessRequest request) {
        return access.grant(id, request.userId());
    }

    @DeleteMapping("/{id}/access/{userId}")
    @PreAuthorize("hasAuthority('project.manage')")
    public List<ProjectAccessView> revokeAccess(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        return access.revoke(id, userId);
    }
}
