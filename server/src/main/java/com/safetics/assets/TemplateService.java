package com.safetics.assets;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import com.safetics.assets.AssetDtos.AssetTypeRequest;
import com.safetics.assets.AssetDtos.InstallTemplateResponse;
import com.safetics.core.audit.AuditService;
import com.safetics.core.web.ApiException;
import com.safetics.personnel.PersonnelDtos.PositionRequest;
import com.safetics.personnel.PositionService;
import com.safetics.services.ServiceDtos.ServiceTypeRequest;
import com.safetics.services.ServiceTypeService;
import com.safetics.tasks.TaskDtos.TaskTypeRequest;
import com.safetics.tasks.TaskTypeService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Галузеві шаблони: JSON-файли в {@code resources/templates}. Встановлення копіює типи обладнання
 * і посади в компанію; далі компанія змінює свою копію, а повторне встановлення наявні типи не перезаписує.
 */
@Service
public class TemplateService {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9-]{1,50}$");

    /** Вміст файлу шаблону. Пізніше сюди додадуться послуги, коди операцій, чек-листи. */
    public record TemplateDefinition(String key, Map<String, String> name, List<AssetTypeRequest> assetTypes,
                                     List<PositionRequest> positions, List<ServiceTypeRequest> serviceTypes,
                                     List<TaskTypeRequest> taskTypes) {
    }

    private final JsonMapper json;
    private final AssetTypeService types;
    private final PositionService positions;
    private final ServiceTypeService serviceTypes;
    private final TaskTypeService taskTypes;
    private final AuditService audit;

    public TemplateService(JsonMapper json, AssetTypeService types, PositionService positions,
                           ServiceTypeService serviceTypes, TaskTypeService taskTypes, AuditService audit) {
        this.taskTypes = taskTypes;
        this.json = json;
        this.types = types;
        this.positions = positions;
        this.serviceTypes = serviceTypes;
        this.audit = audit;
    }

    /** Шаблон у списку вибору: ключ і назва за мовами. */
    public record TemplateSummary(@jakarta.validation.constraints.NotNull String key,
                                  @jakarta.validation.constraints.NotNull Map<String, String> name) {
    }

    /** Усі шаблони з resources/templates, за ключем. */
    public List<TemplateSummary> list() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:templates/*.json");
            List<TemplateSummary> result = new ArrayList<>();
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    TemplateDefinition template = json.readValue(in, TemplateDefinition.class);
                    result.add(new TemplateSummary(template.key(), template.name()));
                }
            }
            result.sort(java.util.Comparator.comparing(TemplateSummary::key));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list templates", e);
        }
    }

    public TemplateDefinition load(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.validation("templateKey", "unknown_template");
        }
        ClassPathResource resource = new ClassPathResource("templates/" + key + ".json");
        if (!resource.exists()) {
            throw ApiException.validation("templateKey", "unknown_template");
        }
        try (InputStream in = resource.getInputStream()) {
            return json.readValue(in, TemplateDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read template " + key, e);
        }
    }

    public InstallTemplateResponse install(String key) {
        TemplateDefinition template = load(key);
        List<String> installed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AssetTypeRequest type : template.assetTypes()) {
            if (types.findByCode(type.code()).isPresent()) {
                skipped.add(type.code());
            } else {
                types.create(type, key);
                installed.add(type.code());
            }
        }
        List<String> installedPositions = new ArrayList<>();
        for (PositionRequest position : template.positions() == null ? List.<PositionRequest>of() : template.positions()) {
            if (positions.findByCode(position.code()).isEmpty()) {
                positions.create(position, key);
                installedPositions.add(position.code());
            }
        }
        List<String> installedServiceTypes = new ArrayList<>();
        for (ServiceTypeRequest serviceType : template.serviceTypes() == null ? List.<ServiceTypeRequest>of() : template.serviceTypes()) {
            if (serviceTypes.findByCode(serviceType.code()).isEmpty()) {
                serviceTypes.create(serviceType, key);
                installedServiceTypes.add(serviceType.code());
            }
        }
        List<String> installedTaskTypes = new ArrayList<>();
        for (TaskTypeRequest taskType : template.taskTypes() == null ? List.<TaskTypeRequest>of() : template.taskTypes()) {
            if (taskTypes.findByCode(taskType.code()).isEmpty()) {
                taskTypes.create(taskType, key);
                installedTaskTypes.add(taskType.code());
            }
        }
        audit.record("template", key, "install", Map.of("installed", installed, "skipped", skipped,
                "positions", installedPositions, "serviceTypes", installedServiceTypes, "taskTypes", installedTaskTypes));
        return new InstallTemplateResponse(installed, skipped, installedPositions, installedServiceTypes,
                installedTaskTypes);
    }
}
