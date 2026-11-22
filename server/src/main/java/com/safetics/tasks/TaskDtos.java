package com.safetics.tasks;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.safetics.assets.FieldDefinition;

import io.swagger.v3.oas.annotations.media.Schema;

/** Запити й відповіді API типів задач і задач. Поля без @NotNull можуть бути null. */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** Статус типу задачі. category: new / active / done / cancelled — за нею працюють правила. */
    public record TaskStatusDefinition(
            @NotBlank String code,
            @NotNull Map<String, String> label,
            @NotBlank @Schema(allowableValues = {"new", "active", "done", "cancelled"}) String category,
            String color) {
    }

    /**
     * Правило автоматизації. trigger: created — при створенні задачі, status_category — при переході
     * в категорію category, shift_created — при створенні зміни обладнання задачі (задача «Бурова на
     * проєкті»). Дія create_subtask: підзадача типу taskType (код), назва — шаблон з {type}, {title},
     * {number}, {service}, {asset}, {shift} (порожньо — «{type}: {service або title}»), виконавець за
     * роллю assignTo (shift_lead — бурильник зміни, інакше супервайзер), план завершення через
     * dueInDays днів, blockedBy — номер правила (від 0) з тим самим тригером, підзадача якого блокує цю.
     */
    public record TaskRule(
            @NotBlank @Schema(allowableValues = {"created", "status_category", "shift_created"}) String trigger,
            @Schema(allowableValues = {"active", "done", "cancelled"}) String category,
            @Schema(allowableValues = {"create_subtask"}) String action,
            @NotBlank String taskType,
            String title,
            @Schema(allowableValues = {"supervisor", "storekeeper", "parent_assignee", "author", "shift_lead", "none"}) String assignTo,
            Integer dueInDays,
            Integer blockedBy) {
    }

    /**
     * subjectKind — предмет задачі (none / service / asset). syncService — задача керує статусом
     * послуги. childrenBlockClose — не закривати, поки є незавершені підзадачі. closingFields —
     * форма закриття. autoCreate: primary_assignment — задача створюється на кожне призначення
     * основного обладнання на проєкт («Бурова на проєкті»; предмет — обладнання).
     */
    public record TaskTypeView(
            @NotNull UUID id,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull List<TaskStatusDefinition> statuses,
            @NotNull List<FieldDefinition> fields,
            @NotNull List<FieldDefinition> closingFields,
            @NotNull @Schema(allowableValues = {"none", "service", "asset"}) String subjectKind,
            @NotNull boolean syncService,
            @NotNull boolean childrenBlockClose,
            @NotNull List<TaskRule> rules,
            @NotNull @Schema(allowableValues = {"none", "primary_assignment"}) String autoCreate,
            String templateKey,
            @NotNull boolean archived) {
    }

    public record TaskTypeRequest(
            @NotBlank String code,
            @NotNull Map<String, String> name,
            @NotNull @Valid List<TaskStatusDefinition> statuses,
            @Valid List<FieldDefinition> fields,
            @Valid List<FieldDefinition> closingFields,
            @Schema(allowableValues = {"none", "service", "asset"}) String subjectKind,
            Boolean syncService,
            Boolean childrenBlockClose,
            @Valid List<TaskRule> rules,
            @Schema(allowableValues = {"none", "primary_assignment"}) String autoCreate) {
    }

    /**
     * Задача. statusCategory — категорія поточного статусу. openBlockers — скільки незавершених задач
     * блокують цю; childrenTotal / childrenOpen — підзадачі.
     */
    public record TaskView(
            @NotNull UUID id,
            @NotNull long number,
            @NotNull UUID projectId,
            @NotNull String projectCode,
            @NotNull UUID typeId,
            @NotNull String typeCode,
            @NotNull String title,
            String description,
            @NotNull String status,
            @NotNull @Schema(allowableValues = {"new", "active", "done", "cancelled"}) String statusCategory,
            UUID assigneeId,
            String assigneeName,
            UUID authorId,
            String authorName,
            UUID serviceId,
            String serviceCode,
            UUID assetId,
            String assetCode,
            UUID parentId,
            Long parentNumber,
            UUID workShiftId,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate forecastEnd,
            OffsetDateTime actualStart,
            OffsetDateTime actualEnd,
            @NotNull Map<String, Object> attributes,
            Map<String, Object> closingValues,
            @NotNull int openBlockers,
            @NotNull int childrenTotal,
            @NotNull int childrenOpen,
            @NotNull int version,
            @NotNull OffsetDateTime createdAt) {
    }

    /** Коротке посилання на задачу у зв'язках. linkId — для видалення зв'язку. */
    public record TaskRef(
            UUID linkId,
            @NotNull UUID id,
            @NotNull long number,
            @NotNull String title,
            @NotNull String status,
            @NotNull String statusCategory) {
    }

    /** blocks — задачі, які ця блокує; blockedBy — що блокує цю; children — підзадачі. */
    public record TaskLinksView(
            @NotNull List<TaskRef> blocks,
            @NotNull List<TaskRef> blockedBy,
            @NotNull List<TaskRef> related,
            @NotNull List<TaskRef> children) {
    }

    public record TaskHistoryEntry(
            @NotNull long id,
            String userName,
            @NotNull String action,
            Map<String, Object> changes,
            @NotNull OffsetDateTime createdAt) {
    }

    /** Предмет (serviceId / assetId) — за subjectKind типу. parentId — батьківська задача того ж проєкту. */
    public record TaskCreateRequest(
            @NotNull UUID typeId,
            @NotBlank String title,
            String description,
            UUID assigneeId,
            UUID serviceId,
            UUID assetId,
            UUID parentId,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            Map<String, Object> attributes) {
    }

    public record TaskUpdateRequest(
            @NotBlank String title,
            String description,
            UUID assigneeId,
            UUID serviceId,
            UUID assetId,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate forecastEnd,
            Map<String, Object> attributes,
            @NotNull Integer version) {
    }

    /** closingValues — форма закриття, потрібна при переході в статус категорії done. */
    public record TaskStatusRequest(
            @NotBlank String status,
            Map<String, Object> closingValues,
            @NotNull Integer version) {
    }

    public record TaskLinkRequest(
            @NotNull UUID taskId,
            @NotBlank @Schema(allowableValues = {"blocks", "blocked_by", "relates"}) String kind) {
    }
}
