package com.safetics.assets;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Запити й відповіді API типів об'єктів і об'єктів. Поля без @NotNull можуть бути null. */
public final class AssetDtos {

    private AssetDtos() {
    }

    // ---------------------------------------------------------------- типи

    /** category: primary — основне (виконує послуги, напр. бурова), auxiliary — допоміжне (авто, табір). */
    public record AssetTypeView(
            @NotNull UUID id,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull @Schema(allowableValues = {"primary", "auxiliary"}) String category,
            @NotNull List<FieldDefinition> fields,
            @NotNull List<StatusDefinition> statuses,
            @NotNull List<String> allowedChildTypes,
            @NotNull List<ChecklistItem> checklist,
            String templateKey,
            @NotNull boolean archived) {
    }

    /** Пункт чек-листа зміни: critical — непройдений пункт дає «Сервіс» з критичністю high і алерт менеджеру. */
    public record ChecklistItem(
            @NotBlank String key,
            @NotNull Map<String, String> label,
            @NotNull Boolean critical) {
    }

    /** Створення і зміна типу. code після створення не змінюється; category за замовчуванням auxiliary. */
    public record AssetTypeRequest(
            @NotBlank String code,
            @NotNull Map<String, String> name,
            @Schema(allowableValues = {"primary", "auxiliary"}) String category,
            @NotNull @Valid List<FieldDefinition> fields,
            @NotNull @Valid List<StatusDefinition> statuses,
            List<String> allowedChildTypes,
            @Valid List<ChecklistItem> checklist) {
    }

    public record InstallTemplateRequest(@NotBlank String templateKey) {
    }

    /**
     * installed — створені типи, skipped — уже були в компанії (не перезаписуються);
     * positions, serviceTypes, taskTypes — створені посади, типи послуг і задач (наявні не змінюються).
     */
    public record InstallTemplateResponse(@NotNull List<String> installed, @NotNull List<String> skipped,
                                          @NotNull List<String> positions, @NotNull List<String> serviceTypes,
                                          @NotNull List<String> taskTypes) {
    }

    // ---------------------------------------------------------------- об'єкти

    public record AssetView(
            @NotNull UUID id,
            @NotNull UUID typeId,
            @NotNull String typeCode,
            UUID parentId,
            @NotNull String code,
            String name,
            @NotNull String status,
            @NotNull Map<String, Object> attributes,
            @NotNull int version,
            @NotNull boolean archived,
            @NotNull OffsetDateTime createdAt,
            @NotNull OffsetDateTime updatedAt,
            @NotNull @Schema(allowableValues = {"ok", "low", "medium", "high"}) String serviceLevel,
            @NotNull int openServiceTasks) {
    }

    /** status == null — перший статус типу. */
    public record AssetCreateRequest(
            @NotNull UUID typeId,
            UUID parentId,
            @NotBlank String code,
            String name,
            String status,
            Map<String, Object> attributes) {
    }

    /** version — версія, яку бачив клієнт; якщо об'єкт уже змінили, відповідь 409 stale_version. */
    public record AssetUpdateRequest(
            UUID parentId,
            @NotBlank String code,
            String name,
            @NotBlank String status,
            Map<String, Object> attributes,
            @NotNull Integer version) {
    }

    public record AssetPage(@NotNull List<AssetView> items, @NotNull long total) {
    }

    /**
     * Тип з бібліотеки (типи всіх компаній, однакові код і назва — один рядок; компанії не називаються).
     * exists — у вашій компанії вже є тип з таким кодом.
     */
    public record LibraryTypeView(
            @NotNull UUID sourceId,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull String category,
            @NotNull int fieldsCount,
            @NotNull int checklistCount,
            @NotNull int companies,
            @NotNull boolean exists) {
    }

    /** Новий порядок типів (вкладок): усі активні типи компанії. */
    public record AssetTypeOrderRequest(@NotNull List<UUID> ids) {
    }
}
