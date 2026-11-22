package com.safetics.tasks;

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

import com.safetics.tasks.TaskDtos.TaskCreateRequest;
import com.safetics.tasks.TaskDtos.TaskHistoryEntry;
import com.safetics.tasks.TaskDtos.TaskLinkRequest;
import com.safetics.tasks.TaskDtos.TaskLinksView;
import com.safetics.tasks.TaskDtos.TaskStatusRequest;
import com.safetics.tasks.TaskDtos.TaskTypeRequest;
import com.safetics.tasks.TaskDtos.TaskTypeView;
import com.safetics.tasks.TaskDtos.TaskUpdateRequest;
import com.safetics.tasks.TaskDtos.TaskView;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Типи задач і задачі. Перегляд — task.view (портал замовника задач не бачить). Хто може змінювати
 * задачу, вирішує TaskWorkflow: менеджер, супервайзер проєкту, виконавець (статус і дати своїх).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "tasks")
@PreAuthorize("hasAuthority('task.view')")
public class TaskController {

    private final TaskTypeService types;
    private final TaskWorkflow tasks;

    public TaskController(TaskTypeService types, TaskWorkflow tasks) {
        this.types = types;
        this.tasks = tasks;
    }

    // ---------------------------------------------------------------- типи

    @GetMapping("/task-types")
    public List<TaskTypeView> types(@RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return types.list(includeArchived);
    }

    @PostMapping("/task-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('catalog.manage')")
    public TaskTypeView createType(@Valid @RequestBody TaskTypeRequest request) {
        return types.create(request, null);
    }

    @PutMapping("/task-types/{id}")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public TaskTypeView updateType(@PathVariable("id") UUID id, @Valid @RequestBody TaskTypeRequest request) {
        return types.update(id, request);
    }

    @PostMapping("/task-types/{id}/archive")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public TaskTypeView archiveType(@PathVariable("id") UUID id) {
        return types.setArchived(id, true);
    }

    @PostMapping("/task-types/{id}/restore")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public TaskTypeView restoreType(@PathVariable("id") UUID id) {
        return types.setArchived(id, false);
    }

    // ---------------------------------------------------------------- задачі

    /** category: new / active / done / cancelled / open (new + active). */
    @GetMapping("/projects/{projectId}/tasks")
    public List<TaskView> list(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "assigneeId", required = false) UUID assigneeId,
            @RequestParam(name = "typeId", required = false) UUID typeId,
            @RequestParam(name = "serviceId", required = false) UUID serviceId) {
        return tasks.list(projectId, new TaskWorkflow.Filter(category, assigneeId, typeId, serviceId));
    }

    @PostMapping("/projects/{projectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(@PathVariable("projectId") UUID projectId, @Valid @RequestBody TaskCreateRequest request) {
        return tasks.create(projectId, request);
    }

    /** Мої задачі з усіх проєктів (за замовчуванням — відкриті). */
    @GetMapping("/tasks/my")
    public List<TaskView> mine(@RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return tasks.mine(includeClosed);
    }

    @GetMapping("/tasks/{id}")
    public TaskView get(@PathVariable("id") UUID id) {
        return tasks.get(id);
    }

    @PutMapping("/tasks/{id}")
    public TaskView update(@PathVariable("id") UUID id, @Valid @RequestBody TaskUpdateRequest request) {
        return tasks.update(id, request);
    }

    @PostMapping("/tasks/{id}/status")
    public TaskView changeStatus(@PathVariable("id") UUID id, @Valid @RequestBody TaskStatusRequest request) {
        return tasks.changeStatus(id, request);
    }

    @GetMapping("/tasks/{id}/links")
    public TaskLinksView links(@PathVariable("id") UUID id) {
        return tasks.links(id);
    }

    @PostMapping("/tasks/{id}/links")
    public TaskLinksView addLink(@PathVariable("id") UUID id, @Valid @RequestBody TaskLinkRequest request) {
        return tasks.addLink(id, request.taskId(), request.kind());
    }

    @DeleteMapping("/tasks/{id}/links/{linkId}")
    public TaskLinksView removeLink(@PathVariable("id") UUID id, @PathVariable("linkId") UUID linkId) {
        return tasks.removeLink(id, linkId);
    }

    @GetMapping("/tasks/{id}/history")
    public List<TaskHistoryEntry> history(@PathVariable("id") UUID id) {
        return tasks.history(id);
    }
}
