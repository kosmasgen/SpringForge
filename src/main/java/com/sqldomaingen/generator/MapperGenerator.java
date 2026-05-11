package com.sqldomaingen.generator;

import com.sqldomaingen.model.Table;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import com.sqldomaingen.util.GeneratorSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@Log4j2
@Component
public class MapperGenerator {

    private final Map<String, Table> tableMap;

    public MapperGenerator(Map<String, Table> tableMap) {
        this.tableMap = Objects.requireNonNull(tableMap, "tableMap must not be null");
    }

    /**
     * Generates BaseMapper and one mapper per generated entity table.
     * <p>
     * Only tables that are expected to produce entity/DTO classes should generate
     * mapper classes. Pure join tables are skipped because they do not have their
     * own standalone entity/DTO mapper pair.
     *
     * @param outputDir generation root (project root output)
     * @param basePackage base package (e.g. gr.knowledge.schoolmanagement)
     */
    public void generateMappers(String outputDir, String basePackage) {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        Path mapperDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "mapper")
        );
        log.debug("Mappers output directory: {}", mapperDir.toAbsolutePath());

        generateBaseMapper(mapperDir, basePackage);

        for (Table table : tableMap.values()) {
            if (table == null) {
                continue;
            }

            if (table.isPureJoinTable()) {
                log.info("Skipping mapper generation for pure join table: {}", table.getName());
                continue;
            }

            generateMapper(table, mapperDir, basePackage);
        }

        log.debug("Mapper generation completed under: {}", mapperDir.toAbsolutePath());
    }

    /**
     * Generates the BaseMapper class with safe PATCH (partial update) support.
     *
     * @param mapperDir output directory
     * @param basePackage base package
     */
    private void generateBaseMapper(Path mapperDir, String basePackage) {
        Path file = mapperDir.resolve("BaseMapper.java");

        if (Files.exists(file)) {
            log.debug("BaseMapper already exists. Skipping: {}", file.toAbsolutePath());
            return;
        }

        String mapperPackage = PackageResolver.resolvePackageName(basePackage, "mapper");

        String content = """
package %s;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import org.modelmapper.ModelMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base mapper for converting between Entity and DTO using {@link ModelMapper}.
 *
 * @param <E> entity type
 * @param <D> dto type
 */
public abstract class BaseMapper<E, D> {

    protected final ModelMapper modelMapper;
    private final Class<E> entityClass;
    private final Class<D> dtoClass;

    protected BaseMapper(ModelMapper modelMapper, Class<E> entityClass, Class<D> dtoClass) {
        this.modelMapper = modelMapper;
        this.entityClass = entityClass;
        this.dtoClass = dtoClass;
    }

    /**
     * Converts an entity to a DTO.
     *
     * @param entity source entity
     * @return mapped dto
     */
    public D toDTO(E entity) {
        return modelMapper.map(entity, dtoClass);
    }

    /**
     * Converts a list of entities to DTOs.
     *
     * @param entityList source entities
     * @return mapped dto list
     */
    public List<D> toDTOList(List<E> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return List.of();
        }

        return entityList.stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Converts a DTO to an entity.
     *
     * @param dto source dto
     * @return mapped entity
     */
    public E toEntity(D dto) {
        return modelMapper.map(dto, entityClass);
    }

    /**
     * Converts a list of DTOs to entities.
     *
     * @param dtoList source dto list
     * @return mapped entity list
     */
    public List<E> toEntityList(List<D> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            return List.of();
        }

        return dtoList.stream()
                .map(this::toEntity)
                .toList();
    }

    /**
     * Applies non-null values from DTO into an existing entity.
     * Primary key fields are not modified.
     *
     * @param entity target entity already loaded from persistence
     * @param dto source dto containing partial values
     */
    public void partialUpdate(E entity, D dto) {
        if (entity == null || dto == null) {
            return;
        }

        E patchSource = modelMapper.map(dto, entityClass);
        mergeNonNullFields(patchSource, entity);
    }

    /**
     * Copies non-null field values from source entity to target entity.
     *
     * @param source source entity with patch values
     * @param target target entity to update
     */
    private void mergeNonNullFields(E source, E target) {
        for (Field field : getAllFields(entityClass)) {
            if (shouldSkipField(field)) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(source);

                if (value != null) {
                    field.set(target, value);
                }
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(
                        "Failed to apply partial update for field: " + field.getName(),
                        exception
                );
            }
        }
    }

    /**
     * Collects all declared fields from the given class hierarchy.
     *
     * @param type root entity class
     * @return collected fields
     */
    private List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> currentType = type;

        while (currentType != null && currentType != Object.class) {
            Collections.addAll(fields, currentType.getDeclaredFields());
            currentType = currentType.getSuperclass();
        }

        return fields;
    }

    /**
     * Checks whether the field should be skipped during partial update.
     *
     * @param field entity field
     * @return true when the field must not be updated
     */
    private boolean shouldSkipField(Field field) {
        int modifiers = field.getModifiers();

        return Modifier.isStatic(modifiers)
                || Modifier.isFinal(modifiers)
                || field.isAnnotationPresent(Id.class)
                || field.isAnnotationPresent(EmbeddedId.class);
    }
}
""".formatted(mapperPackage);

        GeneratorSupport.writeFile(file, content, true);
    }

    /**
     * Generates a concrete mapper class for the given table.
     *
     * <p>
     * This method creates a Spring {@code @Component} mapper that extends {@code BaseMapper}
     * and provides type-safe conversion between the generated Entity and DTO classes.
     * </p>
     *
     * <p>
     * The generated mapper:
     * <ul>
     *     <li>Uses naming conventions based on the table name</li>
     *     <li>Is placed under the {@code mapper} package</li>
     *     <li>Links the corresponding Entity and DTO types</li>
     *     <li>Is skipped only if the input table is invalid</li>
     * </ul>
     * </p>
     *
     * @param table source table metadata used to derive entity and DTO names
     * @param mapperDir target directory where the mapper class will be written
     * @param basePackage base package used to resolve mapper, entity, and dto packages
     *
     * @throws NullPointerException if {@code table} is null
     */
    private void generateMapper(Table table, Path mapperDir, String basePackage) {
        Objects.requireNonNull(table, "table must not be null");

        String normalizedTableName = GeneratorSupport.normalizeTableName(table.getName());
        String entityName = NamingConverter.toPascalCase(normalizedTableName);
        String dtoName = entityName + "Dto";
        String mapperName = entityName + "Mapper";

        String mapperPackage = PackageResolver.resolvePackageName(basePackage, "mapper");
        String entityPackage = PackageResolver.resolvePackageName(basePackage, "entity");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");

        String content = """
                package %s;

                import org.modelmapper.ModelMapper;
                import org.springframework.stereotype.Component;

                import %s.%s;
                import %s.%s;

                /**
                 * Mapper for {@link %s} and {@link %s}.
                 */
                @Component
                public class %s extends BaseMapper<%s, %s> {

                    public %s(ModelMapper modelMapper) {
                        super(modelMapper, %s.class, %s.class);
                    }
                }
                """.formatted(
                mapperPackage,
                entityPackage, entityName,
                dtoPackage, dtoName,
                entityName, dtoName,
                mapperName, entityName, dtoName,
                mapperName, entityName, dtoName
        );

        Path file = mapperDir.resolve(mapperName + ".java");
        GeneratorSupport.writeFile(file, content);
    }
}
