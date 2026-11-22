package com.safetics.projects;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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

import com.safetics.projects.AssignmentDtos.AssignmentDatesRequest;
import com.safetics.projects.AssignmentDtos.AvailableEquipmentView;
import com.safetics.projects.AssignmentDtos.CrewMemberRequest;
import com.safetics.projects.AssignmentDtos.EquipmentAssignmentRequest;
import com.safetics.projects.AssignmentDtos.EquipmentAssignmentView;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentRequest;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentUpdateRequest;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentView;
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
    private final AssignmentService assignments;

    public ProjectController(ProjectService projects, ShiftScheduleService schedules, ProjectAccessService access,
                             AssignmentService assignments) {
        this.projects = projects;
        this.schedules = schedules;
        this.access = access;
        this.assignments = assignments;
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
        return assignments.closeProject(id);
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

    // ---------------------------------------------------------------- люди на проєкті

    @GetMapping("/{id}/personnel-assignments")
    public List<PersonnelAssignmentView> personnelAssignments(@PathVariable("id") UUID id) {
        return assignments.personnel(id);
    }

    @PostMapping("/{id}/personnel-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('project.manage')")
    public PersonnelAssignmentView assignPersonnel(@PathVariable("id") UUID id,
                                                   @Valid @RequestBody PersonnelAssignmentRequest request) {
        return assignments.assignPersonnel(id, request);
    }

    @PutMapping("/{id}/personnel-assignments/{assignmentId}")
    @PreAuthorize("hasAuthority('project.manage')")
    public PersonnelAssignmentView updatePersonnelAssignment(@PathVariable("id") UUID id,
                                                             @PathVariable("assignmentId") UUID assignmentId,
                                                             @Valid @RequestBody PersonnelAssignmentUpdateRequest request) {
        return assignments.updatePersonnelAssignment(id, assignmentId, request);
    }

    @DeleteMapping("/{id}/personnel-assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('project.manage')")
    public void deletePersonnelAssignment(@PathVariable("id") UUID id, @PathVariable("assignmentId") UUID assignmentId) {
        assignments.deletePersonnelAssignment(id, assignmentId);
    }

    /**
     * Нова людина без входу + призначення. Менеджер (project.manage і personnel.manage) — будь-яка
     * посада; супервайзер проєкту — лише посади «може додавати супервайзер».
     */
    @PostMapping("/{id}/crew")
    @ResponseStatus(HttpStatus.CREATED)
    public PersonnelAssignmentView addCrewMember(@PathVariable("id") UUID id,
                                                 @Valid @RequestBody CrewMemberRequest request,
                                                 Authentication authentication) {
        boolean canManage = authentication.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.equals("project.manage") || a.equals("personnel.manage")).distinct().count() == 2;
        return assignments.addCrewMember(id, request, canManage);
    }

    // ---------------------------------------------------------------- обладнання на проєкті

    @GetMapping("/{id}/equipment-assignments")
    public List<EquipmentAssignmentView> equipmentAssignments(@PathVariable("id") UUID id) {
        return assignments.equipment(id);
    }

    /** Обладнання для вибору: вільне чи зайняте на дати (за замовчуванням — з сьогодні, безстроково). */
    @GetMapping("/{id}/available-equipment")
    @PreAuthorize("hasAuthority('project.manage')")
    public List<AvailableEquipmentView> availableEquipment(
            @PathVariable("id") UUID id,
            @RequestParam(name = "startDate", required = false) java.time.LocalDate startDate,
            @RequestParam(name = "endDate", required = false) java.time.LocalDate endDate) {
        return assignments.available(id, startDate, endDate);
    }

    @PostMapping("/{id}/equipment-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('project.manage')")
    public EquipmentAssignmentView assignEquipment(@PathVariable("id") UUID id,
                                                   @Valid @RequestBody EquipmentAssignmentRequest request) {
        return assignments.assignEquipment(id, request);
    }

    @PutMapping("/{id}/equipment-assignments/{assignmentId}")
    @PreAuthorize("hasAuthority('project.manage')")
    public EquipmentAssignmentView updateEquipmentAssignment(@PathVariable("id") UUID id,
                                                             @PathVariable("assignmentId") UUID assignmentId,
                                                             @Valid @RequestBody AssignmentDatesRequest request) {
        return assignments.updateEquipmentAssignment(id, assignmentId, request);
    }

    @DeleteMapping("/{id}/equipment-assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('project.manage')")
    public void deleteEquipmentAssignment(@PathVariable("id") UUID id, @PathVariable("assignmentId") UUID assignmentId) {
        assignments.deleteEquipmentAssignment(id, assignmentId);
    }
}
